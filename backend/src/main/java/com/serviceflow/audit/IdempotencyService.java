package com.serviceflow.audit;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.serviceflow.shared.BusinessException;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

@Service
public class IdempotencyService {
    public record Reservation(long id, String replayJson) { public boolean replay() { return replayJson != null; } }
    private final IdempotencyMapper mapper;
    private final ObjectMapper json;
    public IdempotencyService(IdempotencyMapper mapper, ObjectMapper json) { this.mapper = mapper; this.json = json; }

    public Reservation reserve(long actorId, String action, String path, String key, Object command, Instant now) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new BusinessException(400, "IDEMPOTENCY_KEY_REQUIRED", "请提供有效的 Idempotency-Key");
        }
        // 路径进入摘要，防止不同资源使用同一请求键和相同正文时错误重放。
        String digest = digest(encode(Map.of("action", action, "path", path, "body", command)));
        long candidateId = IdWorker.getId();
        mapper.reserve(candidateId, actorId, action, key, digest, now);
        var entry = mapper.lock(actorId, action, key);
        if (!digest.equals(entry.requestSha256())) {
            throw BusinessException.conflict("IDEMPOTENCY_KEY_CONFLICT", "同一请求键不能用于不同请求内容");
        }
        if (entry.id() == candidateId) return new Reservation(candidateId, null);
        if ("SUCCEEDED".equals(entry.requestStatus())) return new Reservation(entry.id(), entry.responseBodyJson());
        throw BusinessException.conflict("REQUEST_IN_PROGRESS", "该请求正在处理，请稍后使用原请求键重试");
    }

    public void finish(Reservation reservation, int httpStatus, Object response, Instant now) {
        if (mapper.finish(reservation.id(), httpStatus, encode(response), now) != 1) throw new IllegalStateException("幂等结果保存失败");
    }

    public <T> T replay(Reservation reservation, Class<T> responseType) {
        try { return json.readValue(reservation.replayJson(), responseType); }
        catch (JsonProcessingException error) { throw new IllegalStateException("幂等结果读取失败", error); }
    }

    public String encode(Object value) {
        try { return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("JSON 序列化失败", error); }
    }

    private String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
