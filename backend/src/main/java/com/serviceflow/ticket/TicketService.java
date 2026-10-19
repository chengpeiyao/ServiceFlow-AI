package com.serviceflow.ticket;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.serviceflow.audit.AuditMapper;
import com.serviceflow.audit.IdempotencyService;
import com.serviceflow.contract.ContractService;
import com.serviceflow.identity.IdentityService;
import com.serviceflow.shared.BusinessException;
import com.serviceflow.sla.SlaMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class TicketService {
    public record Created(long ticketId, String status, String priority, int version, boolean slaStarted) {}
    public record MessageAdded(long messageId, long ticketId, int ticketVersion) {}
    public record Triaged(long ticketId, String status, int ticketVersion, long triageRecordId, String source) {}
    public record Details(TicketMapper.Ticket ticket, SlaMapper.Snapshot sla, List<TicketMapper.Activity> activities) {}

    private final TicketMapper tickets;
    private final IdentityService identities;
    private final ContractService contracts;
    private final SlaMapper sla;
    private final AuditMapper audit;
    private final IdempotencyService idempotency;
    private final Clock clock;
    private final Set<String> products;

    public TicketService(TicketMapper tickets, IdentityService identities, ContractService contracts,
                         SlaMapper sla, AuditMapper audit, IdempotencyService idempotency, Clock clock,
                         @Value("${serviceflow.products}") String products) {
        this.tickets = tickets;
        this.identities = identities;
        this.contracts = contracts;
        this.sla = sla;
        this.audit = audit;
        this.idempotency = idempotency;
        this.clock = clock;
        this.products = Set.copyOf(Arrays.asList(products.split(",")));
    }

    public Created create(TicketCommands.Create command, String key) {
        var actor = identities.currentActor();
        identities.requireCustomer(actor, command.customerId());
        String product = product(command.reportedProductCode());
        String productVersion = productVersion(command.reportedProductVersion());
        Instant now = now();
        var reservation = idempotency.reserve(actor.id(), "TICKET_CREATE", "/tickets", key, command, now);
        if (reservation.replay()) return idempotency.replay(reservation, Created.class);

        String priority = priority(command.impact(), command.urgency());
        var coverage = contracts.resolve(command.customerId(), command.contractId(), command.serviceType(), priority, now);
        long ticketId = IdWorker.getId();
        var ticket = new TicketMapper.Ticket(ticketId, command.customerId(), command.contractId(), actor.id(),
                coverage.route().ownerTeamId(), coverage.route().routingVersion(), command.title(), command.description(),
                product, productVersion, command.serviceType(), null, command.impact(), command.urgency(), priority, "SUBMITTED", 0, now);
        tickets.insert(ticket);
        sla.create(IdWorker.getId(), ticketId, coverage.policy().id(), now,
                now.plus(coverage.policy().responseTargetMinutes(), ChronoUnit.MINUTES),
                now.plus(coverage.policy().resolutionTargetMinutes(), ChronoUnit.MINUTES));
        sla.event(IdWorker.getId(), ticketId, "START", now, "CREATE:" + ticketId, actor.id(), "合同权益通过，冻结 SLA 版本");
        audit(ticket, actor, "TICKET_CREATED", key, Map.of("policyVersionId", coverage.policy().id(), "productSource", "CUSTOMER_REPORTED"), now);
        var response = new Created(ticketId, "SUBMITTED", priority, 0, true);
        idempotency.finish(reservation, 201, response, now);
        return response;
    }

    @Transactional(readOnly = true)
    public Details detail(long ticketId) {
        var ticket = tickets.byId(ticketId);
        var actor = identities.currentActor();
        authorize(actor, ticket);
        return new Details(ticket, sla.snapshot(ticketId), tickets.activities(ticketId, !actor.role().equals("CUSTOMER_CONTACT")));
    }

    public MessageAdded append(long ticketId, TicketCommands.Message command, String key) {
        var ticket = tickets.lock(ticketId);
        var actor = identities.currentActor();
        authorize(actor, ticket);
        if (!Set.of("CUSTOMER_CONTACT", "SUPPORT_AGENT", "ENGINEER").contains(actor.role())) {
            throw new BusinessException(403, "RESOURCE_FORBIDDEN", "当前角色不能发送工单消息");
        }
        var now = now();
        var reservation = idempotency.reserve(actor.id(), "TICKET_MESSAGE", "/tickets/" + ticketId + "/messages", key, command, now);
        if (reservation.replay()) return idempotency.replay(reservation, MessageAdded.class);
        version(ticket, command.expectedTicketVersion());
        if (Set.of("CLOSED", "CANCELLED").contains(ticket.status())) {
            throw BusinessException.invalid("INVALID_STATE_TRANSITION", "已结束的工单不能追加处理信息");
        }

        boolean customer = actor.role().equals("CUSTOMER_CONTACT");
        boolean visible = command.customerVisible() == null || command.customerVisible();
        if (customer && !visible) throw BusinessException.invalid("MESSAGE_VISIBILITY_INVALID", "客户消息必须对客户可见");
        boolean updatesProduct = command.reportedProductCode() != null || command.reportedProductVersion() != null;
        if (updatesProduct && (!customer || !ticket.status().equals("SUBMITTED"))) {
            throw BusinessException.invalid("PRODUCT_IDENTIFICATION_INVALID", "仅客户可在待受理阶段补充产品申报");
        }

        String product = ticket.reportedProductCode();
        String productVersion = ticket.reportedProductVersion();
        if (command.reportedProductCode() != null) {
            String newProduct = product(command.reportedProductCode());
            // 更换产品而未申报新版本时，不能把旧产品版本带给新产品。
            if (!Objects.equals(newProduct, product)) productVersion = null;
            product = newProduct;
        }
        if (command.reportedProductVersion() != null) productVersion = productVersion(command.reportedProductVersion());
        long messageId = IdWorker.getId();
        tickets.addActivity(messageId, ticketId, actor.id(), customer ? "CUSTOMER_SUPPLEMENT" : (visible ? "PUBLIC_REPLY" : "INTERNAL_NOTE"), command.content(), visible, now);
        changed(tickets.appendVersion(ticketId, ticket.version(), product, productVersion, now));
        // 客户补充、内部备注和受理本身不计入人工首次响应。
        if (!customer && visible && Set.of("SUPPORT_AGENT", "ENGINEER").contains(actor.role())) {
            var snapshot = sla.snapshot(ticketId);
            if (snapshot == null) throw new IllegalStateException("工单缺少 SLA 快照");
            if (sla.recordResponse(ticketId, now, !now.isAfter(snapshot.responseDueAt())) == 1) {
                sla.event(IdWorker.getId(), ticketId, "FIRST_RESPONSE", now, "MESSAGE:" + messageId, actor.id(), "人工发送有效公开答复");
            }
        }
        audit(ticket, actor, "TICKET_MESSAGE_ADDED", key, Map.of("messageId", messageId, "productUpdated", updatesProduct, "source", customer ? "CUSTOMER_REPORTED" : "HUMAN_REPLY"), now);
        var response = new MessageAdded(messageId, ticketId, ticket.version() + 1);
        idempotency.finish(reservation, 201, response, now);
        return response;
    }

    public Triaged triage(long ticketId, TicketCommands.Triage command, String key) {
        var ticket = tickets.lock(ticketId);
        var actor = identities.currentActor();
        actor.requireRole("SUPPORT_AGENT");
        authorize(actor, ticket);
        var now = now();
        var reservation = idempotency.reserve(actor.id(), "TICKET_TRIAGE", "/tickets/" + ticketId + "/triage", key, command, now);
        if (reservation.replay()) return idempotency.replay(reservation, Triaged.class);
        version(ticket, command.expectedTicketVersion());
        if (!"SUBMITTED".equals(ticket.status())) throw BusinessException.invalid("INVALID_STATE_TRANSITION", "仅待受理工单可以确认受理");
        if (command.proposalId() != null) throw BusinessException.invalid("AI_TRIAGE_NOT_READY", "S1 仅支持人工受理，AI 草稿确认在 S3 接入");
        validateEvidence(ticket, command);
        long recordId = IdWorker.getId();
        var facts = new LinkedHashMap<String, Object>();
        facts.put("facts", command.confirmedFacts());
        facts.put("humanNotes", Objects.toString(command.humanNotes(), ""));
        tickets.insertTriage(recordId, ticketId, command.confirmedCategory(), idempotency.encode(facts),
                idempotency.encode(command.confirmedFollowups()), idempotency.encode(command.confirmedDiagnostics()), actor.id(), now);
        changed(tickets.triage(ticketId, ticket.version(), command.confirmedCategory(), now));
        audit(ticket, actor, "TICKET_TRIAGED", key, Map.of("triageRecordId", recordId, "source", "MANUAL", "category", command.confirmedCategory()), now);
        var response = new Triaged(ticketId, "TRIAGED", ticket.version() + 1, recordId, "MANUAL");
        idempotency.finish(reservation, 200, response, now);
        return response;
    }

    private void validateEvidence(TicketMapper.Ticket ticket, TicketCommands.Triage command) {
        for (var fact : command.confirmedFacts()) {
            String source = fact.sourceMessageId() == null ? ticket.originalDescription() : tickets.customerSource(ticket.id(), fact.sourceMessageId());
            if (source == null || !source.contains(fact.sourceQuote())) {
                throw BusinessException.invalid("FACT_SOURCE_INVALID", "事实引文不在指定客户原文或补充消息中");
            }
        }
        for (var diagnostic : command.confirmedDiagnostics()) {
            if (diagnostic.claimType().equals("EVIDENCE_BASED") && diagnostic.knowledgeRefs().isEmpty()) {
                throw BusinessException.invalid("KNOWLEDGE_REFERENCE_INVALID", "有依据的诊断必须提供适用的知识引用");
            }
            for (long id : diagnostic.knowledgeRefs()) {
                var article = tickets.knowledge(id);
                boolean approved = article != null && article.status().equals("APPROVED");
                boolean general = approved && article.productCode().equals("GENERAL") && article.applicableVersions().equals("ALL");
                boolean productMatch = approved && ticket.reportedProductCode() != null && article.productCode().equals(ticket.reportedProductCode());
                boolean versionMatch = approved && (article.applicableVersions().equals("ALL") || (ticket.reportedProductVersion() != null
                        && Arrays.stream(article.applicableVersions().split(",")).map(String::trim).anyMatch(ticket.reportedProductVersion()::equals)));
                if (!approved || (!general && !(productMatch && versionMatch))) {
                    throw BusinessException.invalid("KNOWLEDGE_REFERENCE_INVALID", "知识引用不存在、未审核或不适用当前产品版本");
                }
            }
        }
    }

    private void authorize(IdentityService.Actor actor, TicketMapper.Ticket ticket) {
        if (ticket == null) throw BusinessException.forbidden();
        switch (actor.role()) {
            case "CUSTOMER_CONTACT" -> identities.requireCustomer(actor, ticket.customerId());
            case "SUPPORT_AGENT", "OPS_ADMIN" -> { }
            case "SERVICE_MANAGER" -> { if (!identities.manages(actor, ticket.ownerTeamId())) throw BusinessException.forbidden(); }
            case "ENGINEER" -> { if (tickets.currentEngineer(ticket.id(), actor.id()) == 0) throw BusinessException.forbidden(); }
            default -> throw BusinessException.forbidden();
        }
    }

    private void audit(TicketMapper.Ticket ticket, IdentityService.Actor actor, String action, String key, Object details, Instant now) {
        audit.insert(IdWorker.getId(), actor.id(), ticket.customerId(), ticket.id(), action, key, idempotency.encode(details), now);
    }

    private void version(TicketMapper.Ticket ticket, int expected) {
        if (ticket.version() != expected) throw BusinessException.conflict("VERSION_CONFLICT", "工单已变化，请刷新后重新确认");
    }

    private void changed(int count) {
        if (count != 1) throw BusinessException.conflict("VERSION_CONFLICT", "工单更新冲突，请刷新后重试");
    }

    private String product(String value) {
        String normalized = normalize(value);
        if (normalized != null && !products.contains(normalized)) throw BusinessException.invalid("PRODUCT_IDENTIFICATION_INVALID", "产品代码不在演示目录中");
        return normalized;
    }

    private String productVersion(String value) {
        String normalized = normalize(value);
        if (normalized != null && !normalized.matches("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,79}")) {
            throw BusinessException.invalid("PRODUCT_IDENTIFICATION_INVALID", "版本号格式不符合要求");
        }
        return normalized;
    }

    private String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }

    private String priority(String impact, String urgency) {
        if ((impact.equals("HIGH") && !urgency.equals("LOW")) || (impact.equals("MEDIUM") && urgency.equals("HIGH"))) return "P1";
        if ((impact.equals("MEDIUM") && urgency.equals("LOW")) || (impact.equals("LOW") && !urgency.equals("HIGH"))) return "P3";
        return "P2";
    }
}
