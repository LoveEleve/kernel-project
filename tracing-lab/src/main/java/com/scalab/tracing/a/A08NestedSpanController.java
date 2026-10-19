package com.scalab.tracing.a;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A08 嵌套 Span 父子链验证（对照 D1 §二.1 / D3 §二.3）
 * 
 * 验证点:
 * 1. 子 span 继承父 traceId
 * 2. 子 span.parentId == 父 span.spanId
 * 3. 多层嵌套 (3 层) 正确链接
 */
@RestController
@RequestMapping("/a08")
public class A08NestedSpanController {
    private static final Logger log = LoggerFactory.getLogger(A08NestedSpanController.class);
    private final Tracer tracer;

    public A08NestedSpanController(Tracer tracer) {
        this.tracer = tracer;
    }

    /** 两层嵌套——父-子 */
    @GetMapping("/parent-child")
    public String parentChild() {
        Span parent = tracer.nextSpan().name("a08.parent").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(parent)) {
            parent.tag("level", "parent");
            Span child = tracer.nextSpan().name("a08.child").start();
            try {
                child.tag("level", "child");
                boolean sameTrace = parent.context().traceId().equals(child.context().traceId());
                boolean correctParent = parent.context().spanId().equals(child.context().parentId());
                log.info("[A08] parent-child: sameTrace={}, correctParent={}", sameTrace, correctParent);
                return String.format("parent-child | sameTrace=%s | correctParent=%s | parent=%s child=%s",
                        sameTrace, correctParent,
                        parent.context().spanId().substring(0, 8),
                        child.context().spanId().substring(0, 8));
            } finally {
                child.end();
            }
        } finally {
            parent.end();
        }
    }

    /** 三层嵌套——根-父-子 */
    @GetMapping("/triple")
    public String tripleNested() {
        Span root = tracer.nextSpan().name("a08.root").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(root)) {
            root.tag("level", "root");
            Span middle = tracer.nextSpan().name("a08.middle").start();
            try (Tracer.SpanInScope ws2 = tracer.withSpan(middle)) {
                middle.tag("level", "middle");
                Span leaf = tracer.nextSpan().name("a08.leaf").start();
                try {
                    leaf.tag("level", "leaf");
                    String traceId = root.context().traceId();
                    boolean allSameTrace = traceId.equals(middle.context().traceId())
                            && traceId.equals(leaf.context().traceId());
                    boolean middleParent = root.context().spanId().equals(middle.context().parentId());
                    boolean leafParent = middle.context().spanId().equals(leaf.context().parentId());
                    log.info("[A08] triple: sameTrace={}, midParent={}, leafParent={}",
                            allSameTrace, middleParent, leafParent);
                    return String.format("triple | sameTrace=%s | midParent=%s | leafParent=%s",
                            allSameTrace, middleParent, leafParent);
                } finally {
                    leaf.end();
                }
            } finally {
                middle.end();
            }
        } finally {
            root.end();
        }
    }
}
