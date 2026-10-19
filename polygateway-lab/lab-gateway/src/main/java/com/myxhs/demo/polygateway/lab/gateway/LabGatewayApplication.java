package com.myxhs.demo.polygateway.lab.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * polygateway 真实环境实验：网关侧（SCG WebFlux + Nacos）。
 *
 * <p>通过 {@code /actuator/gateway/routes} 可观察生成的 polygateway:* 端点级路由。
 */
@SpringBootApplication
public class LabGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(LabGatewayApplication.class, args);
    }
}
