package com.scalab.openfeign.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * Feign 客户端,通过 Nacos 服务发现调用 nacos-lab。
 *
 * name="nacos-lab" 对应 nacos-lab 的 spring.application.name。
 * 通过 Nacos 服务发现获取 nacos-lab 的实例地址,走 LoadBalancer 选择实例。
 */
@FeignClient(name = "nacos-lab")
public interface NacosLabClient {

    @GetMapping("/health")
    String health();

    @GetMapping("/discovery/status")
    Map<String, Object> discoveryStatus();

    @GetMapping("/discovery/services")
    String discoveryServices();

    @GetMapping("/discovery/cache/info")
    String cacheInfo();

    @GetMapping("/config/current")
    String configCurrent();

    @GetMapping("/config/history")
    Map<String, Object> configHistory();

    @PostMapping("/discovery/set-unhealthy")
    Map<String, Object> setUnhealthy();

    @PostMapping("/discovery/set-healthy")
    Map<String, Object> setHealthy();

    @GetMapping("/lb/instances")
    String lbInstances();

    /** A-02: 验证拦截器 -- 调 nacos-lab 回显 Header */
    @GetMapping("/echo/headers")
    Map<String, Object> echoHeaders();

    /** A-03: 慢请求模拟(超时测试) */
    @GetMapping("/echo/slow")
    Map<String, Object> echoSlow(@RequestParam("seconds") int seconds);
}
