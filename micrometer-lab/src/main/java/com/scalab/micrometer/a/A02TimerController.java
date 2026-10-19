package com.scalab.micrometer.a;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * A-02 Timer 验证——对照 D1 §二 newTimer + D4 §一 percentiles vs histogram。
 *
 * 验证点：
 * 1. {@code Timer.builder().register()} 创建 Timer
 * 2. {@code timer.record(duration, unit)} 记录单次耗时
 * 3. Prometheus 输出：{@code _seconds_count / _seconds_sum / _seconds_max} + histogram buckets
 * 4. {@code publishPercentileHistogram(true)} 生成预分桶直方图
 */
@RestController
@RequestMapping("/a02")
public class A02TimerController {

    private final Timer apiLatency;

    public A02TimerController(MeterRegistry registry) {
        // 创建 Timer——对照 D1 §二 registry.timer()
        this.apiLatency = Timer.builder("a02.api.latency")       // ← 指标名
                .description("A02 API 调用耗时")                   // ← # HELP
                .tag("endpoint", "/a02/work")
                .publishPercentileHistogram(true)                  // ← 服务端预分桶直方图
                .publishPercentiles(0.5, 0.95)                    // ← P50 / P95 客户端计算
                .register(registry);
    }

    /** 模拟耗时操作——每次 GET 随机延迟 10~200ms */
    @GetMapping("/work")
    public String doWork() {
        long start = System.nanoTime();
        long delay = ThreadLocalRandom.current().nextLong(10, 200);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        // record()——对照 D1 §九 Timer 主动记录
        apiLatency.record(durationMs, TimeUnit.MILLISECONDS);
        return "Work done in " + durationMs + "ms"
                + " | Timer: count=" + apiLatency.count()
                + " mean=" + String.format("%.1fms", apiLatency.mean(TimeUnit.MILLISECONDS))
                + " max=" + String.format("%.1fms", apiLatency.max(TimeUnit.MILLISECONDS));
    }

    /** 验证 Timer 的 count/mean/max 三维指标 */
    @GetMapping("/stats")
    public String stats() {
        return "count=" + apiLatency.count()
                + " mean=" + String.format("%.1fms", apiLatency.mean(TimeUnit.MILLISECONDS))
                + " max=" + String.format("%.1fms", apiLatency.max(TimeUnit.MILLISECONDS))
                + " total=" + String.format("%.1fs", apiLatency.totalTime(TimeUnit.SECONDS));
    }
}
