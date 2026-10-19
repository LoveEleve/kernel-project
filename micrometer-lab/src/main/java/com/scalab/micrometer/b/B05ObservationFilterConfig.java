package com.scalab.micrometer.b;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

/**
 * B-05 自定义 ObservationFilter——对照 D3 §十二 三层过滤对比。
 *
 * 验证点：
 * 1. {@code observationConfig().observationFilter()} 为所有观测追加自定义 KeyValue
 * 2. 自定义 tag 出现在 /actuator/prometheus 的 HTTP 指标中
 * 3. ObservationFilter 在 ObservationPredicate 之后、MeterFilter 之前执行
 */
@Component
public class B05ObservationFilterConfig {

    public B05ObservationFilterConfig(ObservationRegistry registry) {
        // 追加全局 KeyValue——所有 Observation 自动带上 tenant=micrometer-lab
        registry.observationConfig()
                .observationFilter(context -> {
                    context.addLowCardinalityKeyValue(
                            KeyValue.of("b05.tenant", "micrometer-lab"));
                    return context;
                });
    }
}
