package com.scalab.micrometer.b;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * B-01 自定义 MeterBinder——对照 D2 §一 MeterBinder 接口 + D2 Gauge 延迟求值。
 *
 * 验证点：
 * 1. 实现 {@code MeterBinder.bindTo(registry)} 注册自定义指标
 * 2. 使用 Gauge 延迟求值（不是 bindTo 时一次性求值——对照 D2 §六 启动时机陷阱）
 * 3. 应用重启后指标自动重新注册（Spring Boot MeterRegistryPostProcessor 调用 bindTo）
 */
public class B01UptimeBinder implements MeterBinder {

    private final long startTime = System.currentTimeMillis();

    @Override
    public void bindTo(MeterRegistry registry) {
        // Gauge 延迟求值——每次 scrape 时调用 getUptimeSeconds()，不是 bindTo 时一次
        Gauge.builder("b01.app.uptime", this, B01UptimeBinder::getUptimeSeconds)
                .description("B01 应用运行时间（秒）")
                .baseUnit("seconds")
                .register(registry);

        // 第二个 Gauge 证明一个 Binder 可以注册多个指标
        Gauge.builder("b01.app.start_time", this, B01UptimeBinder::getStartTimeSeconds)
                .description("B01 应用启动时间戳")
                .register(registry);
    }

    private double getUptimeSeconds() {
        return (System.currentTimeMillis() - startTime) / 1000.0;
    }

    private double getStartTimeSeconds() {
        return startTime / 1000.0;
    }
}
