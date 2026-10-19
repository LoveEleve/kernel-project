package com.scalab.openfeign.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Map;

@FeignClient(name = "sentinel-test", url = "http://localhost:28002",
             fallbackFactory = SentinelTestClientFallbackFactory.class)
public interface SentinelTestClient {

    @GetMapping("/feign/sentinel/ping")
    Map<String, Object> ping();
}
