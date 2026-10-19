package com.scalab.micrometer.a;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A-10 SLO 直方图验证——对照 D4 §一 {@code serviceLevelObjectives} 参数。
 *
 * <p>版本说明（基线 Micrometer 1.12.5 / Boot 3.2.5）：{@code @Timed} 注解的
 * {@code serviceLevelObjectives} 参数是 Micrometer 1.13+ 的 API；1.12.5 下改用
 * 编程式 {@code Timer.builder().serviceLevelObjectives(...)} 达到同样效果。
 *
 * <p>验证点：
 * 1. {@code serviceLevelObjectives} 指定自定义桶边界（Duration 单位）
 * 2. Prometheus histogram bucket 的 {@code le} 值与 SLO 参数对齐
 * 3. 对比默认生成的 histogram（A07）vs 自定义 SLO histogram——桶的数量和边界不同
 */
@RestController
@RequestMapping("/a10")
public class A10SLOController {

    private final Timer sloTimer;
    private final Timer defaultTimer;

    public A10SLOController(MeterRegistry registry) {
        this.sloTimer = Timer.builder("a10.slo.request")
                .description("A10 SLO 直方图验证——自定义桶边界")
                .serviceLevelObjectives(Duration.ofSeconds(1), Duration.ofSeconds(5),
                        Duration.ofSeconds(10), Duration.ofSeconds(50), Duration.ofSeconds(100))
                .register(registry);
        this.defaultTimer = Timer.builder("a10.default.request")
                .description("A10 默认 histogram 对比")
                .publishPercentileHistogram()
                .register(registry);
    }

    /**
     * SLO 直方图——指定桶边界为 [1s, 5s, 10s, 50s, 100s]。
     * 对照 D4 §一：SLO 边界用 {@code Duration} 表达（不是裸数字毫秒）。
     */
    @GetMapping("/work")
    public String doWork() {
        return sloTimer.record(() -> {
            long delay = ThreadLocalRandom.current().nextLong(100, 500); // 100-500ms
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "Work done in ~" + delay + "ms — SLO buckets: 1s/5s/10s/50s/100s";
        });
    }

    /**
     * 对比：默认 histogram（无 SLO 参数）——桶由 minExpected/maxExpected 自动生成
     */
    @GetMapping("/compare")
    public String compare() {
        return defaultTimer.record(() -> {
            long delay = ThreadLocalRandom.current().nextLong(100, 500);
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "Compare default histogram — auto-generated buckets";
        });
    }
}
