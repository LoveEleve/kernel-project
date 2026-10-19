package com.scalab.micrometer.b;

import io.micrometer.core.instrument.Counter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B-02 Controller——验证自定义 B02LoggingMeterRegistry。
 *
 * 手动创建 B02LoggingMeterRegistry 并使用它——Counter increment 会在控制台输出日志。
 */
@RestController
@RequestMapping("/b02")
public class B02LoggingController {

    private final B02LoggingMeterRegistry registry = new B02LoggingMeterRegistry();
    private final Counter testCounter;

    public B02LoggingController() {
        this.testCounter = Counter.builder("b02.test.counter")
                .description("B02 自定义 Registry 的测试 Counter")
                .tag("registry", "logging")
                .register(registry);
    }

    /** 每次 GET 触发 Counter increment——观察控制台 B02 日志 */
    @GetMapping("/inc")
    public String increment() {
        testCounter.increment();
        return "Incremented. Check console for [B02] log entry. Count=" + testCounter.count();
    }
}
