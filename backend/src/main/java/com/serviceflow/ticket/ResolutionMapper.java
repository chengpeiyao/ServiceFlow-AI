package com.serviceflow.ticket;

import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

/** 方案与验收记录的数据访问；调用方负责工单授权，写操作必须处于持有工单锁的事务内。 */
@Mapper
public interface ResolutionMapper {
    /** 已提交方案快照，cycleNo 从 1 开始；方案正文提交后不在此接口中修改。 */
    record Resolution(long id, long ticketId, int cycleNo, String summary, long submittedBy, Instant submittedAt) {}
    /** 历史查询投影；未验收时验收字段为空，未产生整改时 remediationTaskId 为空。 */
    record History(long id, int cycleNo, String summary, long submittedBy, Instant submittedAt,
                   Long acceptanceId, String decision, Long decidedBy, String reason, Instant decidedAt,
                   Long remediationTaskId) {}

    /** 按方案 ID 定位所属工单；普通读取不加行锁，不存在返回 null，也不代表通过授权。 */
    @Select("SELECT id,ticket_id,cycle_no,summary,submitted_by,submitted_at FROM ticket_resolution WHERE id=#{id}")
    Resolution byId(long id);

    /**
     * 保存本轮方案，调用方需同时推进工单状态和 SLA。
     * @param id 新方案 ID
     * @param ticketId 所属工单 ID
     * @param cycle 本次提交的方案轮次，须与已完成任务轮次一致
     * @param summary 对客户公开的方案正文
     * @param actorId 当前提交方案的工程师 ID
     * @param now 本次操作统一使用的业务时间
     */
    @Insert("""
            INSERT INTO ticket_resolution(id,ticket_id,cycle_no,summary,submitted_by,submitted_at)
            VALUES(#{id},#{ticketId},#{cycle},#{summary},#{actorId},#{now})
            """)
    int insert(long id, long ticketId, int cycle, String summary, long actorId, Instant now);

    /**
     * 新增验收事实，不覆盖旧决定；重复验收另有数据库唯一约束保护。
     * @param id 新验收记录 ID，不是方案 ID
     * @param ticketId 所属工单 ID
     * @param resolutionId 被验收的方案 ID
     * @param cycle 被验收方案的轮次
     * @param decision ACCEPT 或 REJECT
     * @param actorId 已经 Service 核实身份的工单原创建者 ID
     * @param reason 拒绝原因；通过时可以为空
     * @param now 本次操作统一使用的业务时间
     */
    @Insert("""
            INSERT INTO ticket_acceptance(id,ticket_id,resolution_id,cycle_no,decision,decided_by,reason,decided_at)
            VALUES(#{id},#{ticketId},#{resolutionId},#{cycle},#{decision},#{actorId},#{reason},#{now})
            """)
    int decide(long id, long ticketId, long resolutionId, int cycle, String decision, long actorId, String reason, Instant now);

    /** 按工单查询完整方案历史；左连接保留尚未验收或没有整改任务的方案。 */
    @Select("""
            SELECT r.id,r.cycle_no,r.summary,r.submitted_by,r.submitted_at,a.id AS acceptance_id,
            a.decision,a.decided_by,a.reason,a.decided_at,t.id AS remediation_task_id
            FROM ticket_resolution r LEFT JOIN ticket_acceptance a ON a.resolution_id=r.id
            LEFT JOIN service_task t ON t.source_resolution_id=r.id
            WHERE r.ticket_id=#{ticketId} ORDER BY r.cycle_no
            """)
    List<History> history(long ticketId);
}
