package com.scalab.openfeign.client;

import com.scalab.openfeignlab.extconfig.CustomFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "custom-ext-test", url = "http://localhost:28002",
             configuration = CustomFeignConfiguration.class)
public interface CustomExtensionsTestClient {

    @GetMapping("/feign/custom-ext/echo")
    Map<String, Object> echo();

    @PostMapping("/feign/custom-ext/echo-body")
    Map<String, Object> echoBody(@RequestBody Map<String, Object> body);
}
