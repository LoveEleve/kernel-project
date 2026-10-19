package com.scalab.openfeign.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Map;

@FeignClient(name = "nacos-lab-v2", url = "http://localhost:28001")
public interface NacosLabV2Client {

    @GetMapping("/discovery/status")
    Map<String, Object> discoveryStatus();
}
