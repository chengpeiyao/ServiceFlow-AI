package com.serviceflow.ticket;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.serviceflow.audit.AuditMapper;
import com.serviceflow.audit.IdempotencyService;
import com.serviceflow.identity.IdentityService;
import com.serviceflow.shared.BusinessException;
import com.serviceflow.sla.SlaService;
import com.serviceflow.task.TaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ResolutionService {
    public record Submitted(long ticketId, long resolutionId, int cycleNo, String ticketStatus, int ticketVersion) {}
    public record Decided(long ticketId, long resolutionId, long acceptanceId, int cycleNo, String decision,
                          String ticketStatus, int ticketVersion, Long remediationTaskId) {}

    private final TicketMapper tickets;
    private final TaskMapper tasks;
    private final ResolutionMapper resolutions;
    private final TicketService ticketService;
    private final IdentityService identities;
    private final IdempotencyService idempotency;
    private final AuditMapper audit;
    private final SlaService sla;
    private final Clock clock;

    public ResolutionService(TicketMapper tickets, TaskMapper tasks, ResolutionMapper resolutions,
                             TicketService ticketService, IdentityService identities, IdempotencyService idempotency,
                             AuditMapper audit, SlaService sla, Clock clock) {
        this.tickets = tickets; this.tasks = tasks; this.resolutions = resolutions; this.ticketService = ticketService;
        this.identities = identities; this.idempotency = idempotency; this.audit = audit; this.sla = sla; this.clock = clock;
    }

    public Submitted submit(long ticketId, ResolutionCommands.Submit command, String key) {
        var ticket = tickets.lock(ticketId);
        var actor = identities.currentActor();
        actor.requireRole("ENGINEER");
        ticketService.authorize(actor, ticket);
        var now = now();
        var reservation = idempotency.reserve(actor.id(), "RESOLUTION_SUBMIT", "/tickets/" + ticketId + "/resolutions", key, command, now);
        var task = tasks.lock(command.taskId());
        // 重放也检查当前轮次与归属，整改开始后不得借旧方案键恢复上轮授权。
        int currentCycle = Set.of("PENDING_ACCEPTANCE", "CLOSED").contains(ticket.status())
                ? ticket.resolutionCycleNo() : ticket.resolutionCycleNo()+1;
        if (task == null || task.ticketId() != ticketId || !Objects.equals(task.currentAssigneeId(), actor.id())
                || task.resolutionCycleNo() != currentCycle) throw BusinessException.forbidden();
        if (reservation.replay()) return idempotency.replay(reservation, Submitted.class);
        version(ticket, command.expectedTicketVersion());
        if (task.version() != command.expectedTaskVersion())
            throw BusinessException.conflict("TASK_VERSION_CONFLICT", "任务已变化，请刷新");
        require(ticket.status().equals("IN_PROGRESS") && task.status().equals("COMPLETED")
                && task.isBlocking() == 1 && tasks.activeCount(ticketId) == 0, "本轮阻塞任务完成且无活动任务后才能提交方案");
        long id = IdWorker.getId();
        changed(resolutions.insert(id, ticketId, currentCycle, command.summary(), actor.id(), now));
        changed(tickets.propose(ticketId, ticket.version(), now));
        sla.pause(ticketId, id, actor.id(), now);
        changed(tickets.addActivity(IdWorker.getId(), ticketId, actor.id(), "RESOLUTION_PROPOSED", command.summary(), true, now));
        audit(ticket, actor, "RESOLUTION_PROPOSED", key, Map.of("resolutionId", id, "cycleNo", currentCycle, "taskId", task.id()), now);
        var response = new Submitted(ticketId, id, currentCycle, "PENDING_ACCEPTANCE", ticket.version()+1);
        idempotency.finish(reservation, 201, response, now);
        return response;
    }

    public Decided decide(long resolutionId, ResolutionCommands.Decide command, String key) {
        var resolution = resolutions.byId(resolutionId);
        if (resolution == null) throw BusinessException.forbidden();
        var ticket = tickets.lock(resolution.ticketId());
        var actor = identities.currentActor();
        if (ticket == null) throw BusinessException.forbidden();
        identities.requireCustomer(actor, ticket.customerId());
        if (actor.id() != ticket.creatorUserId()) throw BusinessException.forbidden();
        var now = now();
        var reservation = idempotency.reserve(actor.id(), "RESOLUTION_ACCEPTANCE", "/resolutions/" + resolutionId + "/acceptance", key, command, now);
        if (reservation.replay()) return idempotency.replay(reservation, Decided.class);
        version(ticket, command.expectedTicketVersion());
        require(ticket.status().equals("PENDING_ACCEPTANCE") && ticket.resolutionCycleNo() == resolution.cycleNo(),
                "仅能验收当前待验收方案");
        boolean accept = command.decision().equals("ACCEPT");
        if (!accept && (command.reason() == null || command.reason().isBlank()))
            throw BusinessException.invalid("REJECTION_REASON_REQUIRED", "拒绝验收必须说明原因");
        long acceptanceId = IdWorker.getId();
        changed(resolutions.decide(acceptanceId, ticket.id(), resolutionId, resolution.cycleNo(),
                command.decision(), actor.id(), command.reason(), now));
        Long taskId = null;
        if (accept) {
            sla.close(ticket.id(), acceptanceId, actor.id(), now);
        } else {
            taskId = IdWorker.getId();
            changed(tasks.insertRework(taskId, ticket.id(), resolution.cycleNo()+1, resolutionId, actor.id(), now));
            changed(tasks.activity(IdWorker.getId(), ticket.id(), taskId, actor.id(), "REMEDIATION_CREATED", command.reason(), now));
            sla.resume(ticket.id(), acceptanceId, actor.id(), now);
        }
        String status = accept ? "CLOSED" : "IN_PROGRESS";
        changed(tickets.decide(ticket.id(), ticket.version(), status, accept ? now : null, now));
        changed(tickets.addActivity(IdWorker.getId(), ticket.id(), actor.id(), "ACCEPTANCE_" + command.decision(),
                accept ? "客户验收通过" : command.reason(), true, now));
        var details = new LinkedHashMap<String, Object>();
        details.put("resolutionId", resolutionId); details.put("acceptanceId", acceptanceId);
        details.put("decision", command.decision()); details.put("reason", command.reason()); details.put("remediationTaskId", taskId);
        audit(ticket, actor, "ACCEPTANCE_" + command.decision(), key, details, now);
        var response = new Decided(ticket.id(), resolutionId, acceptanceId, resolution.cycleNo(),
                command.decision(), status, ticket.version()+1, taskId);
        idempotency.finish(reservation, 200, response, now);
        return response;
    }

    public List<ResolutionMapper.History> history(long ticketId) {
        var ticket = tickets.lock(ticketId);
        ticketService.authorize(identities.currentActor(), ticket);
        return resolutions.history(ticketId);
    }
    private void version(TicketMapper.Ticket ticket, int expected) {
        if (ticket.version() != expected) throw BusinessException.conflict("VERSION_CONFLICT", "工单已变化，请刷新");
    }
    private void require(boolean allowed, String message) {
        if (!allowed) throw BusinessException.invalid("INVALID_STATE_TRANSITION", message);
    }
    private void changed(int count) {
        if (count != 1) throw BusinessException.conflict("VERSION_CONFLICT", "聚合更新冲突，请刷新");
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    private void audit(TicketMapper.Ticket ticket, IdentityService.Actor actor, String action, String key,
                       Map<String, Object> details, Instant now) {
        audit.insert(IdWorker.getId(), actor.id(), ticket.customerId(), ticket.id(), action, key, idempotency.encode(details), now);
    }
}
