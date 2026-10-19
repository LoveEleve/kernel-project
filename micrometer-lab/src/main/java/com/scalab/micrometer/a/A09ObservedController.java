package com.scalab.micrometer.a;

import io.micrometer.observation.annotation.Observed;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ThreadLocalRandom;

/**
 * A-09 @Observed 注解验证——对照 D3 §一 Observation 统一模型 + D3 §十四 @Observed vs @Timed。
 *
 * 验证点：
 * 1. {@code @Observed} 同时产生 metrics + tracing（走 ObservationHandler 链）
 * 2. Timer 出现在 /actuator/prometheus——由 DefaultMeterObservationHandler 自动生成
 * 3. 对照 D3 §十四：@Observed 不直接控制 percentiles/histogram（依赖 Convention）
 */
@RestController
@RequestMapping("/a09")
public class A09ObservedController {

    /** @Observed 自动通过 ObservationHandler 链生成 Timer */
    @Observed(name = "a09.observed.request",
            contextualName = "a09-observed-request")
    @GetMapping("/work")
    public String doWork() {
        long delay = ThreadLocalRandom.current().nextLong(10, 150);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "Work done in ~" + delay + "ms — @Observed via ObservationHandler chain";
    }
}
