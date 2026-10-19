package com.scalab.loadbalancer.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * A-05: 健康检查 Supplier 链（自定义配置类）。
 *
 * <p>为什么需要自定义：默认的 {@code configurations=health-check} 走
 * {@code withBlockingHealthChecks()}（内部 {@code context.getBean(RestTemplate.class)}），
 * loadbalancer-lab 的 RestTemplate 是 @LoadBalanced——健康检查请求发往实例 IP 时被
 * LoadBalancerInterceptor 拦截（把 IP 当服务名二次 LB）→ 死循环 → 全部实例被过滤
 * （"No servers available for service: 21.214.97.212"）。
 *
 * <p>修复：用 {@code withBlockingRestClientHealthChecks(RestClient)} 重载
 * （D3 的 Builder 方法），显式传入非负载均衡的 plainRestClient。
 *
 * <p>用法：@LoadBalancerClient(name="nacos-lab", configuration=HealthCheckLbConfig.class)
 * + 启动参数 {@code -Dscalab.lb.health-check=true}。
 */
public class HealthCheckLbConfig {

    @Bean
    @ConditionalOnProperty(name = "scalab.lb.health-check", havingValue = "true")
    public ServiceInstanceListSupplier healthCheckServiceInstanceListSupplier(
            ConfigurableApplicationContext context, RestClient plainRestClient) {
        return ServiceInstanceListSupplier.builder()
                .withBlockingDiscoveryClient()
                .withBlockingHealthChecks(plainRestClient)   // 非 LB 客户端做健康探测（RestClient 重载）
                .build(context);
    }
}
