package com.serviceflow.task;

import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

/** 任务、派工历史和内部活动的数据访问；授权、状态机及工单优先的锁顺序由 Service 保证。 */
@Mapper
public interface TaskMapper {
    /**
     * 任务快照；version 是任务自身版本，currentAssigneeId 在未派工时为空。
     * resolutionCycleNo 为任务对应的方案轮次；首轮为 1，整改任务的 sourceResolutionId 指向被拒绝方案。
     */
    record Task(long id, long ticketId, String title, int isBlocking, String status,
                Long currentAssigneeId, int version, long createdBy, Instant createdAt, Instant completedAt,
                int resolutionCycleNo, Long sourceResolutionId) {}
    record Assignment(long id, Long previousAssigneeId, long newAssigneeId, long assignedBy,
                      String reason, Instant assignedAt) {}
    record Activity(long id, long actorUserId, String activityType, String body, Instant createdAt) {}
    String COLUMNS = "id,ticket_id,title,is_blocking,status,current_assignee_id,version,created_by,created_at,completed_at,resolution_cycle_no,source_resolution_id";

    /** 无锁定位父工单；不能用该查询代替拿到工单锁后的授权和任务状态检查。 */
    @Select("SELECT ticket_id FROM service_task WHERE id=#{id}")
    Long ticketId(long id);

    @Select("SELECT " + COLUMNS + " FROM service_task WHERE id=#{id}")
    Task byId(long id);

    /** 锁定任务最新记录；写操作必须先取得工单锁，再预留幂等记录，最后调用此方法。 */
    @Select("SELECT " + COLUMNS + " FROM service_task WHERE id=#{id} FOR UPDATE")
    Task lock(long id);

    /** 统计尚未完成或取消的任务；工单取消、建任务及方案提交均依赖这一检查。 */
    @Select("SELECT COUNT(*) FROM service_task WHERE ticket_id=#{ticketId} AND status IN ('CREATED','ASSIGNED','ACCEPTED','PROCESSING')")
    int activeCount(long ticketId);

    @Insert("""
            INSERT INTO service_task(id,ticket_id,title,is_blocking,status,current_assignee_id,version,created_by,created_at)
            VALUES(#{id},#{ticketId},#{title},1,'CREATED',NULL,0,#{actorId},#{now})
            """)
    int insert(long id, long ticketId, String title, long actorId, Instant now);

    /**
     * 在拒绝验收的同一事务中创建未派工的阻塞整改任务，后续由主管选择工程师。
     * @param id 新任务 ID
     * @param ticketId 所属工单 ID
     * @param cycle 下一轮方案编号，即被拒绝方案轮次加 1
     * @param resolutionId 被拒绝的来源方案 ID，受来源唯一约束及同工单外键约束保护
     * @param actorId 触发拒绝验收的客户用户 ID，不是后续派工的主管 ID
     * @param now 本次拒绝验收的业务时间
     */
    @Insert("""
            INSERT INTO service_task(id,ticket_id,title,is_blocking,status,version,created_by,created_at,
            resolution_cycle_no,source_resolution_id)
            VALUES(#{id},#{ticketId},'客户拒绝验收后的整改任务',1,'CREATED',0,#{actorId},#{now},#{cycle},#{resolutionId})
            """)
    int insertRework(long id, long ticketId, int cycle, long resolutionId, long actorId, Instant now);

    /**
     * 检查整改来源是否确实是同一工单、指定轮次的被拒绝方案。
     * @param ticketId 工单 ID
     * @param resolutionId 整改任务引用的来源方案 ID
     * @param cycle 来源方案的轮次，不是整改任务的下一轮编号
     * @return 匹配的拒绝记录数，合法来源应为 1
     */
    @Select("""
            SELECT COUNT(*) FROM ticket_acceptance WHERE ticket_id=#{ticketId}
            AND resolution_id=#{resolutionId} AND cycle_no=#{cycle} AND decision='REJECT'
            """)
    int rejectedSource(long ticketId, long resolutionId, int cycle);

    @Update("""
            UPDATE service_task SET current_assignee_id=#{assigneeId},status='ASSIGNED',version=version+1
            WHERE id=#{id} AND version=#{expectedVersion}
            """)
    int assign(long id, int expectedVersion, long assigneeId);

    @Update("""
            UPDATE service_task SET status=#{status},completed_at=#{completedAt},version=version+1
            WHERE id=#{id} AND version=#{expectedVersion}
            """)
    int transition(long id, int expectedVersion, String status, Instant completedAt);

    // 在事务内共享锁定候选账号和成员关系，资格检查到提交期间不允许并发撤销。
    @Select("""
            SELECT u.id FROM app_user u JOIN team_member m ON m.user_id=u.id
            WHERE u.id=#{userId} AND u.role_code='ENGINEER' AND u.account_status='ACTIVE'
            AND m.team_id=#{teamId} FOR SHARE
            """)
    Long eligibleEngineer(long userId, long teamId);

    @Insert("""
            INSERT INTO task_assignment_history(id,task_id,previous_assignee_id,new_assignee_id,assigned_by,reason,assigned_at)
            VALUES(#{id},#{taskId},#{previous},#{assigneeId},#{actorId},#{reason},#{now})
            """)
    int assignment(long id, long taskId, Long previous, long assigneeId, long actorId, String reason, Instant now);

    @Insert("""
            INSERT INTO ticket_activity(id,ticket_id,task_id,actor_user_id,activity_type,body,customer_visible,created_at)
            VALUES(#{id},#{ticketId},#{taskId},#{actorId},#{type},#{body},0,#{now})
            """)
    int activity(long id, long ticketId, long taskId, long actorId, String type, String body, Instant now);

    @Select("""
            SELECT id,previous_assignee_id,new_assignee_id,assigned_by,reason,assigned_at
            FROM task_assignment_history WHERE task_id=#{taskId} ORDER BY assigned_at,id
            """)
    List<Assignment> assignments(long taskId);

    @Select("""
            SELECT id,actor_user_id,activity_type,body,created_at FROM ticket_activity
            WHERE task_id=#{taskId} ORDER BY created_at,id
            """)
    List<Activity> activities(long taskId);
}
