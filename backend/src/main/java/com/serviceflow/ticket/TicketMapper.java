package com.serviceflow.ticket;

import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

@Mapper
public interface TicketMapper {
    record Ticket(long id, long customerId, long contractId, long creatorUserId, long ownerTeamId,
                  int routingVersion, String title, String originalDescription,
                  String reportedProductCode, String reportedProductVersion, String serviceType, String category,
                  String impactLevel, String urgencyLevel, String priority, String status, int version, Instant createdAt,
                  int resolutionCycleNo, Instant closedAt) {}
    record Activity(long id, long actorUserId, String activityType, String body, boolean customerVisible, Instant createdAt) {}
    record Knowledge(long id, String productCode, String applicableVersions, String status) {}
    record ListItem(long id, long customerId, long contractId, long creatorUserId, long ownerTeamId,
                    String title, String serviceType, String priority, String status, int version,
                    Instant createdAt, Instant updatedAt) {}

    String TICKET_COLUMNS = "id,customer_id,contract_id,creator_user_id,owner_team_id,routing_version,title,original_description,reported_product_code,reported_product_version,service_type,category,impact_level,urgency_level,priority,status,version,created_at,resolution_cycle_no,closed_at";

    // 列表、详情和方案授权共用工程师轮次条件，防止完成或整改后出现可见范围分叉。
    String ENGINEER_SCOPE = """
            EXISTS (SELECT 1 FROM service_task s WHERE s.ticket_id=t.id AND s.current_assignee_id=#{actorId}
            AND s.status IN ('ASSIGNED','ACCEPTED','PROCESSING','COMPLETED')
            AND s.resolution_cycle_no=CASE WHEN t.status IN ('PENDING_ACCEPTANCE','CLOSED')
                THEN t.resolution_cycle_no ELSE t.resolution_cycle_no+1 END)
            """;
    String LIST_WHERE = """
            WHERE
            <choose>
                <when test="role == 'CUSTOMER_CONTACT'">
                    EXISTS (SELECT 1 FROM customer_user c WHERE c.customer_id=t.customer_id AND c.user_id=#{actorId})
                </when>
                <when test="role == 'SERVICE_MANAGER'">
                    EXISTS (SELECT 1 FROM team_manager_scope m WHERE m.team_id=t.owner_team_id AND m.manager_user_id=#{actorId})
                </when>
                <when test="role == 'ENGINEER'">
            """ + ENGINEER_SCOPE + """
                </when>
                <when test="role == 'SUPPORT_AGENT' or role == 'OPS_ADMIN'">1=1</when>
                <otherwise>1=0</otherwise>
            </choose>
            <if test="query.status != null">AND t.status=#{query.status}</if>
            <if test="query.priority != null">AND t.priority=#{query.priority}</if>
            <if test="query.customerId != null">AND t.customer_id=#{query.customerId}</if>
            <if test="query.ownerTeamId != null">AND t.owner_team_id=#{query.ownerTeamId}</if>
            <if test="query.keyword != null and query.keyword != ''">AND LOCATE(#{query.keyword},t.title)>0</if>
            """;

    @Select("<script>SELECT COUNT(*) FROM service_ticket t " + LIST_WHERE + "</script>")
    long countVisible(long actorId, String role, TicketCommands.Query query);

    @Select("""
            <script>SELECT t.id,t.customer_id,t.contract_id,t.creator_user_id,t.owner_team_id,
            t.title,t.service_type,t.priority,t.status,t.version,t.created_at,t.updated_at
            FROM service_ticket t
            """ + LIST_WHERE + " ORDER BY t.created_at DESC,t.id DESC LIMIT #{query.size} OFFSET #{offset}</script>")
    List<ListItem> listVisible(long actorId, String role, TicketCommands.Query query, long offset);

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

    @Select("SELECT COUNT(*) FROM service_ticket t WHERE t.id=#{ticketId} AND " + ENGINEER_SCOPE)
    int currentEngineer(long ticketId, long actorId);

    @Update("""
            UPDATE service_ticket SET status='CANCELLED',version=version+1,updated_at=#{now}
            WHERE id=#{id} AND version=#{expectedVersion} AND status IN ('SUBMITTED','TRIAGED')
            """)
    int cancel(long id, int expectedVersion, Instant now);

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

    @Update("""
            UPDATE service_ticket SET status='PENDING_ACCEPTANCE',resolution_cycle_no=resolution_cycle_no+1,
            version=version+1,updated_at=#{now} WHERE id=#{id} AND version=#{expectedVersion} AND status='IN_PROGRESS'
            """)
    int propose(long id, int expectedVersion, Instant now);

    @Update("""
            UPDATE service_ticket SET status=#{status},closed_at=#{closedAt},version=version+1,updated_at=#{now}
            WHERE id=#{id} AND version=#{expectedVersion} AND status='PENDING_ACCEPTANCE'
            """)
    int decide(long id, int expectedVersion, String status, Instant closedAt, Instant now);

    @Select("SELECT id,product_code,applicable_versions,status FROM knowledge_article_version WHERE id=#{id}")
    Knowledge knowledge(long id);
}
