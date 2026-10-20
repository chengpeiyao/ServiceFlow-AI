package com.serviceflow.sla;

import org.apache.ibatis.annotations.*;
import java.time.Instant;

@Mapper
public interface SlaMapper {
    record Snapshot(long policyVersionId, Instant effectiveStartAt, Instant responseDueAt,
                    Instant resolutionDueAt, Instant firstRespondedAt, Integer responseMet, String resolutionClockState,
                    Integer resolutionMet, long resolutionElapsedMillis, Instant resolutionLastStartedAt) {}
    record ClockState(String resolutionClockState, long resolutionElapsedMillis, Instant resolutionLastStartedAt,
                      int resolutionTargetMinutes, int version) {}

    @Insert("""
            INSERT INTO ticket_sla_instance(id,ticket_id,policy_version_id,effective_start_at,response_due_at,
             resolution_due_at,resolution_clock_state,resolution_elapsed_seconds,resolution_last_started_at,version)
            VALUES(#{id},#{ticketId},#{policyId},#{now},#{responseDue},#{resolutionDue},'RUNNING',0,#{now},0)
            """)
    int create(long id, long ticketId, long policyId, Instant now, Instant responseDue, Instant resolutionDue);

    @Select("SELECT policy_version_id,effective_start_at,response_due_at,resolution_due_at,first_responded_at,response_met,resolution_clock_state,resolution_met,resolution_elapsed_millis,resolution_last_started_at FROM ticket_sla_instance WHERE ticket_id=#{ticketId}")
    Snapshot snapshot(long ticketId);

    @Select("""
            SELECT s.resolution_clock_state,s.resolution_elapsed_millis,s.resolution_last_started_at,
            p.resolution_target_minutes,s.version FROM ticket_sla_instance s
            JOIN sla_policy_version p ON p.id=s.policy_version_id WHERE s.ticket_id=#{ticketId} FOR UPDATE
            """)
    ClockState lockClock(long ticketId);

    @Update("""
            UPDATE ticket_sla_instance SET resolution_clock_state=#{state},resolution_elapsed_millis=#{elapsed},
            resolution_elapsed_seconds=#{seconds},resolution_last_started_at=#{started},resolution_due_at=#{due},
            resolution_met=#{met},response_met=CASE WHEN #{state}='CLOSED' AND first_responded_at IS NULL
                THEN 0 ELSE response_met END,version=version+1 WHERE ticket_id=#{ticketId} AND version=#{version}
            """)
    int changeClock(long ticketId, int version, String state, long elapsed, long seconds,
                    Instant started, Instant due, Integer met);

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
