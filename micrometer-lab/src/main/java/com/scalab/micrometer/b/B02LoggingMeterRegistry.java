package com.scalab.micrometer.b;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;

/**
 * B-02 自定义 MeterRegistry——对照 D1 §二 模板方法模式。
 *
 * 验证点：
 * 1. 继承 {@code MeterRegistry}，实现所有 abstract 模板方法
 * 2. Counter 的 {@code newCounter()} 返回自定义 LoggingCounter——每次 increment 打印日志
 * 3. 其余 Meter 类型用 {@code SimpleMeterRegistry} 的标准实现保持简单
 */
public class B02LoggingMeterRegistry extends MeterRegistry {

    private static final Logger log = LoggerFactory.getLogger(B02LoggingMeterRegistry.class);
    private final SimpleMeterRegistry backing = new SimpleMeterRegistry();

    public B02LoggingMeterRegistry() {
        super(Clock.SYSTEM);
    }

    /** 唯一自定义的模板方法——返回带日志的 Counter */
    @Override
    protected Counter newCounter(Meter.Id id) {
        // 在 backing registry 上创建标准 Counter
        Counter delegate = Counter.builder(id.getName())
                .tags(id.getTagsAsIterable())
                .register(backing);
        return new LoggingCounter(id, delegate);
    }

    /** 其余 8 个模板方法——全部委托给 backing SimpleMeterRegistry */
    @Override
    protected Timer newTimer(Meter.Id id, DistributionStatisticConfig config, PauseDetector pd) {
        return Timer.builder(id.getName()).tags(id.getTagsAsIterable()).register(backing);
    }
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected <T> Gauge newGauge(Meter.Id id, T obj, ToDoubleFunction<T> f) {
        return (Gauge) (Meter) Gauge.builder(id.getName(), obj, (ToDoubleFunction) f)
                .tags(id.getTagsAsIterable()).register(backing);
    }
    @Override
    protected DistributionSummary newDistributionSummary(Meter.Id id, DistributionStatisticConfig config,
            double scale) {
        return DistributionSummary.builder(id.getName()).tags(id.getTagsAsIterable()).register(backing);
    }
    @Override
    protected Meter newMeter(Meter.Id id, Meter.Type type, Iterable<Measurement> measurements) {
        return backing.getMeters().stream()
                .filter(m -> m.getId().equals(id)).findFirst().orElse(null);
    }
    @Override
    protected <T> FunctionTimer newFunctionTimer(Meter.Id id, T obj, ToLongFunction<T> count,
            ToDoubleFunction<T> totalTime, TimeUnit totalTimeUnit) {
        throw new UnsupportedOperationException("FunctionTimer not supported in B02 demo");
    }
    @Override
    protected <T> FunctionCounter newFunctionCounter(Meter.Id id, T obj, ToDoubleFunction<T> count) {
        throw new UnsupportedOperationException("FunctionCounter not supported in B02 demo");
    }
    @Override
    protected LongTaskTimer newLongTaskTimer(Meter.Id id, DistributionStatisticConfig config) {
        throw new UnsupportedOperationException("LongTaskTimer not supported in B02 demo");
    }
    @Override
    protected TimeUnit getBaseTimeUnit() {
        return TimeUnit.SECONDS;
    }
    @Override
    protected DistributionStatisticConfig defaultHistogramConfig() {
        return DistributionStatisticConfig.DEFAULT;
    }

    // ===== LoggingCounter =====

    private static class LoggingCounter implements Counter {
        private final Meter.Id id;
        private final Counter delegate;

        LoggingCounter(Meter.Id id, Counter delegate) {
            this.id = id;
            this.delegate = delegate;
        }

        @Override
        public void increment(double amount) {
            delegate.increment(amount);
            log.info("[B02] Counter '{}' +{} → total: {}", id.getName(), amount, delegate.count());
        }
        @Override
        public double count() { return delegate.count(); }
        @Override
        public Meter.Id getId() { return id; }
    }
}
