package com.serviceflow.task;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.serviceflow.audit.AuditMapper;
import com.serviceflow.audit.IdempotencyService;
import com.serviceflow.identity.IdentityService;
import com.serviceflow.shared.BusinessException;
import com.serviceflow.ticket.TicketMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class TaskService {
    public record Changed(long ticketId, long taskId, String ticketStatus, String taskStatus,
                          int ticketVersion, int taskVersion, Long currentAssigneeId) {}
    public record Details(TaskMapper.Task task, List<TaskMapper.Assignment> assignments,
                          List<TaskMapper.Activity> activities) {}
    private record Locked(TicketMapper.Ticket ticket, TaskMapper.Task task, IdentityService.Actor actor,
                          IdempotencyService.Reservation reservation, Instant now) {}

    private final TicketMapper tickets;
    private final TaskMapper tasks;
    private final IdentityService identities;
    private final IdempotencyService idempotency;
    private final AuditMapper audit;
    private final Clock clock;

    public TaskService(TicketMapper tickets, TaskMapper tasks, IdentityService identities,
                       IdempotencyService idempotency, AuditMapper audit, Clock clock) {
        this.tickets = tickets; this.tasks = tasks; this.identities = identities;
        this.idempotency = idempotency; this.audit = audit; this.clock = clock;
    }

    public Changed create(long ticketId, TaskCommands.Create command, String key) {
        var ticket = tickets.lock(ticketId);
        var actor = identities.currentActor();
        manager(actor, ticket);
        var now = now();
        var reservation = idempotency.reserve(actor.id(), "TASK_CREATE", "/tickets/" + ticketId + "/tasks", key, command, now);
        if (reservation.replay()) return idempotency.replay(reservation, Changed.class);
        ticketVersion(ticket, command.expectedTicketVersion());
        // 整改任务由拒绝验收事务内部创建，不对 IN_PROGRESS 开放任意建任务。
        require(ticket.status().equals("TRIAGED"), "仅已受理且未派工的工单可创建首轮任务");
        if (tasks.activeCount(ticketId) != 0) throw BusinessException.conflict("ACTIVE_TASK_EXISTS", "工单已有活动阻塞任务");
        long taskId = IdWorker.getId();
        try { changed(tasks.insert(taskId, ticketId, command.title(), actor.id(), now)); }
        catch (DuplicateKeyException error) { throw BusinessException.conflict("ACTIVE_TASK_EXISTS", "工单已有活动阻塞任务"); }
        changed(tickets.taskState(ticketId, ticket.version(), ticket.status(), now));
        tasks.activity(IdWorker.getId(), ticketId, taskId, actor.id(), "TASK_CREATED", command.title(), now);
        audit(ticket, taskId, actor, "TASK_CREATED", key, Map.of("title", command.title()), now);
        var response = new Changed(ticketId, taskId, ticket.status(), "CREATED", ticket.version()+1, 0, null);
        idempotency.finish(reservation, 201, response, now);
        return response;
    }

    // 读取也持有短工单锁，避免校验归属后恰逢转派而读取新负责人的内部记录。
    public Details detail(long taskId) {
        var ticket = locateAndLock(taskId);
        var actor = identities.currentActor();
        var task = tasks.byId(taskId);
        if (task == null || task.ticketId() != ticket.id()) throw BusinessException.forbidden();
        switch (actor.role()) {
            case "SERVICE_MANAGER" -> manager(actor, ticket);
            case "ENGINEER" -> owner(actor, task);
            case "SUPPORT_AGENT", "OPS_ADMIN" -> { }
            default -> throw BusinessException.forbidden();
        }
        return new Details(task, tasks.assignments(taskId), tasks.activities(taskId));
    }

    public Changed assign(long taskId, TaskCommands.Assign command, String key) {
        var locked = lock(taskId, "TASK_ASSIGN", "assignments", command, key, true);
        if (locked.reservation().replay()) return replay(locked);
        versions(locked, command.expectedTicketVersion(), command.expectedTaskVersion());
        var task = locked.task(); var ticket = locked.ticket();
        boolean initial = ticket.status().equals("TRIAGED") && ticket.resolutionCycleNo() == 0
                && task.resolutionCycleNo() == 1 && task.sourceResolutionId() == null;
        boolean rework = ticket.status().equals("IN_PROGRESS") && task.sourceResolutionId() != null
                && task.resolutionCycleNo() == ticket.resolutionCycleNo()+1
                && tasks.rejectedSource(ticket.id(), task.sourceResolutionId(), ticket.resolutionCycleNo()) == 1;
        require((initial || rework) && task.status().equals("CREATED") && task.currentAssigneeId() == null,
                "首次派工需要首轮未派任务或当前轮次的真实整改任务");
        eligible(locked, command.assigneeId());
        return assignTo(locked, command.assigneeId(), command.reason(), rework ? "IN_PROGRESS" : "ASSIGNED", "TASK_ASSIGNED", key);
    }

    public Changed reassign(long taskId, TaskCommands.Reassign command, String key) {
        var locked = lock(taskId, "TASK_REASSIGN", "reassignments", command, key, true);
        if (locked.reservation().replay()) return replay(locked);
        versions(locked, command.expectedTicketVersion(), command.expectedTaskVersion());
        activeTicket(locked);
        require(Set.of("ASSIGNED", "ACCEPTED", "PROCESSING").contains(locked.task().status())
                && locked.task().currentAssigneeId() != null, "仅已派发的活动任务可转派");
        if (!Objects.equals(locked.task().currentAssigneeId(), command.expectedAssigneeId()))
            throw BusinessException.conflict("ASSIGNEE_CONFLICT", "当前负责人已变化，请刷新");
        if (Objects.equals(command.expectedAssigneeId(), command.assigneeId()))
            throw BusinessException.invalid("SAME_ASSIGNEE", "新旧负责人不能相同");
        eligible(locked, command.assigneeId());
        return assignTo(locked, command.assigneeId(), command.reason(), locked.ticket().status(), "TASK_REASSIGNED", key);
    }

    public Changed accept(long taskId, TaskCommands.Progress command, String key) {
        var locked = lock(taskId, "TASK_ACCEPT", "accept", command, key, false);
        if (locked.reservation().replay()) return replay(locked);
        versions(locked, command.expectedTicketVersion(), command.expectedTaskVersion());
        activeTicket(locked);
        require(locked.task().status().equals("ASSIGNED"), "仅待接单任务可接单");
        return transition(locked, "ACCEPTED", locked.ticket().status(), "TASK_ACCEPTED", "工程师接单", key);
    }

    public Changed start(long taskId, TaskCommands.Progress command, String key) {
        var locked = lock(taskId, "TASK_START", "start", command, key, false);
        if (locked.reservation().replay()) return replay(locked);
        versions(locked, command.expectedTicketVersion(), command.expectedTaskVersion());
        activeTicket(locked);
        require(locked.task().status().equals("ACCEPTED"), "请先接单，再开始处理");
        return transition(locked, "PROCESSING", "IN_PROGRESS", "TASK_STARTED", "工程师开始处理", key);
    }

    public Changed complete(long taskId, TaskCommands.Complete command, String key) {
        var locked = lock(taskId, "TASK_COMPLETE", "complete", command, key, false);
        if (locked.reservation().replay()) return replay(locked);
        versions(locked, command.expectedTicketVersion(), command.expectedTaskVersion());
        require(locked.ticket().status().equals("IN_PROGRESS") && locked.task().status().equals("PROCESSING"),
                "仅处理中的任务可完成");
        return transition(locked, "COMPLETED", "IN_PROGRESS", "TASK_COMPLETED", command.result(), key);
    }

    public Changed cancel(long taskId, TaskCommands.Cancel command, String key) {
        var locked = lock(taskId, "TASK_CANCEL", "cancel", command, key, true);
        if (locked.reservation().replay()) return replay(locked);
        versions(locked, command.expectedTicketVersion(), command.expectedTaskVersion());
        require(locked.ticket().status().equals("TRIAGED") && locked.task().status().equals("CREATED")
                && locked.task().currentAssigneeId() == null, "P0 仅允许取消尚未派发的任务");
        return transition(locked, "CANCELLED", "TRIAGED", "TASK_CANCELLED", command.reason(), key);
    }

    private Locked lock(long taskId, String action, String suffix, Object command, String key, boolean management) {
        var ticket = locateAndLock(taskId);
        var actor = identities.currentActor();
        if (management) manager(actor, ticket); else actor.requireRole("ENGINEER");
        var now = now();
        // 所有写入口一致采用工单 → 幂等记录 → 任务的锁顺序。
        var reservation = idempotency.reserve(actor.id(), action, "/tasks/" + taskId + "/" + suffix, key, command, now);
        var task = tasks.lock(taskId);
        if (task == null || task.ticketId() != ticket.id()) throw BusinessException.forbidden();
        // 重放也检查当前授权，已转出的工程师不能借旧请求键继续访问任务。
        if (!management) owner(actor, task);
        return new Locked(ticket, task, actor, reservation, now);
    }

    private TicketMapper.Ticket locateAndLock(long taskId) {
        Long ticketId = tasks.ticketId(taskId);
        if (ticketId == null) throw BusinessException.forbidden();
        var ticket = tickets.lock(ticketId);
        if (ticket == null) throw BusinessException.forbidden();
        return ticket;
    }

    private Changed assignTo(Locked locked, long assigneeId, String reason, String ticketStatus, String action, String key) {
        changed(tasks.assign(locked.task().id(), locked.task().version(), assigneeId));
        tasks.assignment(IdWorker.getId(), locked.task().id(), locked.task().currentAssigneeId(),
                assigneeId, locked.actor().id(), reason, locked.now());
        return finish(locked, "ASSIGNED", ticketStatus, assigneeId, action, reason, key);
    }

    private Changed transition(Locked locked, String taskStatus, String ticketStatus, String action, String body, String key) {
        changed(tasks.transition(locked.task().id(), locked.task().version(), taskStatus,
                taskStatus.equals("COMPLETED") ? locked.now() : null));
        return finish(locked, taskStatus, ticketStatus, locked.task().currentAssigneeId(), action, body, key);
    }

    private Changed finish(Locked locked, String taskStatus, String ticketStatus, Long assigneeId, String action, String body, String key) {
        var ticket = locked.ticket(); var task = locked.task();
        changed(tickets.taskState(ticket.id(), ticket.version(), ticketStatus, locked.now()));
        tasks.activity(IdWorker.getId(), ticket.id(), task.id(), locked.actor().id(), action, body, locked.now());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fromTaskStatus", task.status()); details.put("toTaskStatus", taskStatus);
        details.put("previousAssigneeId", task.currentAssigneeId()); details.put("currentAssigneeId", assigneeId);
        details.put("reasonOrResult", body);
        audit(ticket, task.id(), locked.actor(), action, key, details, locked.now());
        var response = new Changed(ticket.id(), task.id(), ticketStatus, taskStatus,
                ticket.version()+1, task.version()+1, assigneeId);
        idempotency.finish(locked.reservation(), 200, response, locked.now());
        return response;
    }

    private void manager(IdentityService.Actor actor, TicketMapper.Ticket ticket) {
        actor.requireRole("SERVICE_MANAGER");
        if (ticket == null || !identities.manages(actor, ticket.ownerTeamId())) throw BusinessException.forbidden();
    }
    private void owner(IdentityService.Actor actor, TaskMapper.Task task) {
        if (!Objects.equals(task.currentAssigneeId(), actor.id())) throw BusinessException.forbidden();
    }
    private void eligible(Locked locked, long assigneeId) {
        if (tasks.eligibleEngineer(assigneeId, locked.ticket().ownerTeamId()) == null)
            throw BusinessException.invalid("ASSIGNEE_NOT_ELIGIBLE", "工程师须为有效账号并属于工单负责团队");
    }
    private void activeTicket(Locked locked) {
        require(Set.of("ASSIGNED", "IN_PROGRESS").contains(locked.ticket().status()), "当前工单状态不允许处理任务");
    }
    private void ticketVersion(TicketMapper.Ticket ticket, int expected) {
        if (ticket.version() != expected) throw BusinessException.conflict("VERSION_CONFLICT", "工单已变化，请刷新");
    }
    private void versions(Locked locked, int ticketVersion, int taskVersion) {
        ticketVersion(locked.ticket(), ticketVersion);
        if (locked.task().version() != taskVersion) throw BusinessException.conflict("TASK_VERSION_CONFLICT", "任务已变化，请刷新");
    }
    private void require(boolean allowed, String message) {
        if (!allowed) throw BusinessException.invalid("INVALID_STATE_TRANSITION", message);
    }
    private void changed(int count) {
        if (count != 1) throw BusinessException.conflict("VERSION_CONFLICT", "聚合更新冲突，请刷新");
    }
    private Changed replay(Locked locked) { return idempotency.replay(locked.reservation(), Changed.class); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    private void audit(TicketMapper.Ticket ticket, long taskId, IdentityService.Actor actor, String action,
                       String key, Map<String, Object> details, Instant now) {
        var data = new LinkedHashMap<>(details); data.put("taskId", taskId);
        audit.insert(IdWorker.getId(), actor.id(), ticket.customerId(), ticket.id(), action, key, idempotency.encode(data), now);
    }
}
