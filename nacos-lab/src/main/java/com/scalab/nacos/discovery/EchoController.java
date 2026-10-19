package com.scalab.nacos.discovery;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * openfeign-lab / loadbalancer-lab 测试辅助端点。
 */
@RestController
public class EchoController {

    @Value("${server.port}")
    private int serverPort;

    /** A-12: 重试测试用调用计数器（每个实例独立，JVM 内存态，仅用于观测） */
    private final AtomicInteger attemptCounter = new AtomicInteger(0);

    @GetMapping("/echo/headers")
    public Map<String, Object> echoHeaders(HttpServletRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method", request.getMethod());
        result.put("path", request.getRequestURI());

        // 回显关键 Header
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            String value = request.getHeader(name);
            if (name.startsWith("X-") || name.startsWith("x-") || name.equals("content-type")) {
                headers.put(name, value);
            }
        }
        result.put("headers", headers);
        return result;
    }

    /** A-03: 慢请求模拟,用于 Feign 超时测试 */
    @GetMapping("/echo/slow")
    public Map<String, Object> slow(@RequestParam(defaultValue = "5") int seconds) throws Exception {
        long start = System.currentTimeMillis();
        Thread.sleep(seconds * 1000L);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method", "GET");
        result.put("path", "/echo/slow");
        result.put("sleepSeconds", seconds);
        result.put("actualCostMs", System.currentTimeMillis() - start);
        return result;
    }

    /**
     * A-12: 重试测试用故障模拟。指定 {@code failPort} 的实例始终返回 {@code status}
     * 状态码，其余实例正常返回 200。用于确定性地验证 Spring Cloud LoadBalancer
     * 的 {@code spring.cloud.loadbalancer.retry.*} 重试机制（故障实例→重试→
     * 换到健康实例→成功）。
     *
     * <p>{@code failPort=0}（默认）表示所有实例都失败，用于验证"重试耗尽仍失败"场景。
     */
    @GetMapping("/echo/fail")
    public ResponseEntity<Map<String, Object>> fail(
            @RequestParam(defaultValue = "500") int status,
            @RequestParam(defaultValue = "0") int failPort) {
        int attempt = attemptCounter.incrementAndGet();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attempt", attempt);
        body.put("port", serverPort);
        if (failPort == 0 || failPort == serverPort) {
            body.put("result", "fail");
            return ResponseEntity.status(status).body(body);
        }
        body.put("result", "success");
        return ResponseEntity.ok(body);
    }

    /** A-12: 重置调用计数器 */
    @PostMapping("/echo/fail/reset")
    public Map<String, Object> resetFail() {
        attemptCounter.set(0);
        return Map.of("result", "reset");
    }
}
