package com.serviceflow.ticket;

import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

@Mapper
public interface ResolutionMapper {
    record Resolution(long id, long ticketId, int cycleNo, String summary, long submittedBy, Instant submittedAt) {}
    record History(long id, int cycleNo, String summary, long submittedBy, Instant submittedAt,
                   Long acceptanceId, String decision, Long decidedBy, String reason, Instant decidedAt,
                   Long remediationTaskId) {}

    @Select("SELECT id,ticket_id,cycle_no,summary,submitted_by,submitted_at FROM ticket_resolution WHERE id=#{id}")
    Resolution byId(long id);

    @Insert("""
            INSERT INTO ticket_resolution(id,ticket_id,cycle_no,summary,submitted_by,submitted_at)
            VALUES(#{id},#{ticketId},#{cycle},#{summary},#{actorId},#{now})
            """)
    int insert(long id, long ticketId, int cycle, String summary, long actorId, Instant now);

    @Insert("""
            INSERT INTO ticket_acceptance(id,ticket_id,resolution_id,cycle_no,decision,decided_by,reason,decided_at)
            VALUES(#{id},#{ticketId},#{resolutionId},#{cycle},#{decision},#{actorId},#{reason},#{now})
            """)
    int decide(long id, long ticketId, long resolutionId, int cycle, String decision, long actorId, String reason, Instant now);

    @Select("""
            SELECT r.id,r.cycle_no,r.summary,r.submitted_by,r.submitted_at,a.id AS acceptance_id,
            a.decision,a.decided_by,a.reason,a.decided_at,t.id AS remediation_task_id
            FROM ticket_resolution r LEFT JOIN ticket_acceptance a ON a.resolution_id=r.id
            LEFT JOIN service_task t ON t.source_resolution_id=r.id
            WHERE r.ticket_id=#{ticketId} ORDER BY r.cycle_no
            """)
    List<History> history(long ticketId);
}
