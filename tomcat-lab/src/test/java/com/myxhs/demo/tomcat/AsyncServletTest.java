package com.myxhs.demo.tomcat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 08 篇异步 Servlet 的复现测试。
 *
 * <p>覆盖三条路径：
 * <ul>
 *   <li>DeferredResult 超时 → timeoutResult 兜底（200）</li>
 *   <li>Callable 超时 → 容器/框架兜底（观察状态码；{@code spring.mvc.async.request-timeout=1s}）</li>
 *   <li>原生 AsyncContext：正常完成 / 超时未处理 / 超时被 listener 处理</li>
 * </ul>
 */
class AsyncServletTest {

    @Test
    void deferredTimeoutUsesFallbackResult() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            HttpClient client = HttpClient.newHttpClient();
            long begin = System.currentTimeMillis();
            HttpResponse<String> response = get(client, port(context), "/deferred-timeout?timeoutMs=1000");
            long elapsed = System.currentTimeMillis() - begin;

            System.out.println("[lab] deferred-timeout status=" + response.statusCode()
                    + " elapsed=" + elapsed + " body=" + response.body());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("deferred timeout fallback");
            assertThat(elapsed).isBetween(800L, 5_000L);
        }
    }

    @Test
    void callableTimeoutObserved() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            HttpClient client = HttpClient.newHttpClient();
            long begin = System.currentTimeMillis();
            HttpResponse<String> response = get(client, port(context), "/async?seconds=5");
            long elapsed = System.currentTimeMillis() - begin;

            System.out.println("[lab] callable-timeout status=" + response.statusCode()
                    + " elapsed=" + elapsed + " body=" + response.body());
            // 1s 超时应远早于 5s 的业务完成时间
            assertThat(elapsed).isLessThan(4_000L);
            assertThat(response.statusCode()).isIn(500, 503);
        }
    }

    @Test
    void rawAsyncCompletesNormally() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = get(client, port(context), "/raw-async?mode=ok&workMs=300");

            System.out.println("[lab] raw-async(ok) status=" + response.statusCode() + " body=" + response.body());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("raw done");
        }
    }

    @Test
    void rawAsyncTimeoutUnhandledObserved() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            HttpClient client = HttpClient.newHttpClient();
            long begin = System.currentTimeMillis();
            HttpResponse<String> response = get(client, port(context),
                    "/raw-async?mode=timeout&timeoutMs=500&workMs=5000");
            long elapsed = System.currentTimeMillis() - begin;

            System.out.println("[lab] raw-async(timeout) status=" + response.statusCode()
                    + " elapsed=" + elapsed + " body=" + response.body());
            // Tomcat 异步超时是每秒轮询检查（AbstractProtocol.startAsyncTimeout），
            // 实际触发 = 配置值 + 最多 ~1s；worker 睡 5s，所以兜底必然先到
            assertThat(elapsed).isLessThan(4_000L);
            assertThat(response.statusCode()).isEqualTo(500);
        }
    }

    @Test
    void rawAsyncTimeoutHandledByListener() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            HttpClient client = HttpClient.newHttpClient();
            long begin = System.currentTimeMillis();
            HttpResponse<String> response = get(client, port(context),
                    "/raw-async?mode=timeout-handled&timeoutMs=500&workMs=5000");
            long elapsed = System.currentTimeMillis() - begin;

            System.out.println("[lab] raw-async(timeout-handled) status=" + response.statusCode()
                    + " elapsed=" + elapsed + " body=" + response.body());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("timeout handled by listener");
            // 超时轮询粒度 ±1s，故上界放宽；下界 = 配置超时 500ms
            assertThat(elapsed).isBetween(400L, 3_000L);
        }
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(TomcatLabApplication.class)
                .run("--server.port=0",
                        "--spring.mvc.async.request-timeout=1s");
    }

    private int port(ConfigurableApplicationContext context) {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    private HttpResponse<String> get(HttpClient client, int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
