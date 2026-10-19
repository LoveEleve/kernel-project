package com.scalab.openfeign.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

@FeignClient(name = "nacos-lab-v1", url = "http://localhost:28001")
public interface NacosLabV1Client {

    @GetMapping("/health")
    String health();
}
