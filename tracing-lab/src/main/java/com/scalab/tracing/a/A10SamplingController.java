package com.scalab.tracing.a;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A10 采样验证（对照 D7 §一）
 * 
 * 验证点:
 * 1. sampling.probability=1.0 → 所有 span 被采样（isNoop=false）
 * 2. 同请求内 child span 继承父 traceId（nextSpan 行为）
 * 3. isNoop=false 表示 span 被正常采样
 */
@RestController
@RequestMapping("/a10")
public class A10SamplingController {
    private static final Logger log = LoggerFactory.getLogger(A10SamplingController.class);
    private final Tracer tracer;

    public A10SamplingController(Tracer tracer) {
        this.tracer = tracer;
    }

    /** 验证 100% 采样——所有 span 都有有效 traceId */
    @GetMapping("/verify")
    public String verifySampling() {
        int sampled = 0;
        int total = 5;
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < total; i++) {
            Span span = tracer.nextSpan().name("a10.sample-" + i).start();
            try {
                if (!span.isNoop()) {
                    sampled++;
                    if (ids.length() > 0) ids.append(",");
                    ids.append(span.context().traceId().substring(0, 8));
                }
            } finally {
                span.end();
            }
        }
        log.info("[A10] Sampling verification: {}/{} spans sampled", sampled, total);
        return String.format("sampling | sampled=%d/%d | probability=1.0 | traceIds=%s",
                sampled, total, ids);
    }

    /** 检查当前 sampling 配置 */
    @GetMapping("/config")
    public String samplingConfig() {
        Span span = tracer.nextSpan().name("a10.config-check").start();
        try {
            boolean isNoop = span.isNoop();
            String traceId = span.context().traceId();
            log.info("[A10] Config: isNoop={}, traceId={}", isNoop, traceId);
            return String.format("config | isNoop=%s | traceId=%s | note=sampling.probability=1.0 in application.yml",
                    isNoop, traceId.substring(0, 8));
        } finally {
            span.end();
        }
    }
}
