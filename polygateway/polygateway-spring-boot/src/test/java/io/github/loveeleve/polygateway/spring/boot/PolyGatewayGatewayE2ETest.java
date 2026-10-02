/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.loveeleve.polygateway.spring.boot;

import com.sun.net.httpserver.HttpServer;
import io.github.loveeleve.polygateway.PolyGatewayConstants;
import io.github.loveeleve.polygateway.web.WebEndpointMapping;
import io.github.loveeleve.polygateway.web.WebEndpointMappingCodec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.discovery.event.HeartbeatEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the PolyGateway gateway side through the full auto-configuration: a stub
 * discovery client publishes a service with the web endpoint metadata, a real HTTP server plays
 * the downstream provider, and the requests are routed precisely through the generated routes.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
@SpringBootTest(classes = PolyGatewayGatewayE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "polygateway.gateway.excludes[0].patterns=/internal/**",
                "polygateway.gateway.excludes[0].methods=POST",
                "spring.cloud.gateway.discovery.locator.enabled=false"})
@AutoConfigureWebTestClient(timeout = "PT30S")
class PolyGatewayGatewayE2ETest {

    private static final String SERVICE_ID = "order-service";

    @SpringBootApplication(exclude = {
            org.springframework.cloud.client.discovery.simple.SimpleDiscoveryClientAutoConfiguration.class,
            org.springframework.cloud.client.discovery.simple.reactive.SimpleReactiveDiscoveryClientAutoConfiguration.class,
            org.springframework.cloud.client.discovery.composite.CompositeDiscoveryClientAutoConfiguration.class,
            org.springframework.cloud.client.discovery.composite.reactive.ReactiveCompositeDiscoveryClientAutoConfiguration.class})
    static class TestApplication {

        @Bean
        DiscoveryClient discoveryClient() {
            return new MutableDiscoveryClient();
        }
    }

    @Autowired
    private WebTestClient webClient;

    @Autowired
    private MutableDiscoveryClient discoveryClient;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Test
    void testEndpointRouting() throws Exception {
        RecordingHttpServer downstream = RecordingHttpServer.start();
        try {
            List<WebEndpointMapping> endpoints = List.of(
                    WebEndpointMapping.builder(WebEndpointMapping.Kind.MVC)
                            .id(1).patterns("/orders/{id}").methods("GET").build(),
                    WebEndpointMapping.builder(WebEndpointMapping.Kind.MVC)
                            .id(2).patterns("/orders").methods("POST").consumes("application/json").build(),
                    WebEndpointMapping.builder(WebEndpointMapping.Kind.MVC)
                            .id(3).patterns("/internal/reconcile").methods("POST").build());
            discoveryClient.setInstances(SERVICE_ID, List.of(instance(SERVICE_ID, downstream.port(), "/api", endpoints)));
            eventPublisher.publishEvent(new HeartbeatEvent(this, "initial"));

            // GET with the path variable : Path + Method + StripPrefix
            awaitUntil(() -> {
                String body = webClient.get().uri("/order-service/api/orders/42")
                        .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
                return "GET /api/orders/42".equals(body);
            });
            // POST with the matching media type : Path + Method + Consumes
            webClient.post().uri("/order-service/api/orders")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":\"x\"}")
                    .exchange().expectStatus().isOk();
            assertEquals("/api/orders", downstream.lastPathOf("POST"));
            // The same POST with an unmatched media type has no route
            webClient.post().uri("/order-service/api/orders")
                    .contentType(MediaType.TEXT_PLAIN).bodyValue("x")
                    .exchange().expectStatus().isNotFound();
            // The excluded endpoint has no route
            webClient.post().uri("/order-service/api/internal/reconcile")
                    .exchange().expectStatus().isNotFound();
            // A GET to an unknown path has no route
            webClient.get().uri("/order-service/api/unknown")
                    .exchange().expectStatus().isNotFound();

            // The refresh proof : a new endpoint appears in the metadata and becomes routable
            // after the heartbeat
            List<WebEndpointMapping> refreshed = new ArrayList<>(endpoints);
            refreshed.add(WebEndpointMapping.builder(WebEndpointMapping.Kind.MVC)
                    .id(4).patterns("/orders/{id}/lines").methods("GET").build());
            discoveryClient.setInstances(SERVICE_ID, List.of(instance(SERVICE_ID, downstream.port(), "/api", refreshed)));
            eventPublisher.publishEvent(new HeartbeatEvent(this, "refreshed"));
            awaitUntil(() -> {
                String body = webClient.get().uri("/order-service/api/orders/42/lines")
                        .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
                return "GET /api/orders/42/lines".equals(body);
            });
        } finally {
            downstream.stop();
        }
    }

    private static ServiceInstance instance(String serviceId, int port, String contextPath,
                                            List<WebEndpointMapping> endpoints) {
        DefaultServiceInstance instance = new DefaultServiceInstance(serviceId + "-1", serviceId,
                "127.0.0.1", port, false);
        instance.getMetadata().put(PolyGatewayConstants.WEB_ENDPOINTS_METADATA_NAME,
                WebEndpointMappingCodec.encode(endpoints));
        instance.getMetadata().put(PolyGatewayConstants.CONTEXT_PATH_METADATA_NAME, contextPath);
        return instance;
    }

    /**
     * The mutable stub discovery client of the tests.
     */
    static class MutableDiscoveryClient implements DiscoveryClient {

        private final Map<String, List<ServiceInstance>> instances = new LinkedHashMap<>();

        void setInstances(String serviceId, List<ServiceInstance> serviceInstances) {
            instances.put(serviceId, serviceInstances);
        }

        @Override
        public String description() {
            return "mutable-stub";
        }

        @Override
        public List<ServiceInstance> getInstances(String serviceId) {
            return instances.getOrDefault(serviceId, List.of());
        }

        @Override
        public List<String> getServices() {
            return new ArrayList<>(instances.keySet());
        }
    }

    /**
     * A minimal downstream HTTP server echoing the method and the path.
     */
    static class RecordingHttpServer {

        private final HttpServer server;

        private final Map<String, String> paths = new java.util.concurrent.ConcurrentHashMap<>();

        private RecordingHttpServer(HttpServer server) {
            this.server = server;
        }

        static RecordingHttpServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            RecordingHttpServer recording = new RecordingHttpServer(server);
            server.createContext("/", exchange -> {
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();
                recording.paths.put(method, path);
                byte[] body = (method + " " + path).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.setExecutor(null);
            server.start();
            return recording;
        }

        int port() {
            return server.getAddress().getPort();
        }

        String lastPathOf(String method) {
            return paths.get(method);
        }

        void stop() {
            server.stop(0);
        }
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (RuntimeException ignored) {
            }
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), "The condition was not satisfied in time");
    }

    @FunctionalInterface
    interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
