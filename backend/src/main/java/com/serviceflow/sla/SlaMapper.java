package com.serviceflow.sla;

import org.apache.ibatis.annotations.*;
import java.time.Instant;

@Mapper
public interface SlaMapper {
    record Snapshot(long policyVersionId, Instant effectiveStartAt, Instant responseDueAt,
                    Instant resolutionDueAt, Instant firstRespondedAt, Integer responseMet, String resolutionClockState) {}

    @Insert("""
            INSERT INTO ticket_sla_instance(id,ticket_id,policy_version_id,effective_start_at,response_due_at,
             resolution_due_at,resolution_clock_state,resolution_elapsed_seconds,resolution_last_started_at,version)
            VALUES(#{id},#{ticketId},#{policyId},#{now},#{responseDue},#{resolutionDue},'RUNNING',0,#{now},0)
            """)
    int create(long id, long ticketId, long policyId, Instant now, Instant responseDue, Instant resolutionDue);

    @Select("SELECT policy_version_id,effective_start_at,response_due_at,resolution_due_at,first_responded_at,response_met,resolution_clock_state FROM ticket_sla_instance WHERE ticket_id=#{ticketId}")
    Snapshot snapshot(long ticketId);

    @Update("""
            UPDATE ticket_sla_instance SET first_responded_at=#{now},response_met=#{met},version=version+1
            WHERE ticket_id=#{ticketId} AND first_responded_at IS NULL
            """)
    int recordResponse(long ticketId, Instant now, boolean met);

    @Insert("""
            INSERT INTO ticket_sla_event(id,sla_instance_id,sequence_no,event_type,event_at,command_key,actor_user_id,reason)
            SELECT #{id},s.id,COALESCE((SELECT MAX(e.sequence_no) FROM ticket_sla_event e WHERE e.sla_instance_id=s.id),0)+1,
            #{type},#{now},#{commandKey},#{actorId},#{reason} FROM ticket_sla_instance s WHERE s.ticket_id=#{ticketId}
            """)
    int event(long id, long ticketId, String type, Instant now, String commandKey, long actorId, String reason);
}
