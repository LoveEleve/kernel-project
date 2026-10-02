package com.myxhs.demo.gateway;

import com.myxhs.demo.lb.DemoLoadBalancerConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;

/**
 * 网关主类。
 *
 * <p>★ L16：{@code @LoadBalancerClient} 把自定义负载均衡配置挂到 **demo-provider 这个服务**上
 * （配置类放在 {@code com.myxhs.demo.lb}，**不在**组件扫描包内 —— SCLB 的约定）。
 */
@SpringBootApplication
@LoadBalancerClient(name = "demo-provider", configuration = DemoLoadBalancerConfiguration.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
