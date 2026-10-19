package com.scalab.micrometer.b;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * B-04 自定义 MeterFilter——对照 D6 §三 准入判定 + D6 §二 DENY/NEUTRAL/ACCEPT。
 *
 * 验证点：
 * 1. {@code MeterFilter.deny()}——拒绝匹配的指标（如 jvm.* 全部排除）
 * 2. {@code MeterFilter.accept()}——提前放行保护关键指标
 * 3. MeterFilter 注册到全局 {@code MeterRegistry}（影响所有后续创建的 Meter）
 * 4. Filter 在应用启动后注册——已创建的 JVM 指标不受影响（对照 D6 §七 stale 标记）
 */
@Component
public class B04MeterFilterConfig {

    @EventListener(ApplicationReadyEvent.class)
    public void registerFilters(ApplicationReadyEvent event) {
        MeterRegistry registry = event.getApplicationContext().getBean(MeterRegistry.class);

        // 拒绝所有 actuator 健康检查的指标——降低噪音
        registry.config().meterFilter(
                MeterFilter.deny(id -> id.getName().startsWith("spring.security")));

        // 提前放行我们自己的 b01 指标——即使后面有 denyAll，b01 也被保护
        registry.config().meterFilter(
                MeterFilter.accept(id -> id.getName().startsWith("b01.app")));

        // 注意：Filter 按注册顺序执行——对照 D6 Q5
        // accept(b01) 在 deny(spring.security) 之后注册，但各自处理不同 prefix，无冲突
    }
}
