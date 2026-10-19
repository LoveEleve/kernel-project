package com.scalab.loadbalancer;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * A-01 轮询自动化集成测试（可离线运行，不依赖真实 Nacos）。
 *
 * <p>方案：mock {@link DiscoveryClient} 返回两个内嵌 HttpServer 实例（随机端口），
 * 通过主类的 {@code @LoadBalanced RestTemplate} 调用 20 次，断言轮询 10:10 均匀分布。
 *
 * <p>注：直接复用 {@link LoadBalancerLabApplication} 的 loadBalancedRestTemplate Bean，
 * 不再重复定义（避免同类型双 Bean 的 byName 隐式依赖）。
 *
 * <p>已知坑：@SpringBootTest 上下文类级别缓存，两个测试方法共享 LoadBalancer 子上下文
 * 和 CachingServiceInstanceListSupplier 的缓存——两个方法必须使用相同实例（端口），
 * 否则 Caching 返回旧实例导致连接失败。当前两个方法用同端口设计。
 */
@SpringBootTest(classes = LoadBalancerLabApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
        "spring.cloud.nacos.discovery.namespace="
})
class A01RoundRobinIntegrationTest {

    private static final Map<Integer, AtomicInteger> hits = new ConcurrentHashMap<>();

    private static HttpServer server1;
    private static HttpServer server2;
    private static int port1;
    private static int port2;

    @Autowired
    private RestTemplate loadBalancedRestTemplate;

    @MockitoBean
    private DiscoveryClient discoveryClient;

    @BeforeAll
    static void startServers() throws IOException {
        server1 = startServer();
        server2 = startServer();
        port1 = server1.getAddress().getPort();
        port2 = server2.getAddress().getPort();
    }

    @AfterAll
    static void stopServers() {
        if (server1 != null) server1.stop(0);
        if (server2 != null) server2.stop(0);
    }

    @BeforeEach
    void clearHits() {
        hits.clear();   // 防止跨测试共享统计污染断言
    }

    private static HttpServer startServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/discovery/status", exchange -> {
            int port = server.getAddress().getPort();
            hits.computeIfAbsent(port, p -> new AtomicInteger()).incrementAndGet();
            byte[] body = ("{\"port\":" + port + "}").getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void roundRobinShouldDistributeEvenly() {
        when(discoveryClient.getInstances("nacos-lab")).thenReturn(List.of(
                new DefaultServiceInstance("test-1", "nacos-lab", "127.0.0.1", port1, false),
                new DefaultServiceInstance("test-2", "nacos-lab", "127.0.0.1", port2, false)));
        when(discoveryClient.getServices()).thenReturn(List.of("nacos-lab"));

        for (int i = 0; i < 20; i++) {
            Map<String, Object> status = loadBalancedRestTemplate.getForObject(
                    "http://nacos-lab/discovery/status", Map.class);
            if (status == null) {
                throw new IllegalStateException("null response at call " + i);
            }
        }

        int h1 = countOf(port1);
        int h2 = countOf(port2);
        System.out.printf("[A-01] round-robin: port%d=%d, port%d=%d%n", port1, h1, port2, h2);

        assertEquals(10, h1, "round-robin should hit each instance 10 times");
        assertEquals(10, h2, "round-robin should hit each instance 10 times");
        assertEquals(20, h1 + h2, "total calls should be 20");
    }

    private static int countOf(int port) {
        AtomicInteger counter = hits.get(port);
        return counter != null ? counter.get() : 0;
    }

    @Test
    void sameInstancesAlsoPassedToDiscoveryClient() {
        when(discoveryClient.getInstances("nacos-lab")).thenReturn(List.of(
                new DefaultServiceInstance("test-1", "nacos-lab", "127.0.0.1", port1, false),
                new DefaultServiceInstance("test-2", "nacos-lab", "127.0.0.1", port2, false)));
        when(discoveryClient.getServices()).thenReturn(List.of("nacos-lab"));

        // 单次调用不抛异常即证明实例列表供应链路通（A-03）
        Map<String, Object> status = loadBalancedRestTemplate.getForObject(
                "http://nacos-lab/discovery/status", Map.class);
        assertTrue(status != null && status.containsKey("port"),
                "should get port from one of the mock instances");
    }
}
