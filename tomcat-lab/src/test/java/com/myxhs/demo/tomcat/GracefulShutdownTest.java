package com.myxhs.demo.tomcat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 07 篇优雅停机的复现测试。
 *
 * <p>断言思路（不依赖日志、结果确定）：
 * <ul>
 *   <li>graceful：在途请求进入服务端后 {@code context.close()} 会阻塞到请求完成（closeMillis ≥ 请求时长），
 *       且请求返回 200；同步请求靠 {@code countAllocated}，异步请求靠 {@code inProgressAsyncCount}</li>
 *   <li>immediate：{@code context.close()} 不等在途请求（closeMillis &lt; 1s），请求结局不保证</li>
 * </ul>
 */
class GracefulShutdownTest {

    @Test
    void gracefulShutdownWaitsForInFlightSyncRequest() throws Exception {
        try (ConfigurableApplicationContext context = start("graceful")) {
            int port = port(context);
            HttpClient client = HttpClient.newHttpClient();

            CompletableFuture<HttpResponse<String>> inFlight =
                    getAsync(client, port, "/slow?seconds=3&tag=graceful");
            await(client, port, "inFlight=1");

            long start = System.currentTimeMillis();
            context.close();
            long closeMillis = System.currentTimeMillis() - start;

            HttpResponse<String> response = inFlight.get(30, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("done tag=graceful");
            // 下界=等完在途请求；上界=防止"一直等到 phase 超时(15s)"这种假通过
            assertThat(closeMillis).as("graceful：close() 应等在途同步请求完成")
                    .isBetween(2_000L, 10_000L);
            System.out.println("[lab] graceful(sync)  closeMillis=" + closeMillis + "  body=" + response.body());
        }
    }

    @Test
    void gracefulShutdownWaitsForAsyncRequest() throws Exception {
        try (ConfigurableApplicationContext context = start("graceful")) {
            int port = port(context);
            HttpClient client = HttpClient.newHttpClient();

            CompletableFuture<HttpResponse<String>> inFlight = getAsync(client, port, "/async?seconds=3");
            await(client, port, "asyncInFlight=1");

            long start = System.currentTimeMillis();
            context.close();
            long closeMillis = System.currentTimeMillis() - start;

            HttpResponse<String> response = inFlight.get(30, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("async done");
            // 异步等待期 countAllocated==0（StandardWrapperValve finally 无条件 deallocate），
            // 能拦住停机的只可能是 inProgressAsyncCount —— 这正是本用例要验证的
            assertThat(closeMillis).as("graceful：close() 应等在途异步请求完成（inProgressAsyncCount）")
                    .isBetween(2_000L, 10_000L);
            System.out.println("[lab] graceful(async) closeMillis=" + closeMillis + "  body=" + response.body());
        }
    }

    @Test
    void immediateShutdownDoesNotWait() throws Exception {
        try (ConfigurableApplicationContext context = start("immediate")) {
            int port = port(context);
            HttpClient client = HttpClient.newHttpClient();

            CompletableFuture<HttpResponse<String>> inFlight =
                    getAsync(client, port, "/slow?seconds=10&tag=immediate");
            await(client, port, "inFlight=1");

            long start = System.currentTimeMillis();
            context.close();
            long closeMillis = System.currentTimeMillis() - start;

            assertThat(closeMillis).as("immediate：close() 不应等待在途请求").isLessThan(1_000);

            // 在途请求的结局不保证：可能连接被硬断（预期），也可能侥幸写完（记录即可）
            try {
                HttpResponse<String> response = inFlight.get(3, TimeUnit.SECONDS);
                System.out.println("[lab] immediate        closeMillis=" + closeMillis
                        + "  在途请求侥幸完成: status=" + response.statusCode() + " body=" + response.body());
            }
            catch (Exception ex) {
                System.out.println("[lab] immediate        closeMillis=" + closeMillis
                        + "  在途请求被中断: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }
    }

    private ConfigurableApplicationContext start(String shutdown) {
        // 注意：必须用命令行参数（最高优先级）而不是 builder.properties()——
        // 后者是 default properties（最低优先级），会被 application.properties 的 graceful 覆盖。
        return new SpringApplicationBuilder(TomcatLabApplication.class)
                .run("--server.port=0",
                        "--server.shutdown=" + shutdown,
                        "--spring.lifecycle.timeout-per-shutdown-phase=15s");
    }

    private int port(ConfigurableApplicationContext context) {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    private CompletableFuture<HttpResponse<String>> getAsync(HttpClient client, int port, String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    private void await(HttpClient client, int port, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/inflight")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.body().contains(expected)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("请求未在 5s 内进入服务端（/inflight 始终不含 " + expected + "）");
    }
}
