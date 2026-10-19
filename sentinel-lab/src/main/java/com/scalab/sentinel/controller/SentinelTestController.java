package com.scalab.sentinel.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Sentinel 验证端点。
 *
 * <p>核心验证路径：
 * <pre>
 * curl (并发) → sentinel-lab:28011/sentinel/api
 *     → [Sentinel FlowRule QPS=1 限流] → 触发 BlockException → fallback
 *     → [Sentinel DegradeRule 熔断] → 触发 BlockException → fallback
 *     → [通过] → RestTemplate 调用 nacos-lab/discovery/status → 返回下游端口
 * </pre>
 */
@RestController
public class SentinelTestController {

    private static final Logger log = LoggerFactory.getLogger(SentinelTestController.class);

    @Autowired
    private RestTemplate restTemplate;

    /**
     * 受 Sentinel 保护的测试接口。
     *
     * <p>{@link SentinelResource} 注解：
     * <ul>
     *   <li>{@code value} 定义资源名（Sentinel 规则匹配的依据）</li>
     *   <li>{@code blockHandler} 指定流控/熔断触发后的 fallback 方法</li>
     *   <li>{@code fallback} 指定业务异常后的 fallback（不设，区分 block vs error）</li>
     * </ul>
     */
    @GetMapping("/sentinel/api")
    @SentinelResource(value = "sentinelApi", blockHandler = "handleBlock")
    public Map<String, Object> api(
            @RequestParam(defaultValue = "normal") String key,
            @RequestParam(defaultValue = "0") int sleepMs) {
        if (sleepMs > 0) {
            try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("source", "sentinel-lab");
        // 调用 nacos-lab 作为下游
        Map<?, ?> downstream = restTemplate.getForObject(
                "http://localhost:28001/discovery/status", Map.class);
        result.put("downstream", downstream);
        return result;
    }

    /** A-02 熔断验证：手动管理 entry 生命周期 + exit 前重新绑定 */
    @GetMapping("/sentinel/error")
    public Map<String, Object> error() throws Throwable {
        com.alibaba.csp.sentinel.Entry entry = null;
        try {
            entry = com.alibaba.csp.sentinel.SphU.entry("sentinelApi");
            throw new RuntimeException("simulated error for degrade testing");
        } catch (com.alibaba.csp.sentinel.slots.block.BlockException e) {
            Map<String, Object> r = new HashMap<>();
            r.put("blocked", true); r.put("rule", e.getRule().getResource());
            return r;
        } catch (Throwable e) {
            // 熔断由 Sentinel DegradeSlot 负责（ApplicationRunner 已加载 sentinelApi ERROR_COUNT=1 规则）
            throw e;
        } finally {
            if (entry != null) { entry.exit(); }
        }
    }

    /**
     * A-03 降级验证：fallback（业务异常） + blockHandler（限流熔断）分工。
     *
     * <p>blockHandler 处理 BlockException（限流/熔断），fallback 处理业务异常。
     * 两者独立——被限流时走 blockHandler，业务抛异常时走 fallback。
     */
    @GetMapping("/sentinel/fallback")
    @SentinelResource(value = "sentinelApi", blockHandler = "handleBlock", fallback = "handleFallback")
    public Map<String, Object> fallbackTest(@RequestParam(defaultValue = "false") boolean error) {
        if (error) {
            throw new RuntimeException("business logic failed");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("source", "sentinel-lab");
        result.put("status", "ok");
        return result;
    }

    /** fallback 处理方法——业务异常时降级返回友好 JSON（而非 500） */
    public Map<String, Object> handleFallback(boolean error, Throwable ex) {
        Map<String, Object> result = new HashMap<>();
        result.put("degraded", true);
        result.put("reason", ex.getClass().getSimpleName());
        result.put("message", ex.getMessage());
        return result;
    }

    /**
     * A-06 授权验证：通过 X-Caller header 设置 origin（由 RequestOriginParser 解析），
     * 触发 AuthorityRule 黑名单拦截。
     *
     * <p>不能再手动 ContextUtil.enter——Spring MVC 请求线程中 Sentinel 已在
     * SphU.entry 前创建了 Context，Controller 体内的 enter 无法覆盖。
     */
    @GetMapping("/sentinel/auth")
    @SentinelResource(value = "sentinelApi", blockHandler = "handleAuthBlock")
    public Map<String, Object> authTest() {
        Map<String, Object> result = new HashMap<>();
        result.put("source", "sentinel-lab");
        result.put("status", "ok");
        return result;
    }

    public Map<String, Object> handleAuthBlock(BlockException ex) {
        Map<String, Object> result = new HashMap<>();
        result.put("blocked", true);
        result.put("reason", ex.getClass().getSimpleName());
        return result;
    }

    private Map<String, Object> handleBlock(com.alibaba.csp.sentinel.slots.block.BlockException ex) {
        Map<String, Object> result = new HashMap<>();
        result.put("blocked", true);
        result.put("rule", ex.getRule().getResource());
        result.put("reason", ex.getClass().getSimpleName());
        return result;
    }

    /** 流控 / 熔断触发后的 fallback 处理方法 */
    public Map<String, Object> handleBlock(String key, int sleepMs, BlockException ex) {
        Map<String, Object> result = new HashMap<>();
        result.put("blocked", true);
        result.put("rule", ex.getRule().getResource());
        result.put("reason", ex.getClass().getSimpleName());
        log.info("[Sentinel] 被限流/熔断: rule={}, reason={}", ex.getRule().getResource(), ex.getClass().getSimpleName());
        return result;
    }

    /** 健康检查 */
    @GetMapping("/sentinel/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "sentinel-lab is running");
        result.put("nacosLabHealthy", false);
        try {
            restTemplate.getForObject("http://localhost:28001/health", String.class);
            result.put("nacosLabHealthy", true);
        } catch (Exception ignored) {}
        return result;
    }

    /** B-04 验证：不用 @SentinelResource，触发全局自定义 BlockExceptionHandler */
    @GetMapping("/sentinel/global")
    public Map<String, Object> globalBlockTest(@RequestParam(defaultValue = "normal") String key)
            throws com.alibaba.csp.sentinel.slots.block.BlockException {
        com.alibaba.csp.sentinel.Entry entry = null;
        try {
            entry = com.alibaba.csp.sentinel.SphU.entry("sentinelApi");
            Map<String, Object> result = new HashMap<>();
            result.put("source", "sentinel-lab");
            result.put("status", "ok");
            return result;
        } finally {
            if (entry != null) { entry.exit(); }
        }
    }
}
