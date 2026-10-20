package com.serviceflow.sla;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class SlaService {
    private final SlaMapper sla;
    public SlaService(SlaMapper sla) { this.sla = sla; }

    // 调用方已持有工单锁；SLA 投影、事件与业务结果必须同事务提交。
    public void pause(long ticketId, long resolutionId, long actorId, Instant now) {
        var state = state(ticketId, "RUNNING");
        long elapsed = elapsed(state, now);
        change(ticketId, state, "PAUSED_ACCEPTANCE", elapsed, null, null, null);
        event(ticketId, "RESOLUTION_PROPOSED", "RESOLUTION:" + resolutionId, actorId, now, "提交方案，暂停解决时钟");
    }

    public void resume(long ticketId, long acceptanceId, long actorId, Instant now) {
        var state = state(ticketId, "PAUSED_ACCEPTANCE");
        long remaining = Math.max(0, target(state) - state.resolutionElapsedMillis());
        change(ticketId, state, "RUNNING", state.resolutionElapsedMillis(), now, now.plusMillis(remaining), null);
        event(ticketId, "ACCEPTANCE_REJECTED", "ACCEPTANCE:" + acceptanceId, actorId, now, "拒绝验收，保留累计耗时并恢复解决时钟");
    }

    public void close(long ticketId, long acceptanceId, long actorId, Instant now) {
        var state = state(ticketId, "PAUSED_ACCEPTANCE");
        change(ticketId, state, "CLOSED", state.resolutionElapsedMillis(), null, null,
                state.resolutionElapsedMillis() <= target(state) ? 1 : 0);
        event(ticketId, "CLOSED", "ACCEPTANCE:" + acceptanceId, actorId, now, "验收通过，结算解决时钟和首响结果");
    }

    public void cancel(long ticketId, long actorId, Instant now, String reason) {
        var state = state(ticketId, "RUNNING");
        // 取消保留首响事实，解决结果保持未知，以 CANCELLED 状态单列，不伪装为达标关闭。
        change(ticketId, state, "CANCELLED", elapsed(state, now), null, null, null);
        event(ticketId, "CANCELLED", "CANCEL:" + ticketId, actorId, now, reason);
    }

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
