package com.myxhs.demo.polygateway.lab.provider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * polygateway 真实环境实验：元数据发布方（Spring MVC + Nacos）。
 *
 * <p>运行后可观察 Nacos 实例 metadata 中的 polygateway.web-endpoints / polygateway.context-path。
 */
@SpringBootApplication
public class LabProviderApplication {

    public static void main(String[] args) {
        SpringApplication.run(LabProviderApplication.class, args);
    }
}
