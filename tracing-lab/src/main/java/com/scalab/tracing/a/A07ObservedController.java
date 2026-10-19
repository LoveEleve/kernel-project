package com.scalab.tracing.a;

import io.micrometer.observation.annotation.Observed;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A07 @Observed 注解自动 Span 验证（对照 D4 §一/§七）
 * 
 * 验证点:
 * 1. @Observed 自动创建 Span
 * 2. Span name 来自注解参数
 * 3. 日志中 traceId/spanId 可见
 * 
 * 注: @Observed 需要 public 方法 + 外部代理调用（Spring AOP 限制）;
 *     私有方法 / 内部调用 this.method() 均不会被拦截。
 */
@RestController
@RequestMapping("/a07")
public class A07ObservedController {
    private static final Logger log = LoggerFactory.getLogger(A07ObservedController.class);
    private final Tracer tracer;

    public A07ObservedController(Tracer tracer) {
        this.tracer = tracer;
    }

    /** @Observed 创建名为 "a07.simple" 的 Span */
    @Observed(name = "a07.simple")
    @GetMapping("/simple")
    public String simpleObserved() {
        log.info("[A07] Inside @Observed method: a07.simple");
        return "@Observed ok | span=a07.simple (traceId in log)";
    }

    /** @Observed 带 contextualName——覆盖默认 HTTP 命名 */
    @Observed(name = "a07.process-order")
    @GetMapping("/process")
    public String processOrder() {
        log.info("[A07] Processing order with @Observed(name=a07.process-order)");
        return "@Observed ok | span=a07.process-order";
    }

    /** 嵌套 span——@Observed 外层 + 手动 tracer 内层 */
    @Observed(name = "a07.outer")
    @GetMapping("/nested")
    public String nestedObserved() {
        log.info("[A07] Outer span: a07.outer");
        Span inner = tracer.nextSpan().name("a07.inner").start();
        try {
            String innerTraceId = inner.context().traceId();
            log.info("[A07] Inner span: a07.inner (manual, traceId={})", innerTraceId);
            return "nested ok | outer=@Observed a07.outer | inner=manual a07.inner | traceId=" + innerTraceId;
        } finally {
            inner.end();
        }
    }
}
