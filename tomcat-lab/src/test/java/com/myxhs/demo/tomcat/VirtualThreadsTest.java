package com.myxhs.demo.tomcat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 11 篇虚拟线程的复现测试（需 JDK21+，JDK17 下自动跳过）。
 *
 * <p>对照设计：同一份配置（threads.max=1 + min-spare=1）+ 两个并发 1s 慢请求——
 * <ul>
 *   <li>平台线程：被唯一线程串行，B ≈ 1811ms（TuningTest 已证）</li>
 *   <li>虚拟线程（spring.threads.virtual.enabled=true）：不受 maxThreads 限制，B ≈ 1000ms</li>
 * </ul>
 *
 * 运行：{@code JAVA_HOME=/env/javaEnv/jdk21 mvn -pl tomcat-lab test -Dtest=VirtualThreadsTest}
 */
class VirtualThreadsTest {

    @Test
    @EnabledForJreRange(min = JRE.JAVA_21)
    void virtualThreadsIgnoreMaxThreads() throws Exception {
        try (ConfigurableApplicationContext context = start(
                "--server.tomcat.threads.max=1", "--server.tomcat.threads.min-spare=1",
                "--spring.threads.virtual.enabled=true")) {
            HttpClient client = HttpClient.newHttpClient();
            int port = port(context);
            warmUp(client, port);

            CompletableFuture<HttpResponse<String>> a = getAsync(client, port, "/slow?seconds=1&tag=A");
            Thread.sleep(200);
            long beginB = System.currentTimeMillis();
            CompletableFuture<HttpResponse<String>> b = getAsync(client, port, "/slow?seconds=1&tag=B");

            HttpResponse<String> ra = a.get(15, TimeUnit.SECONDS);
            HttpResponse<String> rb = b.get(15, TimeUnit.SECONDS);
            long elapsedB = System.currentTimeMillis() - beginB;

            System.out.println("[lab] 虚拟线程 + maxThreads=1 → A: " + ra.body());
            System.out.println("[lab]                         B: elapsed=" + elapsedB + "ms " + rb.body());
            assertThat(ra.statusCode()).isEqualTo(200);
            assertThat(rb.statusCode()).isEqualTo(200);
            // 对照 TuningTest（平台线程 B ≈ 1811ms）：虚拟线程下两个请求并行，B ≈ 1000ms
            assertThat(elapsedB).as("虚拟线程不应受 maxThreads=1 限制").isLessThan(1_500L);
            assertThat(ra.body()).contains("thread=tomcat-handler-");
            assertThat(rb.body()).contains("thread=tomcat-handler-");
        }
    }

    @Test
    @EnabledForJreRange(min = JRE.JAVA_21)
    void platformThreadsStillSerializeWithSameConfig() throws Exception {
        // 对照组：不开虚拟线程，同一配置 → 串行（与 TuningTest 同口径，放一起便于 A/B）
        try (ConfigurableApplicationContext context = start(
                "--server.tomcat.threads.max=1", "--server.tomcat.threads.min-spare=1")) {
            HttpClient client = HttpClient.newHttpClient();
            int port = port(context);
            warmUp(client, port);

            CompletableFuture<HttpResponse<String>> a = getAsync(client, port, "/slow?seconds=1&tag=A");
            Thread.sleep(200);
            long beginB = System.currentTimeMillis();
            CompletableFuture<HttpResponse<String>> b = getAsync(client, port, "/slow?seconds=1&tag=B");

            HttpResponse<String> ra = a.get(15, TimeUnit.SECONDS);
            HttpResponse<String> rb = b.get(15, TimeUnit.SECONDS);
            long elapsedB = System.currentTimeMillis() - beginB;

            System.out.println("[lab] 平台线程 + maxThreads=1 → B: elapsed=" + elapsedB + "ms " + rb.body());
            assertThat(elapsedB).as("平台线程下 maxThreads=1 应串行").isGreaterThanOrEqualTo(1_500L);
            assertThat(rb.body()).contains("thread=http-nio-");
        }
    }

    private ConfigurableApplicationContext start(String... extraArgs) {
        String[] all = new String[extraArgs.length + 1];
        all[0] = "--server.port=0";
        System.arraycopy(extraArgs, 0, all, 1, extraArgs.length);
        return new SpringApplicationBuilder(TomcatLabApplication.class).run(all);
    }

    private int port(ConfigurableApplicationContext context) {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    private void warmUp(HttpClient client, int port) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/slow?seconds=0&tag=warmup"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private CompletableFuture<HttpResponse<String>> getAsync(HttpClient client, int port, String path) {
        return client.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
