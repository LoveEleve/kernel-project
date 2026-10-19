package com.scalab.openfeign.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * A-03 超时测试专用 FeignClient。
 * 用 url 直连 nacos-lab(不走服务发现,不受 nacos-lab 不自动注册的影响)。
 */
@FeignClient(name = "nacos-lab-direct", url = "http://localhost:28001")
public interface NacosLabDirectClient {

    @GetMapping("/echo/slow")
    Map<String, Object> echoSlow(@RequestParam("seconds") int seconds);
}
