package com.myxhs.demo.provider;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * L37 故障注入端点：给网关的熔断 / 重试实验提供"可控的失败"。
 *
 * <p>- {@code /status/{code}}：按指定状态码返回（GET/POST 都支持），用于 statusCodes / Retry 实验；
 * <p>- {@code /flaky?fail=2&key=x}：同一个 key 前 fail 次返回 500、之后返回 200（内存计数，重启清零），
 *    用于观察"重试第 N 次成功"。
 */
@RestController
public class FailureController {

    private static final Logger log = LoggerFactory.getLogger(FailureController.class);

    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    @Value("${server.port}")
    private int port;

    @GetMapping("/status/{code}")
    public ResponseEntity<Map<String, Object>> statusGet(@PathVariable int code, HttpServletRequest request) {
        return status(code, request);
    }

    @PostMapping("/status/{code}")
    public ResponseEntity<Map<String, Object>> statusPost(@PathVariable int code, HttpServletRequest request) {
        return status(code, request);
    }

    @GetMapping("/flaky")
    public ResponseEntity<Map<String, Object>> flaky(@RequestParam(defaultValue = "2") int fail,
            @RequestParam(defaultValue = "default") String key,
            HttpServletRequest request) {
        int attempt = counters.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        boolean ok = attempt > fail;
        log.info("[provider] /flaky key={} fail={} attempt={} -> {}", key, fail, attempt, ok ? 200 : 500);
        Map<String, Object> body = base(request);
        body.put("attempt", attempt);
        body.put("ok", ok);
        return ok ? ResponseEntity.ok(body) : ResponseEntity.status(500).body(body);
    }

    /** 独立的重置端点：避免把 reset 放进 /flaky 的 query 里——重试会原样重放 query，每次重试都会重置计数。 */
    @GetMapping("/flaky/reset")
    public Map<String, Object> resetFlaky(@RequestParam(defaultValue = "default") String key) {
        counters.remove(key);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reset", key);
        return body;
    }

    private ResponseEntity<Map<String, Object>> status(int code, HttpServletRequest request) {
        log.info("[provider] /status/{} 收到请求 method={} uri={}", code, request.getMethod(),
                request.getRequestURI());
        Map<String, Object> body = base(request);
        body.put("requestedStatus", code);
        return ResponseEntity.status(code).body(body);
    }

    private Map<String, Object> base(HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("port", port);
        body.put("method", request.getMethod());
        body.put("uri", request.getRequestURI());
        return body;
    }

}
