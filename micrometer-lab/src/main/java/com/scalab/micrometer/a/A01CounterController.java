package com.scalab.micrometer.a;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A-01 Counter 验证——对照 D1 §二 newCounter + D1 §九 Counter 采样哲学。
 *
 * 验证点：
 * 1. {@code registry.counter()} 创建 Counter（create-or-reuse 幂等）
 * 2. {@code counter.increment()} 主动递增
 * 3. Counter 出现在 /actuator/prometheus 输出中
 * 4. Meter.Id 不可变 + Tags 语义
 */
@RestController
@RequestMapping("/a01")
public class A01CounterController {

    private final Counter ordersCreated;
    private final AtomicInteger count = new AtomicInteger(0);

    public A01CounterController(MeterRegistry registry) {
        // 创建 Counter——对照 D1 §二 registry.counter()
        this.ordersCreated = Counter.builder("a01.orders.created")     // ← 指标名
                .description("A01 订单创建计数")                         // ← 描述（→ # HELP）
                .tag("status", "success")                               // ← Tag（→ {status="success"}）
                .tag("region", "us-east")
                .register(registry);                                    // ← 注册到 Prometheus Registry
    }

    /** 触发计数——每次 GET 递增 1 */
    @GetMapping("/order")
    public String createOrder() {
        ordersCreated.increment();                                      // ← Counter 主动递增
        int c = count.incrementAndGet();
        return "Order created #" + c + " | Counter value: " + ordersCreated.count();
    }

    /**
     * 验证 create-or-reuse——用相同 name+tags 再次 Counter.builder().register(),
     * 返回的 Java 对象是否和构造器中创建的 ordersCreated 是同一个引用。
     * 对照 D1 §三 registerMeterIfNecessary create-or-reuse + D1 §七 Meter.Id equals。
     */
    @GetMapping("/verify-reuse")
    public String verifyReuse(MeterRegistry registry) {
        double before = ordersCreated.count();
        // 用相同的 name+tags 再次调用 Counter.builder().register()
        Counter second = Counter.builder("a01.orders.created")
                .tag("status", "success").tag("region", "us-east")
                .register(registry);
        // 在第二个引用上递增
        second.increment();
        double after = ordersCreated.count();
        // 如果两个引用指向同一个底层 Meter，ordersCreated.count() 应该 +1
        return (after == before + 1)
                ? "create-or-reuse ✅ (" + before + " → " + after + ")"
                : "DIFFERENT counter ❌ (before=" + before + " after=" + after + ")";
    }
}
