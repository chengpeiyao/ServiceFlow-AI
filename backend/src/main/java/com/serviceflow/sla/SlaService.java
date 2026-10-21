package com.serviceflow.sla;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;

/**
 * 维护 SLA 解决时钟的当前状态和事件流水，以毫秒累计实际处理耗时，等待客户验收不计时。
 * MANDATORY 要求加入调用方已有事务；调用方还必须先持有工单锁并完成业务授权。
 * 各入口的 now 由调用方统一传入，使工单、SLA 和审计使用同一业务时间。
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class SlaService {
    private final SlaMapper sla;
    public SlaService(SlaMapper sla) { this.sla = sla; }

    /**
     * 提交方案时结算当前运行区间，进入等待验收的暂停状态。
     * @param ticketId 工单 ID
     * @param resolutionId 新提交的方案 ID，用于关联 SLA 事件命令键
     * @param actorId 提交方案的工程师 ID
     * @param now 本次提交方案的业务时间，作为当前运行区间的终点
     */
    // 调用方已持有工单锁；SLA 投影、事件与业务结果必须同事务提交。
    public void pause(long ticketId, long resolutionId, long actorId, Instant now) {
        var state = state(ticketId, "RUNNING");
        long elapsed = elapsed(state, now);
        change(ticketId, state, "PAUSED_ACCEPTANCE", elapsed, null, null, null);
        event(ticketId, "RESOLUTION_PROPOSED", "RESOLUTION:" + resolutionId, actorId, now, "提交方案，暂停解决时钟");
    }

    /**
     * 拒绝验收时保留累计耗时并重新计时；剩余额度不足时到期时间取 now，不返还超时额度。
     * @param ticketId 工单 ID
     * @param acceptanceId 本次拒绝验收记录 ID，用于关联事件命令键
     * @param actorId 作出拒绝决定的客户用户 ID
     * @param now 新运行区间起点，等待验收的时长不加入累计值
     */
    public void resume(long ticketId, long acceptanceId, long actorId, Instant now) {
        var state = state(ticketId, "PAUSED_ACCEPTANCE");
        long remaining = Math.max(0, target(state) - state.resolutionElapsedMillis());
        change(ticketId, state, "RUNNING", state.resolutionElapsedMillis(), now, now.plusMillis(remaining), null);
        event(ticketId, "ACCEPTANCE_REJECTED", "ACCEPTANCE:" + acceptanceId, actorId, now, "拒绝验收，保留累计耗时并恢复解决时钟");
    }

    /**
     * 验收通过后按暂停时已累计的耗时结算；恰好等于目标耗时仍算达标。
     * @param ticketId 工单 ID
     * @param acceptanceId 本次通过验收记录 ID
     * @param actorId 验收客户用户 ID
     * @param now 关闭事件时间，不把暂停等待时间加入解决耗时
     */
    public void close(long ticketId, long acceptanceId, long actorId, Instant now) {
        var state = state(ticketId, "PAUSED_ACCEPTANCE");
        change(ticketId, state, "CLOSED", state.resolutionElapsedMillis(), null, null,
                state.resolutionElapsedMillis() <= target(state) ? 1 : 0);
        event(ticketId, "CLOSED", "ACCEPTANCE:" + acceptanceId, actorId, now, "验收通过，结算解决时钟和首响结果");
    }

    /**
     * 主动取消时累计最后一段处理时间，停止时钟但不判定解决是否达标。
     * @param ticketId 已通过取消业务校验的工单 ID
     * @param actorId 执行取消的用户 ID
     * @param now 取消时间，作为最后一段运行区间的终点
     * @param reason 与取消请求一致的原因，写入 SLA 事件以便追溯
     */
    public void cancel(long ticketId, long actorId, Instant now, String reason) {
        var state = state(ticketId, "RUNNING");
        // 取消保留首响事实，解决结果保持未知，以 CANCELLED 状态单列，不伪装为达标关闭。
        change(ticketId, state, "CANCELLED", elapsed(state, now), null, null, null);
        event(ticketId, "CANCELLED", "CANCEL:" + ticketId, actorId, now, reason);
    }

    /** 既有累计毫秒加上本次运行区间；拒绝倒退时间，使用 addExact 避免溢出后静默变成负值。 */
    private long elapsed(SlaMapper.ClockState state, Instant now) {
        if (state.resolutionLastStartedAt() == null || now.isBefore(state.resolutionLastStartedAt()))
            throw new IllegalStateException("解决时钟起点无效");
        return Math.addExact(state.resolutionElapsedMillis(), Duration.between(state.resolutionLastStartedAt(), now).toMillis());
    }

    private SlaMapper.ClockState state(long ticketId, String expected) {
        var state = sla.lockClock(ticketId);
        if (state == null || !state.resolutionClockState().equals(expected))
            throw new IllegalStateException("SLA 状态与业务状态不一致");
        return state;
    }
    private long target(SlaMapper.ClockState state) { return state.resolutionTargetMinutes() * 60_000L; }
    /**
     * 用 SLA 自身版本更新时钟投影；秒字段仅由累计毫秒折算，避免多次暂停产生截断误差。
     * @param ticketId 工单 ID
     * @param old 已锁定的旧 SLA 状态，提供预期版本
     * @param state 目标时钟状态
     * @param elapsed 已结算的累计毫秒
     * @param started 运行区间起点，非运行状态传 null
     * @param due 当前解决到期时间，非运行状态传 null
     * @param met 解决达标结果：1 达标、0 未达标、null 尚未结算或主动取消
     */
    private void change(long ticketId, SlaMapper.ClockState old, String state, long elapsed,
                        Instant started, Instant due, Integer met) {
        if (sla.changeClock(ticketId, old.version(), state, elapsed, elapsed / 1000, started, due, met) != 1)
            throw new IllegalStateException("SLA 投影更新冲突");
    }
    private void event(long ticketId, String type, String key, long actorId, Instant now, String reason) {
        if (sla.event(IdWorker.getId(), ticketId, type, now, key, actorId, reason) != 1)
            throw new IllegalStateException("SLA 事件写入失败");
    }
}
