package com.serviceflow.sla;

import org.apache.ibatis.annotations.*;
import java.time.Instant;

/** SLA 实例投影与事件流水的数据访问；状态写入依赖调用方已有事务和工单锁。 */
@Mapper
public interface SlaMapper {
    /**
     * SLA 查询快照；达标字段为 1/0/null，分别表示达标、未达标、尚无判定。
     * resolutionElapsedMillis 仅含已结算区间，运行中的当前区间还需结合 resolutionLastStartedAt 计算。
     * 非运行状态的 resolutionDueAt、resolutionLastStartedAt 为空。
     */
    record Snapshot(long policyVersionId, Instant effectiveStartAt, Instant responseDueAt,
                    Instant resolutionDueAt, Instant firstRespondedAt, Integer responseMet, String resolutionClockState,
                    Integer resolutionMet, long resolutionElapsedMillis, Instant resolutionLastStartedAt) {}
    /** 写入所需时钟快照；目标来自创建时冻结的策略版本，version 是 SLA 版本而非工单版本。 */
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

    /** 读取并锁定计时状态及关联策略；调用前须已锁定工单，避免与其他聚合写入锁顺序相反。 */
    @Select("""
            SELECT s.resolution_clock_state,s.resolution_elapsed_millis,s.resolution_last_started_at,
            p.resolution_target_minutes,s.version FROM ticket_sla_instance s
            JOIN sla_policy_version p ON p.id=s.policy_version_id WHERE s.ticket_id=#{ticketId} FOR UPDATE
            """)
    ClockState lockClock(long ticketId);

    /**
     * 原子更新时钟状态与累计耗时；仅 CLOSED 且从未首响时，把首响判为未达标。
     * @param ticketId 工单 ID
     * @param version 更新前的 SLA 实例版本
     * @param state 目标时钟状态
     * @param elapsed 累计处理毫秒，作为精确计时依据
     * @param seconds 由累计毫秒除以 1000 得到的兼容秒值
     * @param started 新运行区间起点；暂停或结束时为 null
     * @param due 当前解决到期时间；暂停或结束时为 null
     * @param met 解决达标结果：1 达标、0 未达标、null 尚无判定
     * @return 成功为 1；0 表示记录不存在或版本已变化，调用方必须中止事务
     */
    @Update("""
            UPDATE ticket_sla_instance SET resolution_clock_state=#{state},resolution_elapsed_millis=#{elapsed},
            resolution_elapsed_seconds=#{seconds},resolution_last_started_at=#{started},resolution_due_at=#{due},
            resolution_met=#{met},response_met=CASE WHEN #{state}='CLOSED' AND first_responded_at IS NULL
                THEN 0 ELSE response_met END,version=version+1 WHERE ticket_id=#{ticketId} AND version=#{version}
            """)
    int changeClock(long ticketId, int version, String state, long elapsed, long seconds,
                    Instant started, Instant due, Integer met);

    /**
     * 首次人工公开答复才记录首响，已有首响不会被后续答复覆盖。
     * @param ticketId 工单 ID
     * @param now 本次有效公开答复的时间
     * @param met 是否在首响到期时间内，由 Service 比较后传入
     * @return 首次写入为 1；已有首响或记录不存在为 0
     */
    @Update("""
            UPDATE ticket_sla_instance SET first_responded_at=#{now},response_met=#{met},version=version+1
            WHERE ticket_id=#{ticketId} AND first_responded_at IS NULL
            """)
    int recordResponse(long ticketId, Instant now, boolean met);

    /**
     * 追加计时事件；MAX(sequence_no)+1 依赖同一工单锁的串行化，不能脱离业务事务独立调用。
     * @param id 新事件 ID
     * @param ticketId 工单 ID，用于定位对应 SLA 实例
     * @param type 事件类型
     * @param now 事件发生的业务时间
     * @param commandKey 关联业务事实的命令键，如 RESOLUTION:方案ID，不是 HTTP 请求幂等键
     * @param actorId 触发事件的用户 ID
     * @param reason 触发原因或业务说明
     */
    @Insert("""
            INSERT INTO ticket_sla_event(id,sla_instance_id,sequence_no,event_type,event_at,command_key,actor_user_id,reason)
            SELECT #{id},s.id,COALESCE((SELECT MAX(e.sequence_no) FROM ticket_sla_event e WHERE e.sla_instance_id=s.id),0)+1,
            #{type},#{now},#{commandKey},#{actorId},#{reason} FROM ticket_sla_instance s WHERE s.ticket_id=#{ticketId}
            """)
    int event(long id, long ticketId, String type, Instant now, String commandKey, long actorId, String reason);
}
