package com.myxhs.demo.lb;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * L16：把自定义的 {@code ServiceInstanceListSupplier} 装进 SCLB。
 *
 * <p>⚠️ 两个约定（否则不生效/重复注册）：
 * <ol>
 *   <li>本类**不在**网关的组件扫描包（{@code com.myxhs.demo.gateway}）下 —— 通过
 *       {@code @LoadBalancerClient(configuration = ...)} 放进 **LoadBalancer 子上下文**</li>
 *   <li>模式用属性 {@code demo.lb.mode} 切换，重启生效（只为演示；生产按需固化）</li>
 * </ol>
 *
 * <p>装配链：{@code 服务发现 → [灰度过滤 | Zone 偏好] → 缓存 → LoadBalancer 选实例}
 */
@Configuration(proxyBeanMethods = false)
public class DemoLoadBalancerConfiguration {

    @Bean
    public ServiceInstanceListSupplier serviceInstanceListSupplier(
            ConfigurableApplicationContext context,
            @Value("${demo.lb.mode:none}") String mode) {

        // ① 基础：从注册中心拿实例列表
        ServiceInstanceListSupplier discovery = ServiceInstanceListSupplier.builder()
                .withDiscoveryClient()
                .build(context);

        // ② 按模式包一层"选实例"逻辑
        ServiceInstanceListSupplier filtered = switch (mode) {
            case "gray" -> new GrayInstanceListSupplier(discovery, "v2");
            case "zone" -> new ZonePreferredInstanceListSupplier(discovery, "shanghai");
            default -> discovery;
        };

        // ③ 再加缓存（SCLB 建议：缓存放最外层）
        return ServiceInstanceListSupplier.builder()
                .withBase(filtered)
                .withCaching()
                .build(context);
    }
}
