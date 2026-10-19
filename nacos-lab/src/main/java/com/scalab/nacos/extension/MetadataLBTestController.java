package com.scalab.nacos.extension;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * B3 验证：查看 Nacos 实例 metadata，验证 LoadBalancer 配置
 */
@RestController
public class MetadataLBTestController {

    @Autowired(required = false)
    private DiscoveryClient discoveryClient;

    /**
     * 查看 nacos-lab 的所有实例及其 metadata
     */
    @GetMapping("/lb/instances")
    public Map<String, Object> instances() {
        Map<String, Object> result = new HashMap<>();
        if (discoveryClient != null) {
            List<ServiceInstance> instances = discoveryClient.getInstances("nacos-lab");
            result.put("count", instances.size());
            result.put("instances", instances.stream().map(instance -> {
                Map<String, Object> info = new HashMap<>();
                info.put("host", instance.getHost());
                info.put("port", instance.getPort());
                info.put("metadata", instance.getMetadata());
                info.put("serviceId", instance.getServiceId());
                return info;
            }).collect(Collectors.toList()));
        }
        result.put("lbConfig", "MetadataBasedLoadBalancer (bound via @LoadBalancerClient)");
        return result;
    }
}
