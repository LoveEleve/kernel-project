package com.scalab.micrometer.a;

import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ThreadLocalRandom;

/**
 * A-07 @Timed 注解验证——对照 D4 §一 @Timed 注解 + D4 §二 TimedAspect 双切面。
 *
 * 验证点：
 * 1. {@code @Timed} 注解自动记录方法耗时——无需手动调用 timer.record()
 * 2. Timer 出现在 /actuator/prometheus（_seconds_count/_seconds_sum/_seconds_max）
 * 3. {@code percentiles} 和 {@code histogram} 参数生效
 * 4. 对照 D4 §三：@Timed 的 class/method/exception tag 自动生成
 */
@RestController
@RequestMapping("/a07")
public class A07TimedController {

    public A07TimedController(MeterRegistry registry) {
        // @Timed 注解会自动通过 TimedAspect 切面注册 Timer
        // 无需手动创建——对照 D4 §三 processWithTimer()
    }

    /** @Timed 自动记录耗时——每次 GET 生成 Timer 指标 */
    @Timed(value = "a07.timed.request", percentiles = {0.5, 0.95}, histogram = true)
    @GetMapping("/work")
    public String doWork() {
        long delay = ThreadLocalRandom.current().nextLong(10, 200);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "Work done in ~" + delay + "ms — @Timed auto-records duration";
    }

    /** 带 extraTags 的 @Timed */
    @Timed(value = "a07.timed.fast", extraTags = {"type", "fast"})
    @GetMapping("/fast")
    public String fastWork() {
        return "Fast operation — @Timed with extraTags";
    }
}
