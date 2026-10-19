package com.scalab.loadbalancer.function;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer;
import org.springframework.cloud.loadbalancer.core.HealthCheckServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A-05 健康检查机制隔离测试：
 * 两个 mock 实例（health 端点 200 / 500），验证 HealthCheckServiceInstanceListSupplier
 * 只返回健康实例（D2 4.4 分析的过滤逻辑）。
 */
class HealthCheckServiceInstanceListSupplierTest {

    private static HttpServer healthyServer;
    private static HttpServer unhealthyServer;
    private static int healthyPort;
    private static int unhealthyPort;

    @BeforeAll
    static void startServers() throws IOException {
        healthyServer = healthServer(true);
        unhealthyServer = healthServer(false);
        healthyPort = healthyServer.getAddress().getPort();
        unhealthyPort = unhealthyServer.getAddress().getPort();
    }

    @AfterAll
    static void stopServers() {
        if (healthyServer != null) healthyServer.stop(0);
        if (unhealthyServer != null) unhealthyServer.stop(0);
    }

    private static HttpServer healthServer(boolean healthy) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/actuator/health", exchange -> {
            System.out.println("[HealthCheckTest] received request on port " + server.getAddress().getPort()
                    + " from " + exchange.getRemoteAddress());
            int status = healthy ? 200 : 500;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    static class StubSupplier implements ServiceInstanceListSupplier {
        private final List<ServiceInstance> instances;

        StubSupplier(List<ServiceInstance> instances) {
            this.instances = instances;
        }

        @Override
        public String getServiceId() {
            return "test-service";
        }

        @Override
        public Flux<List<ServiceInstance>> get() {
            return Flux.just(instances);
        }

        @Override
        public Flux<List<ServiceInstance>> get(Request request) {
            return get();
        }
    }

    @Test
    void shouldFilterOutUnhealthyInstance() {
        ServiceInstance healthy = new DefaultServiceInstance("h", "test-service", "127.0.0.1", healthyPort, false);
        ServiceInstance unhealthy = new DefaultServiceInstance("u", "test-service", "127.0.0.1", unhealthyPort, false);

        HealthCheckServiceInstanceListSupplier supplier = new HealthCheckServiceInstanceListSupplier(
                new StubSupplier(List.of(healthy, unhealthy)),
                new ReactiveLoadBalancer.Factory<>() {
                    @Override
                    public ReactiveLoadBalancer<ServiceInstance> getInstance(String serviceId) {
                        return null;
                    }

                    @Override
                    public org.springframework.cloud.client.loadbalancer.LoadBalancerProperties getProperties(String serviceId) {
                        return new org.springframework.cloud.client.loadbalancer.LoadBalancerProperties();
                    }

                    @Override
                    public <X> java.util.Map<String, X> getInstances(String name, Class<X> type) {
                        return java.util.Map.of();
                    }

                    @Override
                    public <X> X getInstance(String name, Class<?> clazz, Class<?>... generics) {
                        return null;
                    }
                },
                (instance, path) -> {
                    try {
                        URI uri = URI.create("http://" + instance.getHost() + ":" + instance.getPort() + path);
                        var response = java.net.http.HttpClient.newHttpClient().send(
                                java.net.http.HttpRequest.newBuilder(uri).GET().build(),
                                java.net.http.HttpResponse.BodyHandlers.discarding());
                        // 注意：statusCode() 返回 int，不能 HttpStatus.OK.equals(int)（恒 false），
                        // 用 valueOf + is2xxSuccessful 或直接 == 200
                        return reactor.core.publisher.Mono.just(response.statusCode() == 200);
                    } catch (Exception e) {
                        return reactor.core.publisher.Mono.just(false);
                    }
                });

        // afterPropertiesSet 触发健康检查（D2 4.4.5 分析过）
        try {
            supplier.afterPropertiesSet();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        List<ServiceInstance> alive = supplier.get().blockFirst();
        assertEquals(1, alive.size(), "only healthy instance should survive");
        assertEquals("h", alive.get(0).getInstanceId());
    }
}
