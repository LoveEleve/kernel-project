package com.scalab.micrometer.b;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * B-03 自定义 ObservationHandler——对照 D3 ObservationHandler 接口 + D3 §四 Composite 分发。
 *
 * 验证点：
 * 1. 实现 {@code ObservationHandler} 的三个事件方法
 * 2. {@code supportsContext()} 返回 true——接收所有类型的 Context
 * 3. onStart/onStop 打印日志——自定义"观测消费者"
 * 4. Handler 注册到 {@code ObservationRegistry} 后，所有 Observation 自动触发回调
 */
public class B03LoggingObservationHandler implements ObservationHandler<Observation.Context> {

    private static final Logger log = LoggerFactory.getLogger(B03LoggingObservationHandler.class);

    @Override
    public boolean supportsContext(Observation.Context context) {
        return true; // 接收所有类型的 Observation Context
    }

    @Override
    public void onStart(Observation.Context context) {
        log.info("[B03] Observation START: name={}, contextualName={}",
                context.getName(), context.getContextualName());
        for (KeyValue kv : context.getAllKeyValues()) {
            log.info("[B03]   tag: {}={}", kv.getKey(), kv.getValue());
        }
    }

    @Override
    public void onStop(Observation.Context context) {
        log.info("[B03] Observation STOP: name={}, error={}",
                context.getName(), context.getError() != null ? context.getError() : "none");
    }

    @Override
    public void onError(Observation.Context context) {
        log.warn("[B03] Observation ERROR: name={}, error={}",
                context.getName(), context.getError().getMessage());
    }
}
