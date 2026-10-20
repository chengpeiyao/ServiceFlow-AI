package com.serviceflow.ticket;

import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

@Mapper
public interface TicketMapper {
    record Ticket(long id, long customerId, long contractId, long creatorUserId, long ownerTeamId,
                  int routingVersion, String title, String originalDescription,
                  String reportedProductCode, String reportedProductVersion, String serviceType, String category,
                  String impactLevel, String urgencyLevel, String priority, String status, int version, Instant createdAt) {}
    record Activity(long id, long actorUserId, String activityType, String body, boolean customerVisible, Instant createdAt) {}
    record Knowledge(long id, String productCode, String applicableVersions, String status) {}

    String TICKET_COLUMNS = "id,customer_id,contract_id,creator_user_id,owner_team_id,routing_version,title,original_description,reported_product_code,reported_product_version,service_type,category,impact_level,urgency_level,priority,status,version,created_at";

    @Select("SELECT " + TICKET_COLUMNS + " FROM service_ticket WHERE id=#{id}")
    Ticket byId(long id);

    @Select("SELECT " + TICKET_COLUMNS + " FROM service_ticket WHERE id=#{id} FOR UPDATE")
    Ticket lock(long id);

    @Insert("""
            INSERT INTO service_ticket
            (id,customer_id,contract_id,creator_user_id,owner_team_id,routing_version,title,original_description,
             reported_product_code,reported_product_version,service_type,impact_level,urgency_level,priority,status,version,created_at,updated_at)
            VALUES (#{id},#{customerId},#{contractId},#{creatorUserId},#{ownerTeamId},#{routingVersion},#{title},#{originalDescription},
             #{reportedProductCode},#{reportedProductVersion},#{serviceType},#{impactLevel},#{urgencyLevel},#{priority},'SUBMITTED',0,#{createdAt},#{createdAt})
            """)
    int insert(Ticket ticket);

    @Update("""
            UPDATE service_ticket SET reported_product_code=#{product},reported_product_version=#{productVersion},
            version=version+1,updated_at=#{now} WHERE id=#{id} AND version=#{expectedVersion}
            """)
    int appendVersion(long id, int expectedVersion, String product, String productVersion, Instant now);

    @Update("""
            UPDATE service_ticket SET category=#{category},status='TRIAGED',version=version+1,updated_at=#{now}
            WHERE id=#{id} AND version=#{expectedVersion} AND status='SUBMITTED'
            """)
    int triage(long id, int expectedVersion, String category, Instant now);

    @Insert("""
            INSERT INTO ticket_activity(id,ticket_id,actor_user_id,activity_type,body,customer_visible,created_at)
            VALUES(#{id},#{ticketId},#{actorId},#{type},#{body},#{visible},#{now})
            """)
    int addActivity(long id, long ticketId, long actorId, String type, String body, boolean visible, Instant now);

    @Select("""
            SELECT id,actor_user_id,activity_type,body,customer_visible,created_at FROM ticket_activity
            WHERE ticket_id=#{ticketId} AND (#{includeInternal}=true OR customer_visible=1) ORDER BY created_at,id
            """)
    List<Activity> activities(long ticketId, boolean includeInternal);

    @Select("SELECT body FROM ticket_activity WHERE id=#{messageId} AND ticket_id=#{ticketId} AND activity_type='CUSTOMER_SUPPLEMENT'")
    String customerSource(long ticketId, long messageId);

    @Select("SELECT COUNT(*) FROM service_task WHERE ticket_id=#{ticketId} AND current_assignee_id=#{actorId} AND status IN ('ASSIGNED','ACCEPTED','PROCESSING')")
    int currentEngineer(long ticketId, long actorId);

    @Insert("""
            INSERT INTO ticket_triage_record(id,ticket_id,proposal_id,confirmed_category,confirmed_facts_json,
            confirmed_followups_json,confirmed_diagnostics_json,reviewed_by,applied_at)
            VALUES(#{id},#{ticketId},NULL,#{category},#{facts},#{followups},#{diagnostics},#{actorId},#{now})
            """)
    int insertTriage(long id, long ticketId, String category, String facts, String followups, String diagnostics, long actorId, Instant now);

    @Update("""
            UPDATE service_ticket SET status=#{status},version=version+1,updated_at=#{now}
            WHERE id=#{id} AND version=#{expectedVersion}
            """)
    int taskState(long id, int expectedVersion, String status, Instant now);

    @Select("SELECT id,product_code,applicable_versions,status FROM knowledge_article_version WHERE id=#{id}")
    Knowledge knowledge(long id);
}
