package com.scalab.openfeign.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "openfeign-lab-self", url = "http://localhost:28002")
public interface CompressionTestClient {

    @PostMapping("/feign/compression/echo")
    Map<String, Object> echoWithLargeBody(@RequestBody String body);

    @GetMapping("/feign/compression/large-data")
    Map<String, Object> getLargeData();
}
