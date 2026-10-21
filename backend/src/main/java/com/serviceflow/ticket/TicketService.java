package com.serviceflow.ticket;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.serviceflow.audit.AuditMapper;
import com.serviceflow.audit.IdempotencyService;
import com.serviceflow.contract.ContractService;
import com.serviceflow.identity.IdentityService;
import com.serviceflow.shared.BusinessException;
import com.serviceflow.sla.SlaMapper;
import com.serviceflow.sla.SlaService;
import com.serviceflow.task.TaskMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 工单应用服务，协调合同权益、资源权限、SLA、活动、审计和请求幂等。
 * 已有工单的写操作先锁定工单，再检查最新授权；所有业务写入与幂等结果同事务提交。
 * 默认使用 READ_COMMITTED，列表方法单独采用一致的只读快照。
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class TicketService {
    public record Created(long ticketId, String status, String priority, int version, boolean slaStarted) {}
    public record MessageAdded(long messageId, long ticketId, int ticketVersion) {}
    public record Triaged(long ticketId, String status, int ticketVersion, long triageRecordId, String source) {}
    public record Details(TicketMapper.Ticket ticket, SlaMapper.Snapshot sla, List<TicketMapper.Activity> activities) {}
    /** 取消结果；ticketVersion 为变更后的版本，cancelledAt 为本次操作统一使用的业务时间。 */
    public record Cancelled(long ticketId, String status, int ticketVersion, Instant cancelledAt) {}
    /**
     * 工单分页结果。
     * @param items 当前页摘要，不包含原始问题描述或内部活动
     * @param total 当前权限及筛选条件下的总条数，不是全库工单数
     * @param page 从 1 开始的请求页码，超出末页时 items 为空
     * @param size 每页条数上限，末页可以不足此数量
     */
    public record Page(List<TicketMapper.ListItem> items, long total, int page, int size) {}

    private final TicketMapper tickets;
    private final IdentityService identities;
    private final ContractService contracts;
    private final SlaMapper sla;
    private final SlaService slaService;
    private final TaskMapper tasks;
    private final AuditMapper audit;
    private final IdempotencyService idempotency;
    private final Clock clock;
    private final Set<String> products;

    public TicketService(TicketMapper tickets, IdentityService identities, ContractService contracts,
                         SlaMapper sla, SlaService slaService, TaskMapper tasks,
                         AuditMapper audit, IdempotencyService idempotency, Clock clock,
                         @Value("${serviceflow.products}") String products) {
        this.tickets = tickets;
        this.identities = identities;
        this.contracts = contracts;
        this.sla = sla;
        this.slaService = slaService;
        this.tasks = tasks;
        this.audit = audit;
        this.idempotency = idempotency;
        this.clock = clock;
        this.products = Set.copyOf(Arrays.asList(products.split(",")));
    }

    /**
     * 按当前登录人的权限分页读取工单；总数和条目共用筛选条件与事务快照。
     * @param query 已完成格式校验的分页及筛选条件，身份和角色从登录上下文获取
     * @return 同一读取快照中的总数和当前页
     */
    // 总数和分页数据使用同一读取快照；授权范围在 SQL 内应用，不先分页后过滤。
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(TicketCommands.Query query) {
        var actor = identities.currentActor();
        long total = tickets.countVisible(actor.id(), actor.role(), query);
        var items = tickets.listVisible(actor.id(), actor.role(), query, query.offset());
        return new Page(items, total, query.page(), query.size());
    }

    /**
     * 取消待受理或已受理且没有活动任务的工单，同时停止解决计时并记录原因。
     * 当前权限校验先于幂等重放；合法重试返回原结果，不再次递增版本或累计耗时。
     * @param ticketId 待取消的工单 ID
     * @param command 预期工单版本和必填取消原因
     * @param key 同一次取消操作重试时复用的幂等键
     * @return 本次取消或合法幂等重放的结果
     */
    public Cancelled cancel(long ticketId, TicketCommands.Cancel command, String key) {
        var ticket = tickets.lock(ticketId);
        var actor = identities.currentActor();
        if (!Set.of("CUSTOMER_CONTACT", "SUPPORT_AGENT").contains(actor.role()))
            throw new BusinessException(403, "RESOURCE_FORBIDDEN", "当前角色不能取消工单");
        authorize(actor, ticket);
        if (actor.role().equals("CUSTOMER_CONTACT") && actor.id() != ticket.creatorUserId())
            throw BusinessException.forbidden();
        var now = now();
        var reservation = idempotency.reserve(actor.id(), "TICKET_CANCEL", "/tickets/" + ticketId + "/cancel", key, command, now);
        if (reservation.replay()) return idempotency.replay(reservation, Cancelled.class);
        version(ticket, command.expectedTicketVersion());
        if (!Set.of("SUBMITTED", "TRIAGED").contains(ticket.status()))
            throw BusinessException.invalid("INVALID_STATE_TRANSITION", "仅待受理或已受理的工单可主动取消");
        if (tasks.activeCount(ticketId) != 0)
            throw BusinessException.conflict("ACTIVE_TASK_EXISTS", "请先由主管合法取消未派任务");
        changed(tickets.cancel(ticketId, ticket.version(), now));
        slaService.cancel(ticketId, actor.id(), now, command.reason());
        changed(tickets.addActivity(IdWorker.getId(), ticketId, actor.id(), "TICKET_CANCELLED", command.reason(), true, now));
        audit(ticket, actor, "TICKET_CANCELLED", key, Map.of("fromStatus", ticket.status(), "reason", command.reason()), now);
        var response = new Cancelled(ticketId, "CANCELLED", ticket.version()+1, now);
        idempotency.finish(reservation, 200, response, now);
        return response;
    }

    /**
     * 根据合同权益和影响程度、紧急程度创建工单，冻结路由及 SLA 策略版本。
     * @param command 客户的原始申报；客户归属和合同覆盖范围需由服务端核实
     * @param key 本次创建操作的幂等键
     */
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
                product, productVersion, command.serviceType(), null, command.impact(), command.urgency(), priority, "SUBMITTED", 0, now, 0, null);
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

    /**
     * 查询有权访问的工单、SLA 和活动，客户只能看到公开活动。
     * @param ticketId 工单 ID；不存在或不在可见范围内时按资源不可访问处理
     */
    @Transactional(readOnly = true)
    public Details detail(long ticketId) {
        var ticket = tickets.byId(ticketId);
        var actor = identities.currentActor();
        authorize(actor, ticket);
        return new Details(ticket, sla.snapshot(ticketId), tickets.activities(ticketId, !actor.role().equals("CUSTOMER_CONTACT")));
    }

    /**
     * 追加消息并递增工单版本；仅人工公开答复可能触发首次响应计时结算。
     * @param ticketId 工单 ID
     * @param command 消息内容、可见性、预期版本及可选产品申报
     * @param key 本次消息的幂等键，避免重试产生重复活动或首响事件
     */
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

    /**
     * 客服人工受理：核验事实引文和知识适用性后保存受理记录，不把受理动作视为首响。
     * @param ticketId 待受理工单 ID
     * @param command 预期版本及人工确认内容；当前不允许非空 AI 草稿 ID
     * @param key 本次确认受理的幂等键
     */
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

    void authorize(IdentityService.Actor actor, TicketMapper.Ticket ticket) {
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
