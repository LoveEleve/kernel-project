package com.scalab.loadbalancer.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.RandomLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ReactorLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * A-02: 随机负载均衡策略（B 类扩展点示范——自定义 ReactorServiceInstanceLoadBalancer 替换默认）。
 *
 * <p><b>重要认知</b>：{@code spring.cloud.loadbalancer.configurations} 只控制
 * <b>Supplier 链</b>（D3 5.3 矩阵：default/zone-preference/weighted/subset/...），
 * <b>不控制 LoadBalancer 策略</b>。切换 LoadBalancer 需自定义
 * {@link ReactorLoadBalancer} Bean（D3 的 @ConditionalOnMissingBean 覆盖机制）。
 *
 * <p>作为 per-client 配置类使用（D3 分析过：不能放主上下文——PROPERTY_NAME 只在
 * 子上下文存在；且不能带 @Configuration，否则被主上下文扫描成全局配置）：
 * <pre>{@code
 * @LoadBalancerClient(name = "nacos-lab", configuration = RandomLbConfig.class)
 * }</pre>
 *
 * <p>切换：启动参数 {@code -Dscalab.lb.strategy=random}（默认走 RoundRobin）。
 */
public class RandomLbConfig {

    @Bean
    @ConditionalOnProperty(name = "scalab.lb.strategy", havingValue = "random")
    public ReactorLoadBalancer<ServiceInstance> randomLoadBalancer(
            Environment environment, LoadBalancerClientFactory loadBalancerClientFactory) {
        // D3 分析过：子上下文中 loadbalancer.client.name 属性标识当前客户端
        String serviceId = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
        return new RandomLoadBalancer(
                loadBalancerClientFactory.getLazyProvider(serviceId, ServiceInstanceListSupplier.class),
                serviceId);
    }
}
