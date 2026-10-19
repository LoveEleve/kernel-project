package com.scalab.openfeign.controller;

import com.scalab.openfeign.client.CompressionTestClient;
import com.scalab.openfeign.client.CustomExtensionsTestClient;
import com.scalab.openfeign.client.NacosLabClient;
import com.scalab.openfeign.client.NacosLabDirectClient;
import com.scalab.openfeign.client.NacosLabV1Client;
import com.scalab.openfeign.client.NacosLabV2Client;
import com.scalab.openfeign.client.SentinelTestClient;
import com.scalab.openfeign.restclient.RestClientExample;
import com.scalab.openfeign.interceptor.VersionInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

@RestController
public class FeignTestController {

    private static final Logger log = LoggerFactory.getLogger(FeignTestController.class);

    @Autowired
    private NacosLabClient nacosLabClient;

    @Autowired(required = false)
    private NacosLabDirectClient nacosLabDirectClient;

    @Autowired(required = false)
    private DiscoveryClient discoveryClient;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired(required = false)
    private CompressionTestClient compressionTestClient;

    @Autowired(required = false)
    private SentinelTestClient sentinelTestClient;

    @Autowired(required = false)
    private NacosLabV1Client nacosLabV1Client;

    @Autowired(required = false)
    private NacosLabV2Client nacosLabV2Client;

    @Autowired
    private RestClientExample restClientExample;

    @Autowired(required = false)
    private CustomExtensionsTestClient customExtensionsTestClient;

    private volatile boolean sentinelTestHealthy = true;

    /** 场景 A1: 基本 @FeignClient 声明式调用 */
    @GetMapping("/feign/health")
    public Map<String, Object> testHealth() {
        Map<String, Object> result = new HashMap<>();
        result.put("test", "A1-声明式调用");
        long start = System.currentTimeMillis();
        try {
            String health = nacosLabClient.health();
            result.put("success", true);
            result.put("costMs", System.currentTimeMillis() - start);
            result.put("response", health);
        } catch (Exception e) {
            result.put("success", false);
            result.put("costMs", System.currentTimeMillis() - start);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    /** 场景 A1: 查看 nacos-lab 完整状态 */
    @GetMapping("/feign/discovery-status")
    public Map<String, Object> testDiscoveryStatus() {
        Map<String, Object> result = new HashMap<>();
        result.put("test", "A1-服务发现查询");
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> status = nacosLabClient.discoveryStatus();
            result.put("success", true);
            result.put("costMs", System.currentTimeMillis() - start);
            result.put("response", status);
        } catch (Exception e) {
            result.put("success", false);
            result.put("costMs", System.currentTimeMillis() - start);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    /** 场景 A2: 拦截器测试(验证 X-Trace-Id  Header 透传) */
    @GetMapping("/feign/interceptor-test")
    public Map<String, Object> testInterceptor() {
        Map<String, Object> result = new HashMap<>();
        result.put("test", "A2-请求拦截器");

        // 调用 nacos-lab 的 /echo/headers; Feign 请求时会被 TraceIdInterceptor 拦截,
        // 自动注入 X-Trace-Id 和 X-Source-App Header
        try {
            Map<String, Object> echo = nacosLabClient.echoHeaders();
            result.put("success", true);
            result.put("echoResponse", echo);

            // 从响应中提取 Header 值,验证拦截器是否生效
            // 注意: HTTP Header 名大小写不敏感,Tomcat 返回小写
            String receivedTraceId = null;
            String receivedSourceApp = null;
            if (echo != null && echo.get("headers") instanceof Map) {
                Map<String, Object> headers = (Map<String, Object>) echo.get("headers");
                for (Map.Entry<String, Object> e : headers.entrySet()) {
                    String key = e.getKey().toLowerCase();
                    if (key.equals("x-trace-id")) {
                        receivedTraceId = String.valueOf(e.getValue());
                    }
                    if (key.equals("x-source-app")) {
                        receivedSourceApp = String.valueOf(e.getValue());
                    }
                }
            }

            result.put("interceptorVerified", receivedTraceId != null && !receivedTraceId.isEmpty());
            result.put("receivedTraceId", receivedTraceId);
            result.put("receivedSourceApp", receivedSourceApp);
            result.put("note", "如果 interceptorVerified=true,说明 TraceIdInterceptor 成功注入了 Header ");
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    /** 场景 A3: 超时测试(用 NacosLabDirectClient, url 直连 nacos-lab) */
    @GetMapping("/feign/timeout-test")
    public Map<String, Object> testTimeout(@RequestParam(defaultValue = "3") int seconds) {
        Map<String, Object> result = new HashMap<>();
        result.put("test", "A3-超时测试");
        result.put("client", "nacos-lab-direct");
        result.put("target", "/echo/slow?seconds=" + seconds);
        result.put("readTimeout", "2000ms");
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> response = nacosLabDirectClient.echoSlow(seconds);
            result.put("success", true);
            result.put("costMs", System.currentTimeMillis() - start);
            result.put("responseSleepSeconds", response.get("sleepSeconds"));
            result.put("responseActualCostMs", response.get("actualCostMs"));
        } catch (Exception e) {
            result.put("success", false);
            result.put("costMs", System.currentTimeMillis() - start);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    /** A-06: Sentinel 熔断测试(自包含,不依赖 nacos-lab) */
    @PostMapping("/feign/circuit-breaker-test")
    public Map<String, Object> testCircuitBreaker() {
        Map<String, Object> result = new HashMap<>();
        result.put("test", "A6-熔断测试(Sentinel)");
        if (sentinelTestClient == null) {
            result.put("error", "SentinelTestClient not available (feign.sentinel.enabled missing?)");
            return result;
        }
        sentinelTestHealthy = true;
        Map<String, Object> healthyResp = sentinelTestClient.ping();
        result.put("healthyPing", healthyResp);
        sentinelTestHealthy = false;
        List<Map<String, Object>> fallbackCalls = new ArrayList<>();
        long totalStart = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            long start = System.currentTimeMillis();
            Map<String, Object> resp = sentinelTestClient.ping();
            resp.put("callIndex", i + 1);
            resp.put("costMs", System.currentTimeMillis() - start);
            fallbackCalls.add(resp);
        }
        result.put("callCount", 10);
        result.put("fallbackCount", fallbackCalls.stream().filter(r -> Boolean.TRUE.equals(r.get("fallback"))).count());
        result.put("normalCount", fallbackCalls.stream().filter(r -> !Boolean.TRUE.equals(r.get("fallback"))).count());
        result.put("calls", fallbackCalls);
        result.put("totalCostMs", System.currentTimeMillis() - totalStart);
        result.put("note", "SentinelInvocationHandler 拦截失败的调用,通过 FallbackFactory 返回降级响应");
        sentinelTestHealthy = true;
        return result;
    }

    /** A-06: Sentinel 测试端点 — 可切换健康/故障 */
    @GetMapping("/feign/sentinel/ping")
    public Map<String, Object> sentinelPing() {
        if (!sentinelTestHealthy) {
            throw new RuntimeException("模拟 Sentinel 熔断故障: sentinel-test service is unhealthy");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("status", "healthy");
        result.put("message", "pong");
        return result;
    }

    /** A-06: 切换 Sentinel 测试端点的健康状态 */
    @PostMapping("/feign/sentinel/toggle")
    public Map<String, Object> toggleSentinelTest(@RequestParam boolean healthy) {
        sentinelTestHealthy = healthy;
        Map<String, Object> result = new HashMap<>();
        result.put("healthy", healthy);
        result.put("note", healthy ? "恢复正常" : "已切换为故障模式,后续 Feign 调用将触发 fallback");
        return result;
    }

    /** A-06: 完整 Sentinel 熔断测试 */
    @GetMapping("/feign/sentinel-test")
    public Map<String, Object> testSentinel() {
        Map<String, Object> result = new HashMap<>();
        result.put("test", "A6-完整熔断测试");
        if (sentinelTestClient == null) {
            result.put("error", "SentinelTestClient not available");
            return result;
        }
        sentinelTestHealthy = true;
        long start = System.currentTimeMillis();
        Map<String, Object> healthyResp = sentinelTestClient.ping();
        result.put("healthyCallSuccess", !Boolean.TRUE.equals(healthyResp.get("fallback")));
        result.put("healthyCall", healthyResp);
        result.put("healthyCallCostMs", System.currentTimeMillis() - start);
        sentinelTestHealthy = false;
        start = System.currentTimeMillis();
        Map<String, Object> fallbackResp = sentinelTestClient.ping();
        result.put("fallbackTriggered", Boolean.TRUE.equals(fallbackResp.get("fallback")));
        result.put("fallbackCall", fallbackResp);
        result.put("fallbackCallCostMs", System.currentTimeMillis() - start);
        sentinelTestHealthy = true;
        return result;
    }

    /** 恢复 nacos-lab 健康 */
    @PostMapping("/feign/recover")
    public Map<String, Object> recover() {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> resp = nacosLabClient.setHealthy();
            result.put("success", true);
            result.put("response", resp);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    /** A-08: 多版本 API — 用不同 Feign 接口调用不同版本的 API */
    @GetMapping("/feign/multi-version")
    public Map<String, Object> testMultiVersion(@RequestParam(defaultValue = "v1") String version) {
        Map<String, Object> result = new HashMap<>();
        result.put("test", "A8-多版本API");
        result.put("version", version);
        VersionInterceptor.setVersion(version);
        long start = System.currentTimeMillis();
        try {
            if ("v1".equals(version)) {
                if (nacosLabV1Client == null) {
                    throw new IllegalStateException("NacosLabV1Client not available");
                }
                String resp = nacosLabV1Client.health();
                result.put("client", "NacosLabV1Client");
                result.put("endpoint", "/health");
                result.put("response", resp);
            } else {
                if (nacosLabV2Client == null) {
                    throw new IllegalStateException("NacosLabV2Client not available");
                }
                Map<String, Object> status = nacosLabV2Client.discoveryStatus();
                result.put("client", "NacosLabV2Client");
                result.put("endpoint", "/discovery/status");
                result.put("response", status);
            }
            result.put("success", true);
            result.put("costMs", System.currentTimeMillis() - start);
        } catch (Exception e) {
            result.put("success", false);
            result.put("costMs", System.currentTimeMillis() - start);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            VersionInterceptor.clear();
        }
        return result;
    }

    /** A-08: 多版本 API 对比测试 — V1 vs V2 不同接口/响应格式 */
    @GetMapping("/feign/multi-version-test")
    public Map<String, Object> testMultiVersionFull() {
        Map<String, Object> result = new HashMap<>();
        result.put("test", "A8-多版本API-完整测试");
        Map<String, Object> v1 = new HashMap<>();
        try {
            if (nacosLabV1Client == null) {
                throw new IllegalStateException("NacosLabV1Client not available");
            }
            long start = System.currentTimeMillis();
            String health = nacosLabV1Client.health();
            v1.put("success", true);
            v1.put("costMs", System.currentTimeMillis() - start);
            v1.put("responseType", "String");
            v1.put("response", health);
            v1.put("clientInterface", "NacosLabV1Client");
            v1.put("endpoint", "GET /health");
        } catch (Exception e) {
            v1.put("success", false);
            v1.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        result.put("v1", v1);
        Map<String, Object> v2 = new HashMap<>();
        try {
            if (nacosLabV2Client == null) {
                throw new IllegalStateException("NacosLabV2Client not available");
            }
            long start = System.currentTimeMillis();
            Map<String, Object> status = nacosLabV2Client.discoveryStatus();
            v2.put("success", true);
            v2.put("costMs", System.currentTimeMillis() - start);
            v2.put("responseType", "Map<String, Object>");
            v2.put("response", status);
            v2.put("clientInterface", "NacosLabV2Client");
            v2.put("endpoint", "GET /discovery/status");
        } catch (Exception e) {
            v2.put("success", false);
            v2.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        result.put("v2", v2);
        result.put("note", "V1: NacosLabV1Client → /health → String; "
                + "V2: NacosLabV2Client → /discovery/status → Map");
        return result;
    }

    /** 获取 Feign + 服务发现状态 */
    @GetMapping("/feign/client-info")
    public Map<String, Object> clientInfo() {
        Map<String, Object> result = new HashMap<>();
        result.put("test", "Feign 客户端状态");
        result.put("clientInterface", "NacosLabClient");
        result.put("discoveryEnabled", discoveryClient != null);

        if (discoveryClient != null) {
            List<String> services = discoveryClient.getServices();
            result.put("discoveredServices", services);
            if (services.contains("nacos-lab")) {
                result.put("nacosLabInstances",
                        discoveryClient.getInstances("nacos-lab").size());
            }
        }
        return result;
    }

    /** A-04: HTTP 客户端切换信息 */
    @GetMapping("/feign/http-client-info")
    public Map<String, Object> httpClientInfo() {
        Map<String, Object> result = new HashMap<>();
        result.put("test", "A4-HTTP客户端切换");

        // 检测 Feign 实际使用的底层 HTTP 客户端
        // 方式1: 检查 CloseableHttpClient bean 是否存在(HC5 创建的)
        boolean hc5Active = false;
        try {
            applicationContext.getBean(org.apache.hc.client5.http.impl.classic.CloseableHttpClient.class);
            hc5Active = true;
        } catch (Exception ignored) {}

        if (hc5Active) {
            result.put("activeClient", "Apache HttpClient5");
            result.put("note", "由 FeignAutoConfiguration 自动创建(spring.cloud.openfeign.httpclient.hc5.enabled=true)");
        } else {
            result.put("activeClient", "HttpURLConnection(默认)");
        }

        // 显示配置状态
        result.put("hc5Enabled", applicationContext.getEnvironment()
                .getProperty("spring.cloud.openfeign.httpclient.hc5.enabled", "true"));

        // 连通性测试
        try {
            nacosLabDirectClient.echoSlow(0);
            result.put("connectivityTest", "ok");
        } catch (Exception e) {
            result.put("connectivityTest", "fail");
            result.put("error", e.getMessage());
        }
        return result;
    }

    /** A-05: Echo endpoint — receives raw body bytes, reports Content-Encoding and verifies gzip */
    @PostMapping("/feign/compression/echo")
    public Map<String, Object> compressionEcho(
            @RequestHeader(value = "Content-Encoding", required = false) String contentEncoding,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            @RequestBody byte[] body) {
        Map<String, Object> result = new HashMap<>();
        result.put("contentEncoding", contentEncoding);
        result.put("contentType", contentType);
        result.put("receivedBytes", body.length);
        if ("gzip".equals(contentEncoding)) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(body))) {
                byte[] decompressed = gzip.readAllBytes();
                result.put("bodyActuallyCompressed", true);
                result.put("decompressedLength", decompressed.length);
                result.put("decompressedPreview",
                        new String(decompressed, 0, Math.min(100, decompressed.length), StandardCharsets.UTF_8));
            } catch (Exception e) {
                result.put("bodyActuallyCompressed", false);
                result.put("decompressError", e.getClass().getSimpleName() + ": " + e.getMessage());
                result.put("decompressNote", "FeignContentGzipEncodingInterceptor 仅设置 Content-Encoding: gzip 头,"
                        + "不压缩请求体;实际压缩依赖 HTTP 客户端");
            }
        }
        return result;
    }

    /** A-05: Large data response (triggers server response compression) */
    @GetMapping("/feign/compression/large-data")
    public Map<String, Object> largeData() {
        Map<String, Object> data = new HashMap<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("这是一段用于测试响应压缩的长文本。Spring Boot 会在返回时自动使用 Gzip 压缩此响应。")
              .append("The quick brown fox jumps over the lazy dog. ").append(i).append("\n");
        }
        data.put("longText", sb.toString());
        data.put("length", sb.length());
        data.put("description", "此响应应被服务器压缩(server.compression.enabled=true),客户端自动解压");
        return data;
    }

    /** A-05: Full compression test (request header + response) */
    @GetMapping("/feign/compression-test")
    public Map<String, Object> testCompression() {
        Map<String, Object> result = new HashMap<>();
        result.put("test", "A5-压缩与序列化");
        if (compressionTestClient == null) {
            result.put("error", "CompressionTestClient not available");
            return result;
        }
        StringBuilder largeBody = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            largeBody.append("{\"key\":\"").append(i)
                     .append("\",\"value\":\"test-data-for-compression-")
                     .append(i).append("\"},");
        }
        String payload = "[" + largeBody.toString() + "]";
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> echoResult = compressionTestClient.echoWithLargeBody(payload);
            boolean headerSet = "gzip".equals(echoResult.get("contentEncoding"));
            Boolean bodyCompressed = (Boolean) echoResult.get("bodyActuallyCompressed");
            result.put("requestCompressionHeaderSet", headerSet);
            result.put("requestBodyActuallyCompressed", bodyCompressed);
            result.put("requestOriginalSize", payload.length());
            result.put("requestReceivedSize", echoResult.get("receivedBytes"));
            result.put("requestEcho", echoResult);
            result.put("requestCostMs", System.currentTimeMillis() - start);
        } catch (Exception e) {
            result.put("requestCompressionHeaderSet", false);
            result.put("requestError", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        start = System.currentTimeMillis();
        try {
            Map<String, Object> largeResponse = compressionTestClient.getLargeData();
            result.put("responseCompression", true);
            result.put("responseDataLength", largeResponse.get("length"));
            result.put("responseCostMs", System.currentTimeMillis() - start);
        } catch (Exception e) {
            result.put("responseCompression", false);
            result.put("responseError", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    /** A-07: 负载均衡测试 — 多次调用 nacos-lab,观察请求分布 */
    @GetMapping("/feign/load-balance-test")
    public Map<String, Object> testLoadBalance() {
        Map<String, Object> result = new HashMap<>();
        result.put("test", "A7-负载均衡");
        int totalCalls = 20;
        List<Map<String, Object>> callResults = new ArrayList<>();
        Map<String, Integer> portCount = new HashMap<>();
        for (int i = 0; i < totalCalls; i++) {
            long start = System.currentTimeMillis();
            Map<String, Object> callResult = new HashMap<>();
            callResult.put("call", i + 1);
            try {
                Map<String, Object> status = nacosLabClient.discoveryStatus();
                callResult.put("success", true);
                callResult.put("port", status.get("port"));
                callResult.put("costMs", System.currentTimeMillis() - start);
                String port = String.valueOf(status.get("port"));
                portCount.merge(port, 1, Integer::sum);
            } catch (Exception e) {
                callResult.put("success", false);
                callResult.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                callResult.put("costMs", System.currentTimeMillis() - start);
            }
            callResults.add(callResult);
        }
        result.put("totalCalls", totalCalls);
        result.put("portDistribution", portCount);
        result.put("calls", callResults);
        result.put("note", "Spring Cloud LoadBalancer 轮询分发请求到不同实例");
        return result;
    }

    /** A-09: RestClient vs OpenFeign 对比测试 */
    @GetMapping("/feign/restclient-test")
    public Map<String, Object> testRestClient() {
        Map<String, Object> result = new HashMap<>();
        result.put("test", "A9-RestClient对比");
        try {
            long start = System.currentTimeMillis();
            String feignV1 = nacosLabV1Client.health();
            long feignV1Cost = System.currentTimeMillis() - start;

            start = System.currentTimeMillis();
            String restV1 = restClientExample.health();
            long restV1Cost = System.currentTimeMillis() - start;

            start = System.currentTimeMillis();
            Map<String, Object> feignV2 = nacosLabV2Client.discoveryStatus();
            long feignV2Cost = System.currentTimeMillis() - start;

            start = System.currentTimeMillis();
            Map<String, Object> restV2 = restClientExample.discoveryStatus();
            long restV2Cost = System.currentTimeMillis() - start;

            Map<String, Object> v1 = new HashMap<>();
            v1.put("feignResult", feignV1);
            v1.put("feignCostMs", feignV1Cost);
            v1.put("restClientResult", restV1);
            v1.put("restClientCostMs", restV1Cost);
            v1.put("same", feignV1.equals(restV1));
            v1.put("feignCode", "@FeignClient + interface: 3 行声明");
            v1.put("restClientCode", "RestClient.builder() + .get().retrieve(): 5 行 + try-catch");

            Map<String, Object> v2 = new HashMap<>();
            v2.put("feignResult", feignV2);
            v2.put("feignCostMs", feignV2Cost);
            v2.put("restClientResult", restV2);
            v2.put("restClientCostMs", restV2Cost);
            v2.put("same", feignV2.equals(restV2));
            v2.put("feignCode", "@FeignClient + interface: 3 行声明");
            v2.put("restClientCode", "RestClient.builder() + .get().retrieve() + ParameterizedTypeReference");

            result.put("v1", v1);
            result.put("v2", v2);
            result.put("note", "Feign: 声明式接口,编译期生成代理; RestClient: 命令式 Builder,运行时构造");
            result.put("conclusion", "Feign 适合+微服务间调用(声明式,负载均衡,熔断); "
                    + "RestClient 适合+简单 HTTP 调用(轻量,无额外依赖)");
        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    /** B-02~B-05: 自定义扩展点测试(CustomInvocationHandlerFactory/CustomContract/CustomEncoder/CustomDecoder/CustomErrorDecoder) */
    @GetMapping("/feign/custom-ext-test")
    public Map<String, Object> testCustomExtensions() {
        Map<String, Object> result = new HashMap<>();
        result.put("test", "B2-B5-自定义扩展点");

        if (customExtensionsTestClient == null) {
            result.put("error", "CustomExtensionsTestClient not available");
            return result;
        }

        try {
            long start = System.currentTimeMillis();
            Map<String, Object> echo = customExtensionsTestClient.echo();
            result.put("echoSuccess", true);
            result.put("echoCostMs", System.currentTimeMillis() - start);
            result.put("echo", echo);

            start = System.currentTimeMillis();
            Map<String, Object> bodyResult = customExtensionsTestClient.echoBody(Map.of("hello", "feign"));
            result.put("bodyEchoSuccess", true);
            result.put("bodyEchoCostMs", System.currentTimeMillis() - start);
            result.put("bodyEcho", bodyResult);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    /** B-02~B-05: 自定义扩展点回显端点 */
    @GetMapping("/feign/custom-ext/echo")
    public Map<String, Object> customExtEcho() {
        Map<String, Object> result = new HashMap<>();
        result.put("message", "custom-ext-echo-ok");
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    /** B-02~B-05: 请求体回显端点 */
    @PostMapping("/feign/custom-ext/echo-body")
    public Map<String, Object> customExtEchoBody(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>(body);
        result.put("echoed", true);
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

}
