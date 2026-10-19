package com.scalab.loadbalancer.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * A-08: 提示路由（hint）Supplier 链。
 *
 * <p>为什么需要自定义：D3 5.3 矩阵里<b>没有 hints 行</b>——HintBasedServiceInstanceListSupplier
 * 在 LoadBalancerClientConfiguration 中没有对应 @Bean（configurations=hints 不合法，
 * 会导致无 Supplier 激活、实例列表为空）。
 *
 * <p>用法：@LoadBalancerClient(name="nacos-lab", configuration=HintLbConfig.class)
 * + 启动参数 {@code -Dscalab.lb.hints=true}。
 *
 * <p>请求侧：带 {@code X-SC-LB-Hint: xxx} 头（D2 4.6），实例 metadata.hint 匹配才路由；
 * 无匹配回退全部实例。
 */
public class HintLbConfig {

    @Bean
    @ConditionalOnProperty(name = "scalab.lb.hints", havingValue = "true")
    public ServiceInstanceListSupplier hintServiceInstanceListSupplier(
            ConfigurableApplicationContext context) {
        return ServiceInstanceListSupplier.builder()
                .withBlockingDiscoveryClient()
                .withCaching()
                .withHints()
                .build(context);
    }
}
