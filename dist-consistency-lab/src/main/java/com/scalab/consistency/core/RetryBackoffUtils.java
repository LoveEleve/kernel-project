package com.scalab.consistency.core;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 补偿重试退避工具——指数退避（封顶）+ 抖动。
 *
 * <p>对齐 xhs-project {@code RetryBackoffUtils} 的语义：
 * <ul>
 *   <li>指数退避：base × 2^(attempt-1)，封顶 maxSeconds</li>
 *   <li>抖动：±jitterRatio（默认 0.2）——避免多实例"对齐共振"在下游恢复瞬间二次打垮</li>
 * </ul>
 *
 * <p>例子：base=30s、max=480s、jitter=0.2 时
 * attempt=1 → ~30s、2 → ~60s、3 → ~120s、4 → ~240s、5 → ~480s（封顶）、6 → ~480s。
 */
public final class RetryBackoffUtils {

    private RetryBackoffUtils() {
    }

    /** 指数退避（秒）：base * 2^(attempt-1)，封顶 maxSeconds，再叠加 ±jitterRatio 抖动。 */
    public static long exponentialSeconds(int attempt, long baseSeconds, long maxSeconds, double jitterRatio) {
        long delay = Math.max(0, baseSeconds);
        for (int i = 1; i < attempt && delay < maxSeconds; i++) {
            delay = Math.min(delay * 2, maxSeconds);
        }
        return jitter(delay, jitterRatio);
    }

    /** 给任意值叠加 ±jitterRatio 抖动（结果不为负）。 */
    public static long jitter(long base, double jitterRatio) {
        if (base <= 0 || jitterRatio <= 0) {
            return base;
        }
        long delta = (long) (base * jitterRatio);
        return Math.max(0, base + ThreadLocalRandom.current().nextLong(-delta, delta + 1));
    }

    /** 生成完整退避序列（演示用）。 */
    public static long[] sequence(int attempts, long baseSeconds, long maxSeconds, double jitterRatio) {
        long[] result = new long[attempts];
        for (int i = 1; i <= attempts; i++) {
            result[i - 1] = exponentialSeconds(i, baseSeconds, maxSeconds, jitterRatio);
        }
        return result;
    }
}
