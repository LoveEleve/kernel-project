package com.scalab.consistency.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 消息幂等辅助——Redis SETNX 去重 + 失败删除标记 + 降级放行。
 *
 * <p>对齐 xhs-project {@code MessageIdempotentHelper}：
 * <ul>
 *   <li>首次处理：SETNX 成功 → true</li>
 *   <li>重复消息：SETNX 失败 → false（跳过）</li>
 *   <li>业务失败：{@link #removeMark} 删除标记 → 允许 MQ 重试</li>
 *   <li>Redis 不可用：降级放行（保可用性）+ 告警日志；强幂等需 DB 唯一键兜底</li>
 * </ul>
 */
@Component
public class IdempotentHelper {

    private static final Logger log = LoggerFactory.getLogger(IdempotentHelper.class);
    private static final String KEY_PREFIX = "msg:idempotent:";

    private final StringRedisTemplate redis;

    public IdempotentHelper(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 首次处理返回 true；重复消息返回 false。 */
    public boolean isFirstProcess(String bizType, String bizId, long ttlSeconds) {
        String key = KEY_PREFIX + bizType + ":" + bizId;
        try {
            Boolean success = redis.opsForValue().setIfAbsent(key, "1", ttlSeconds, TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(success)) {
                log.debug("[幂等] 首次处理 bizType={} bizId={}", bizType, bizId);
                return true;
            }
            log.warn("[幂等] 重复消息已忽略 bizType={} bizId={}", bizType, bizId);
            return false;
        } catch (Exception e) {
            // Redis 不可用 → 降级放行（核心业务优先），DB 唯一键兜底
            log.error("[幂等] Redis 不可用，降级放行 bizType={} bizId={}", bizType, bizId, e);
            return true;
        }
    }

    /** 业务失败时删除标记，允许重试（仅确定未执行成功时调用）。 */
    public void removeMark(String bizType, String bizId) {
        String key = KEY_PREFIX + bizType + ":" + bizId;
        try {
            redis.delete(key);
            log.info("[幂等] 已删除标记（允许重试）bizType={} bizId={}", bizType, bizId);
        } catch (Exception e) {
            log.error("[幂等] 删除标记失败 bizType={} bizId={}", bizType, bizId, e);
        }
    }

    /** 统计当前幂等标记数（演示用）。 */
    public long countMarks() {
        try {
            var keys = redis.keys(KEY_PREFIX + "*");
            return keys == null ? 0 : keys.size();
        } catch (Exception e) {
            return -1;
        }
    }
}
