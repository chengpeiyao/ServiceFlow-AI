package com.serviceflow.audit;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import java.time.Instant;

@Mapper
public interface AuditMapper {
    @Insert("""
            INSERT INTO business_audit_log(id,actor_user_id,actor_kind,customer_id,ticket_id,action_code,
            resource_type,resource_id,request_id,detail_json,occurred_at)
            VALUES(#{id},#{actorId},'HUMAN',#{customerId},#{ticketId},#{action},'SERVICE_TICKET',#{ticketId},#{requestId},#{details},#{now})
            """)
    int insert(long id, long actorId, long customerId, long ticketId, String action, String requestId, String details, Instant now);
}
