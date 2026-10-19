package com.scalab.tracing.b;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * B05 自定义 ObservationHandler——Span 创建时自动追加 tag（对照 D4 §二/§六）
 * 
 * 通过 ApplicationReadyEvent 注册到 ObservationRegistry。
 */
@Component
public class B05AutoTagObservationHandler implements TracingObservationHandler<Observation.Context> {
    private static final Logger log = LoggerFactory.getLogger(B05AutoTagObservationHandler.class);
    private final Tracer tracer;

    public B05AutoTagObservationHandler(Tracer tracer) {
        this.tracer = tracer;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register(ApplicationReadyEvent event) {
        ObservationRegistry registry = event.getApplicationContext().getBean(ObservationRegistry.class);
        registry.observationConfig().observationHandler(this);
        log.info("[B05] ObservationHandler registered — auto-tag on every span");
    }

    @Override
    public void onStart(Observation.Context context) {
        Span span = getTracingContext(context).getSpan();
        if (span != null) {
            span.tag("lab", "tracing-lab");
            span.tag("b05", "auto-tagged");
            log.debug("[B05] Auto-tagged span: lab=tracing-lab, b05=auto-tagged");
        }
    }

    @Override
    public void onStop(Observation.Context context) {
        // noop — tags already applied in onStart
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return true; // 对所有 Observation 生效
    }

    @Override
    public Tracer getTracer() {
        return this.tracer; // 框架需要——即使 B05 自己不创建 Span
    }
}
