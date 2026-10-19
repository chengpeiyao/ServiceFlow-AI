package com.serviceflow.audit;

import org.apache.ibatis.annotations.*;
import java.time.Instant;

@Mapper
public interface IdempotencyMapper {
    record Entry(long id, String requestSha256, String requestStatus, String responseBodyJson) {}

    @Insert("""
            INSERT INTO idempotency_request(id,actor_user_id,action_code,idempotency_key,request_sha256,request_status,created_at)
            VALUES(#{id},#{actorId},#{action},#{key},#{digest},'PROCESSING',#{now})
            ON DUPLICATE KEY UPDATE id=id
            """)
    int reserve(long id, long actorId, String action, String key, String digest, Instant now);

    @Select("""
            SELECT id,request_sha256,request_status,response_body_json FROM idempotency_request
            WHERE actor_user_id=#{actorId} AND action_code=#{action} AND idempotency_key=#{key} FOR UPDATE
            """)
    Entry lock(long actorId, String action, String key);

    @Update("""
            UPDATE idempotency_request SET request_status='SUCCEEDED',response_http_status=#{httpStatus},
            response_body_json=#{body},completed_at=#{now} WHERE id=#{id} AND request_status='PROCESSING'
            """)
    int finish(long id, int httpStatus, String body, Instant now);
}
