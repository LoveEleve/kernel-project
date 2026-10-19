package com.scalab.micrometer.a;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ThreadLocalRandom;

/**
 * A-13 Observation 编程 API——对照 D3 §二 Observation 生命周期 + D3 §十一 编程式 API。
 *
 * 验证点：
 * 1. {@code Observation.createNotStarted().start()} 手动创建观测
 * 2. {@code observation.observe(() -> task)} lambda 便捷包装
 * 3. Timer 出现在 /actuator/prometheus——证明手动 Observation 也能产生 metrics
 */
@RestController
@RequestMapping("/a13")
public class A13ObservationController {

    private final ObservationRegistry registry;

    public A13ObservationController(ObservationRegistry registry) {
        this.registry = registry;
    }

    /** 手动创建 Observation——对照 D3 §二 完整的生命周期 */
    @GetMapping("/manual")
    public String manualObserve() {
        long delay = ThreadLocalRandom.current().nextLong(10, 100);
        // 手动 start → openScope → stop 全流程
        Observation observation = Observation.createNotStarted("a13.manual.operation", registry)
                .lowCardinalityKeyValue("type", "manual")
                .start();
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            observation.error(e);
            Thread.currentThread().interrupt();
        } finally {
            observation.stop();
        }
        return "Manual observation done in ~" + delay + "ms";
    }

    /** observe() lambda 便捷包装——对照 D3 §十一 */
    @GetMapping("/lambda")
    public String lambdaObserve() {
        long delay = ThreadLocalRandom.current().nextLong(10, 100);
        // observe() 内部 = start() → try-finally { stop() }
        Observation.createNotStarted("a13.lambda.operation", registry)
                .lowCardinalityKeyValue("type", "lambda")
                .observe(() -> {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        return "Lambda observation done in ~" + delay + "ms";
    }
}
