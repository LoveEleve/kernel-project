package com.scalab.loadbalancer.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A 类官方功能验证端点。
 *
 * <p>通过 {@code @LoadBalanced RestTemplate / RestClient} 调用 nacos-lab 的
 * {@code /discovery/status}（返回处理请求的实例端口），观察负载均衡分布。
 */
@RestController
@RequestMapping("/lb")
public class LbTestController {

    private static final Logger log = LoggerFactory.getLogger(LbTestController.class);

    @Autowired
    private RestTemplate loadBalancedRestTemplate;

    @Autowired
    private RestClient.Builder loadBalancedRestClientBuilder;

    @Autowired(required = false)
    private DiscoveryClient discoveryClient;

    @Autowired
    private ApplicationContext applicationContext;

    /** A-01/A-02: 多次调用验证轮询/随机分布 */
    @GetMapping("/distribution")
    public Map<String, Object> distribution(@RequestParam(defaultValue = "20") int calls) {
        if (calls < 1 || calls > 1000) {
            throw new IllegalArgumentException("calls must be between 1 and 1000");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("test", "A-01/A-02 负载均衡分布");
        // LoadBalancer 策略由 scalab.lb.strategy 切换（RandomLbConfig），
        // 注意与 spring.cloud.loadbalancer.configurations（Supplier 链）区分
        result.put("strategy", applicationContext.getEnvironment()
                .getProperty("scalab.lb.strategy", "round-robin（默认）"));
        result.put("calls", calls);

        Map<String, Integer> portCount = new TreeMap<>();
        List<Map<String, Object>> details = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            long start = System.currentTimeMillis();
            try {
                Map<String, Object> status = loadBalancedRestTemplate.getForObject(
                        "http://nacos-lab/discovery/status", Map.class);
                Object port = status != null ? status.get("port") : null;
                if (port == null) {
                    port = "unknown";
                }
                portCount.merge(String.valueOf(port), 1, Integer::sum);
                Map<String, Object> detail = new HashMap<>();
                detail.put("call", i + 1);
                detail.put("port", port);
                detail.put("costMs", System.currentTimeMillis() - start);
                details.add(detail);
            } catch (Exception e) {
                Map<String, Object> detail = new HashMap<>();
                detail.put("call", i + 1);
                detail.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                details.add(detail);
            }
        }
        result.put("portDistribution", portCount);
        result.put("details", details);
        return result;
    }

    /** A-03: 实例列表供应（DiscoveryClient 视角） */
    @GetMapping("/instances")
    public Map<String, Object> instances() {
        Map<String, Object> result = new HashMap<>();
        result.put("test", "A-03 实例列表供应");
        result.put("discoveryEnabled", discoveryClient != null);
        if (discoveryClient != null) {
            List<String> services = discoveryClient.getServices();
            result.put("services", services);
            if (services.contains("nacos-lab")) {
                List<Map<String, Object>> instances = discoveryClient.getInstances("nacos-lab")
                        .stream().map(this::toMap).toList();
                result.put("nacosLabInstances", instances);
            }
        }
        return result;
    }

    /** A-17/A-18: 客户端信息（LoadBalancerClient 类型 + RestClient 场景） */
    @GetMapping("/client-info")
    public Map<String, Object> clientInfo() {
        Map<String, Object> result = new HashMap<>();
        result.put("test", "A-17/A-18 客户端");
        result.put("restTemplateIsLoadBalanced",
                !loadBalancedRestTemplate.getInterceptors().isEmpty());

        Map<String, Object> restClientResult = new HashMap<>();
        try {
            Map<String, Object> status = loadBalancedRestClientBuilder.build().get()
                    .uri("http://nacos-lab/discovery/status")
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            restClientResult.put("success", true);
            restClientResult.put("port", status != null ? status.get("port") : "unknown");
        } catch (Exception e) {
            restClientResult.put("success", false);
            restClientResult.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        result.put("restClientCall", restClientResult);
        return result;
    }

    /** A-15: 查看当前 Supplier 链结构（从 nacos-lab 子上下文获取并展开 delegate 链） */
    @GetMapping("/supplier-chain")
    public Map<String, Object> supplierChain() {
        Map<String, Object> result = new HashMap<>();
        result.put("test", "A-15 Supplier 链结构");
        result.put("configurations", applicationContext.getEnvironment()
                .getProperty("spring.cloud.loadbalancer.configurations", "default（未配置）"));

        try {
            org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory factory =
                    applicationContext.getBean(org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory.class);
            org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier supplier =
                    factory.getInstance("nacos-lab", org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier.class);
            if (supplier == null) {
                result.put("supplier", "null（子上下文未创建）");
                return result;
            }
            List<String> chain = new ArrayList<>();
            Object current = supplier;
            while (current instanceof org.springframework.cloud.loadbalancer.core.DelegatingServiceInstanceListSupplier delegating) {
                chain.add(current.getClass().getSimpleName());
                current = delegating.getDelegate();
            }
            chain.add(current.getClass().getSimpleName());
            result.put("chain", chain);
            result.put("chainDepth", chain.size());
        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    private Map<String, Object> toMap(ServiceInstance instance) {
        Map<String, Object> map = new HashMap<>();
        map.put("instanceId", instance.getInstanceId());
        map.put("host", instance.getHost());
        map.put("port", instance.getPort());
        map.put("uri", instance.getUri().toString());
        map.put("metadata", instance.getMetadata());
        return map;
    }

    /**
     * A-12: 重试机制验证。调用 nacos-lab 的 /echo/fail，验证
     * spring.cloud.loadbalancer.retry.* 是否生效（500 状态码触发重试）。
     *
     * <p>{@code failPort} 指定哪个 nacos-lab 实例故障（如 28001），0 表示两个实例都故障。
     * 用法：curl 前先 POST 两个 nacos-lab 实例的 /echo/fail/reset 清空计数器。
     */
    @GetMapping("/retry-test")
    public Map<String, Object> retryTest(
            @RequestParam(defaultValue = "500") int status,
            @RequestParam(defaultValue = "0") int failPort) {
        Map<String, Object> result = new HashMap<>();
        result.put("test", "A-12 重试机制");
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> body = loadBalancedRestTemplate.getForObject(
                    "http://nacos-lab/echo/fail?status=" + status + "&failPort=" + failPort, Map.class);
            result.put("success", true);
            result.put("response", body);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        result.put("costMs", System.currentTimeMillis() - start);
        return result;
    }

    /**
     * A-14: X-Forwarded 头验证。调用 nacos-lab 的 /echo/headers（回显 X- 开头的请求头），
     * 验证 spring.cloud.loadbalancer.x-forwarded.enabled 是否生效
     * （XForwardedHeadersTransformer 注入 X-Forwarded-Host / X-Forwarded-Proto）。
     */
    @GetMapping("/xforwarded-test")
    public Map<String, Object> xForwardedTest() {
        Map<String, Object> result = new HashMap<>();
        result.put("test", "A-14 X-Forwarded 头");
        result.put("xForwardedEnabled", applicationContext.getEnvironment()
                .getProperty("spring.cloud.loadbalancer.x-forwarded.enabled", "false（默认）"));
        Map<String, Object> body = loadBalancedRestTemplate.getForObject(
                "http://nacos-lab/echo/headers", Map.class);
        result.put("response", body);
        return result;
    }

    /**
     * A-13: API 版本选择验证。调用 nacos-lab 的 /discovery/status（返回实例端口），
     * 通过 {@code apiVersion} 参数指定请求的 API 版本，验证
     * {@code spring.cloud.loadbalancer.configurations=api-version} 是否生效
     * （BlockingApiVersionServiceInstanceListSupplier 按实例 metadata.API_VERSION 过滤）。
     *
     * <p>需用 {@code -Dspring.cloud.loadbalancer.configurations=api-version} 启动应用，
     * 且 nacos-lab 两实例的 metadata.API_VERSION 不同（实例1=1.0.0，实例2=2.0.0）。
     *
     * <p><b>版本注记（本 lab 基线 4.1.4）</b>：{@code BlockingApiVersionServiceInstanceListSupplier}
     * 在 spring-cloud-commons 4.1.4 中<b>不存在</b>（首次出现在 v5.0.0-RC1，PR #1582）——
     * 本端点保留作"版本落差"演示；在 4.1.4 下以 api-version 配置启动会失败/回退，
     * 详见 docs/A-类官方功能.md A-13。
     */
    @GetMapping("/apiversion-test")
    public Map<String, Object> apiVersionTest(@RequestParam(required = false) String apiVersion) {
        Map<String, Object> result = new HashMap<>();
        result.put("test", "A-13 API 版本选择");
        result.put("configurations", applicationContext.getEnvironment()
                .getProperty("spring.cloud.loadbalancer.configurations", "default（未配置）"));
        result.put("requestedApiVersion", apiVersion);

        long start = System.currentTimeMillis();
        try {
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            if (apiVersion != null && !apiVersion.isEmpty()) {
                headers.set("X-API-Version", apiVersion);
            }
            org.springframework.http.HttpEntity<Void> entity = new org.springframework.http.HttpEntity<>(headers);
            Map<String, Object> body = loadBalancedRestTemplate.exchange(
                    "http://nacos-lab/discovery/status",
                    org.springframework.http.HttpMethod.GET,
                    entity, Map.class).getBody();
            result.put("success", true);
            if (body != null) {
                Map<String, Object> routed = new HashMap<>();
                routed.put("port", body.get("port"));
                routed.put("metadata", body.get("metadata"));
                result.put("routedInstance", routed);
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        result.put("costMs", System.currentTimeMillis() - start);
        return result;
    }

    /**
     * A-16 补充: 触发超时失败（调用 nacos-lab 的 /echo/slow），验证 failed 指标。
     * RestTemplate readTimeout=3000ms，seconds=5 会触发 SocketTimeoutException
     * (is-a IOException) → BlockingLoadBalancerClient.execute() catch IOException
     * → FAILED 事件 → loadbalancer.requests.failed Timer。
     */
    @GetMapping("/slow-test")
    public Map<String, Object> slowTest(@RequestParam(defaultValue = "5") int seconds) {
        Map<String, Object> result = new HashMap<>();
        result.put("test", "A-16 超时失败");
        long start = System.currentTimeMillis();
        try {
            loadBalancedRestTemplate.getForObject(
                    "http://nacos-lab/echo/slow?seconds=" + seconds, Map.class);
            result.put("success", true);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getClass().getSimpleName());
        }
        result.put("costMs", System.currentTimeMillis() - start);
        return result;
    }
}
