package com.scalab.loadbalancer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.DelegatingServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * A-15: 流式 Builder 高级用法 —— {@code with(DelegateCreator)} 自定义装饰器。
 *
 * <p>现有的 HealthCheckLbConfig / HintLbConfig 只用了 {@code withXxx()} 预设方法，
 * 本 demo 展示 Builder 最灵活的扩展点 {@code with(DelegateCreator)}：在 Supplier 链
 * 中插入一个<b>自定义装饰器</b>（日志记录器），每次 get() 时记录实例列表大小。
 *
 * <p>这是 D2 3.1/3.2 分析的 Creator/DelegateCreator 机制的实际验证：
 * <pre>{@code
 * ServiceInstanceListSupplier.builder()
 *         .withBlockingDiscoveryClient()              // baseCreator（最内层）
 *         .withCaching()                              // DelegateCreator（第1层装饰器）
 *         .with((ctx, delegate) ->                    // DelegateCreator（第2层装饰器，自定义）
 *             new LoggingServiceInstanceListSupplier(delegate))
 *         .build(context);
 * }</pre>
 *
 * <p>用法：启动参数 {@code -Dscalab.lb.builder-demo=true}，然后用
 * {@code GET /lb/supplier-chain} 查看链结构（应含 LoggingServiceInstanceListSupplier）。
 *
 * <p>这也是 B-02（自定义 ServiceInstanceListSupplier）的预热。
 */
public class BuilderDemoLbConfig {

    @Bean
    @ConditionalOnProperty(name = "scalab.lb.builder-demo", havingValue = "true")
    public ServiceInstanceListSupplier builderDemoServiceInstanceListSupplier(
            ConfigurableApplicationContext context) {
        return ServiceInstanceListSupplier.builder()
                .withBlockingDiscoveryClient()
                .withCaching()
                // with(DelegateCreator) 是 Builder 最灵活的扩展点：
                // 接收 (context, delegate) → 返回包装后的新 Supplier
                .with((ctx, delegate) ->
                        new LoggingServiceInstanceListSupplier(delegate))
                .build(context);
    }

    /**
     * 自定义装饰器：在 delegate 外包一层日志，记录每次 get() 返回的实例数量。
     *
     * <p>继承 {@link DelegatingServiceInstanceListSupplier}（D2 4.x 分析过的装饰器基类），
     * 只覆盖 {@code get()} 和 {@code get(Request)}，其余行为透传给 delegate。
     */
    static class LoggingServiceInstanceListSupplier extends DelegatingServiceInstanceListSupplier {

        private static final Logger log = LoggerFactory.getLogger(LoggingServiceInstanceListSupplier.class);

        LoggingServiceInstanceListSupplier(ServiceInstanceListSupplier delegate) {
            super(delegate);
        }

        @Override
        public Flux<List<ServiceInstance>> get() {
            return getDelegate().get().doOnNext(instances ->
                    log.info("[BuilderDemo] get() returned {} instances for service '{}'",
                            instances.size(), getServiceId()));
        }
    }
}
