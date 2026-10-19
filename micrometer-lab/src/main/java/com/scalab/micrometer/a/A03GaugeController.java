package com.scalab.micrometer.a;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A-03 Gauge 验证——对照 D1 §九 Gauge 被动观察 + D2 Gauge vs Counter 选择策略。
 *
 * 验证点：
 * 1. Gauge 不自己存储值——通过 {@code Supplier} 从被观察对象读取
 * 2. 修改被观察对象 → Gauge 立刻反映新值（无需手动 increment）
 * 3. Gauge 是瞬时值——可升可降（对照 Counter 的单调递增）
 * 4. 两种 Gauge 模式：队列大小 + 自定义 Supplier
 */
@RestController
@RequestMapping("/a03")
public class A03GaugeController {

    /** 被观察的队列——Gauge 被动读取其大小 */
    private final ConcurrentLinkedQueue<String> taskQueue = new ConcurrentLinkedQueue<>();
    /** 手动计数对照 */
    private final AtomicInteger enqueued = new AtomicInteger(0);

    /**
     * 构造器中注册 Gauge——对照 D1 §九：Gauge 的 valueFunction 每次 Prometheus scrape 时执行一次，
     * 不是启动时一次性求值。这就是 FunctionCounter vs Gauge 的核心区别。
     */
    public A03GaugeController(MeterRegistry registry) {
        // Gauge 模式一：从集合对象读取 size()
        Gauge.builder("a03.queue.size", taskQueue, ConcurrentLinkedQueue::size)
                .description("A03 任务队列长度")
                .tag("queue", "tasks")
                .register(registry);

        // Gauge 模式二：自定义 ToDoubleFunction
        Gauge.builder("a03.queue.processed", enqueued, AtomicInteger::doubleValue)
                .description("A03 已处理任务数（瞬时值——可升可降）")
                .tag("queue", "tasks")
                .register(registry);
    }

    /** 入队任务——队列大小增加，Gauge 自动反映 */
    @GetMapping("/enqueue")
    public String enqueue() {
        String task = "task-" + enqueued.incrementAndGet();
        taskQueue.offer(task);
        return "Enqueued: " + task
                + " | queue.size=" + taskQueue.size()
                + " | processed=" + enqueued.get();
    }

    /** 出队任务——队列大小减少，Gauge 自动反映（Gauge 可降！） */
    @GetMapping("/dequeue")
    public String dequeue() {
        String task = taskQueue.poll();
        if (task == null) {
            return "Queue empty | size=0 | processed=" + enqueued.get();
        }
        enqueued.decrementAndGet(); // 模拟"处理完"——processed 下降
        return "Dequeued: " + task
                + " | queue.size=" + taskQueue.size()
                + " | processed=" + enqueued.get();
    }
}
