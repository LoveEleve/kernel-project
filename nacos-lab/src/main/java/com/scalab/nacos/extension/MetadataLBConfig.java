package com.scalab.nacos.extension;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * B3: 将 MetadataBasedLoadBalancer 绑定到 nacos-lab 服务
 *
 * @LoadBalancerClient 的作用：
 *   告诉 Spring Cloud LoadBalancer ——
 *   "当调用 nacos-lab 服务时，用 MetadataBasedLoadBalancer 而不是默认的 RoundRobin"
 *
 *   其他服务（如 my-xhs-user）仍然用 RoundRobin，不受影响。
 */
@Configuration
@LoadBalancerClient(name = "nacos-lab", configuration = MetadataLBConfig.class)
public class MetadataLBConfig {

    @Bean
    public ReactorServiceInstanceLoadBalancer metadataBasedLoadBalancer(
            ObjectProvider<ServiceInstanceListSupplier> supplierProvider) {
        return new MetadataBasedLoadBalancer(supplierProvider, "nacos-lab");
    }
}
