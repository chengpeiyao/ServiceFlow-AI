package com.serviceflow.task;

import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

@Mapper
public interface TaskMapper {
    record Task(long id, long ticketId, String title, int isBlocking, String status,
                Long currentAssigneeId, int version, long createdBy, Instant createdAt, Instant completedAt) {}
    record Assignment(long id, Long previousAssigneeId, long newAssigneeId, long assignedBy,
                      String reason, Instant assignedAt) {}
    record Activity(long id, long actorUserId, String activityType, String body, Instant createdAt) {}
    String COLUMNS = "id,ticket_id,title,is_blocking,status,current_assignee_id,version,created_by,created_at,completed_at";

    @Select("SELECT ticket_id FROM service_task WHERE id=#{id}")
    Long ticketId(long id);

    @Select("SELECT " + COLUMNS + " FROM service_task WHERE id=#{id}")
    Task byId(long id);

    @Select("SELECT " + COLUMNS + " FROM service_task WHERE id=#{id} FOR UPDATE")
    Task lock(long id);

    @Select("SELECT COUNT(*) FROM service_task WHERE ticket_id=#{ticketId} AND status IN ('CREATED','ASSIGNED','ACCEPTED','PROCESSING')")
    int activeCount(long ticketId);

    @Insert("""
            INSERT INTO service_task(id,ticket_id,title,is_blocking,status,current_assignee_id,version,created_by,created_at)
            VALUES(#{id},#{ticketId},#{title},1,'CREATED',NULL,0,#{actorId},#{now})
            """)
    int insert(long id, long ticketId, String title, long actorId, Instant now);

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
