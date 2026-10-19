package com.scalab.consistency;

import com.scalab.consistency.core.IdempotentHelper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdempotentHelperTest {

    @SuppressWarnings("unchecked")
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final IdempotentHelper helper = new IdempotentHelper(redis);

    @Test
    void firstProcessReturnsTrueOnSetIfAbsentSuccess() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(eq("msg:idempotent:t:1"), eq("1"), anyLong(), any(TimeUnit.class))).thenReturn(true);
        assertThat(helper.isFirstProcess("t", "1", 60)).isTrue();
    }

    @Test
    void duplicateReturnsFalse() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(any(), any(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        assertThat(helper.isFirstProcess("t", "1", 60)).isFalse();
    }

    @Test
    void redisDownDegradesToPassThrough() {
        when(redis.opsForValue()).thenThrow(new RuntimeException("redis down"));
        // 降级放行（可用性优先；强幂等靠 DB 唯一键兜底）
        assertThat(helper.isFirstProcess("t", "1", 60)).isTrue();
    }
}
