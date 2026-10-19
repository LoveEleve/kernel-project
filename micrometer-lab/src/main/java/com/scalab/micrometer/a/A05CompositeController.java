package com.scalab.micrometer.a;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A-05 多后端路由——对照 D1 §一 Facade + D1 §四 CompositeMeterRegistry 嵌套机制。
 *
 * 验证点：
 * 1. CompositeMeterRegistry 包含 Simple + Prometheus 两个后端
 * 2. 同一个 Counter 出现在两个 Registry 中
 * 3. SimpleMeterRegistry.getMeters() 可查询
 * 4. /actuator/prometheus 可见（Prometheus 后端）
 * 5. 对照 D1 §四：Composite 用 IdentityHashMap（==）去重，同类型可共存
 */
@RestController
@RequestMapping("/a05")
public class A05CompositeController {

    private final CompositeMeterRegistry composite;
    private final SimpleMeterRegistry simple;
    private final Counter routedCounter;
    private final AtomicInteger count = new AtomicInteger(0);

    /**
     * 构造器中手动搭建 CompositeMeterRegistry。
     * 对照 D1 §四：Composite 包装多个子 Registry，Meter 操作广播到所有子 Registry。
     */
    public A05CompositeController(MeterRegistry prometheusRegistry) {
        // 1. 创建 SimpleMeterRegistry（纯内存）——对照 D1 §四 SimpleMeterRegistry
        this.simple = new SimpleMeterRegistry();

        // 2. 创建 CompositeMeterRegistry，加入 Simple + Prometheus
        //    对照 D1 §四：Composite 用 IdentityHashMap（==）去重
        this.composite = new CompositeMeterRegistry();
        composite.add(simple);
        composite.add(prometheusRegistry);

        // 3. 在 Composite 上创建 Counter——自动广播到所有子 Registry
        this.routedCounter = Counter.builder("a05.routed.requests")
                .description("A05 多后端路由——同一 Counter 在 Simple 和 Prometheus 中同时出现")
                .tag("source", "composite")
                .register(composite);
    }

    /** 触发计数——Composite 上的 Counter 同时广播到 Simple + Prometheus */
    @GetMapping("/inc")
    public String increment() {
        routedCounter.increment();
        int c = count.incrementAndGet();
        return "Request #" + c + " | Composite counter: " + routedCounter.count();
    }

    /** 查看 SimpleMeterRegistry 中的 Meter——证明 Counter 确实被路由到了 Simple 后端 */
    @GetMapping("/simple")
    public String simpleMeters() {
        StringBuilder sb = new StringBuilder("SimpleMeterRegistry meters:\n");
        simple.getMeters().forEach(m ->
            sb.append("  [").append(m.getId().getType()).append("] ")
              .append(m.getId().getName()).append("\n")
        );
        sb.append("Count: ").append(simple.getMeters().size());
        return sb.toString();
    }
}
