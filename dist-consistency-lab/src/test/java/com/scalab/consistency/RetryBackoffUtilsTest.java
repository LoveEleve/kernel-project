package com.scalab.consistency;

import com.scalab.consistency.core.RetryBackoffUtils;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetryBackoffUtilsTest {

    @Test
    void exponentialGrowsAndCaps() {
        // 无抖动验证指数增长与封顶（jitterRatio=0）
        assertThat(RetryBackoffUtils.exponentialSeconds(1, 30, 480, 0)).isEqualTo(30);
        assertThat(RetryBackoffUtils.exponentialSeconds(2, 30, 480, 0)).isEqualTo(60);
        assertThat(RetryBackoffUtils.exponentialSeconds(3, 30, 480, 0)).isEqualTo(120);
        assertThat(RetryBackoffUtils.exponentialSeconds(4, 30, 480, 0)).isEqualTo(240);
        assertThat(RetryBackoffUtils.exponentialSeconds(5, 30, 480, 0)).isEqualTo(480);
        assertThat(RetryBackoffUtils.exponentialSeconds(8, 30, 480, 0)).isEqualTo(480); // 封顶
    }

    @Test
    void jitterStaysWithinBounds() {
        for (int i = 0; i < 200; i++) {
            long v = RetryBackoffUtils.exponentialSeconds(3, 30, 480, 0.2);
            assertThat(v).isBetween(96L, 144L); // 120 ± 20%
        }
    }

    @Test
    void jitterNeverNegative() {
        assertThat(RetryBackoffUtils.jitter(0, 0.5)).isZero();
        assertThat(RetryBackoffUtils.jitter(10, 0)).isEqualTo(10);
    }
}
