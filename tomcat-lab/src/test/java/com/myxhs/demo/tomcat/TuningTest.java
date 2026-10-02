package com.myxhs.demo.tomcat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 09 篇调参与故障排查的复现测试。
 *
 * <ul>
 *   <li>{@code threadsMaxLimitsConcurrency}：maxThreads=1 → 第二个请求排队（串行化）</li>
 *   <li>{@code maxConnectionsThrottlesNewConnections}：maxConnections=1 → 第二个连接等第一个释放</li>
 *   <li>{@code busyThreadsMetricReflectsLoad}：actuator 指标 tomcat.threads.busy 反映在途线程</li>
 * </ul>
 *
 * <p>注意：maxThreads 与 minSpareThreads 要一起设小，否则 core(10) > max 会让线程池创建失败。
 */
class TuningTest {

    private static final Pattern VALUE = Pattern.compile("\"value\"\\s*:\\s*([0-9.]+)");

    @Test
    void threadsMaxLimitsConcurrency() throws Exception {
        try (ConfigurableApplicationContext context =
                     start("--server.tomcat.threads.max=1", "--server.tomcat.threads.min-spare=1")) {
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

            System.out.println("[lab] threads.max=1 → A=" + ra.body() + " | B elapsed=" + elapsedB + "ms body=" + rb.body());
            assertThat(ra.statusCode()).isEqualTo(200);
            assertThat(rb.statusCode()).isEqualTo(200);
            // B 必须等 A 释放唯一线程：B 耗时 ≥ 1s(自己) + ~0.8s(A 剩余)
            assertThat(elapsedB).as("maxThreads=1 时第二个请求应被排队").isGreaterThanOrEqualTo(1_500L);
        }
    }

    @Test
    void maxConnectionsThrottlesNewConnections() throws Exception {
        try (ConfigurableApplicationContext context = start("--server.tomcat.max-connections=1")) {
            int port = port(context);
            // 注意：warm-up 也必须用"用完即关"的裸 Socket——HttpClient 默认 keep-alive，
            // 空闲连接会一直占住唯一的连接名额（maxConnections=1），后续请求全被卡住。
            rawGetAsync(port, "/slow?seconds=0&tag=warmup").get(10, TimeUnit.SECONDS);

            // 用裸 Socket：JDK HttpClient 禁止设置 Connection 头，而这里必须禁用 keep-alive。
            CompletableFuture<long[]> a = rawGetAsync(port, "/slow?seconds=2&tag=A");   // [status, elapsed]
            Thread.sleep(300);
            CompletableFuture<long[]> b = rawGetAsync(port, "/slow?seconds=0&tag=B");

            long[] ra = a.get(15, TimeUnit.SECONDS);
            long[] rb = b.get(15, TimeUnit.SECONDS);

            System.out.println("[lab] maxConnections=1 → A(status=" + ra[0] + ", " + ra[1] + "ms)"
                    + " | B(status=" + rb[0] + ", " + rb[1] + "ms)");
            assertThat(ra[0]).isEqualTo(200);
            assertThat(rb[0]).isEqualTo(200);
            // B 的连接在 backlog 里排队，等 A 的连接销毁（Acceptor 才从 latch 放行）
            assertThat(rb[1]).as("maxConnections=1 时第二个请求应等连接释放").isGreaterThanOrEqualTo(1_500L);
        }
    }

    /** 裸 Socket GET：显式 Connection: close，读完响应即关（返回 [status, elapsedMs]）。 */
    private CompletableFuture<long[]> rawGetAsync(int port, String path) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            try (java.net.Socket socket = new java.net.Socket("localhost", port)) {
                java.io.OutputStream out = socket.getOutputStream();
                out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                out.flush();
                String response = new String(socket.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                int status = Integer.parseInt(response.split(" ")[1]);
                return new long[] { status, System.currentTimeMillis() - start };
            }
            catch (java.io.IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        });
    }

    @Test
    void busyThreadsMetricReflectsLoad() throws Exception {
        try (ConfigurableApplicationContext context =
                     start("--server.tomcat.threads.max=10", "--server.tomcat.mbeanregistry.enabled=true")) {
            HttpClient client = HttpClient.newHttpClient();
            int port = port(context);
            // 端口 0 启动时连接器名是 http-nio-auto-N，从 warm-up 响应的线程名反推（http-nio-auto-N-exec-X）
            String connectorName = connectorName(client, port);
            warmUp(client, port);

            CompletableFuture<HttpResponse<String>> a = getAsync(client, port, "/slow?seconds=3&tag=A");
            CompletableFuture<HttpResponse<String>> b = getAsync(client, port, "/slow?seconds=3&tag=B");
            CompletableFuture<HttpResponse<String>> c = getAsync(client, port, "/slow?seconds=3&tag=C");

            // 关键：按 name 标签过滤到"本实例"——同 JVM 里前几个测试的 Tomcat MBean 残留会一起聚合，
            // 且已停止实例的 currentThreadsBusy 返回 -2（AbstractEndpoint:1058-1072），会拉出负数。
            String tag = "name:" + connectorName;
            double busy = awaitMetricAtLeast(client, port, "tomcat.threads.busy", tag, 4.0, 6_000);
            System.out.println("[lab] 在途时 tomcat.threads.busy[" + tag + "]=" + busy);
            assertThat(busy).isGreaterThanOrEqualTo(4.0);   // 3 个慢请求 + 指标请求自身占 1 个线程

            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
            c.get(15, TimeUnit.SECONDS);
            Thread.sleep(300);
            double after = metricValue(client, port, "tomcat.threads.busy", tag);
            System.out.println("[lab] 请求结束后 tomcat.threads.busy[" + tag + "]=" + after);
            assertThat(after).isLessThanOrEqualTo(1.0);     // 只剩指标请求自身
        }
    }

    private ConfigurableApplicationContext start(String... extraArgs) {
        String[] args = new String[extraArgs.length + 1];
        args[0] = "--server.port=0";
        System.arraycopy(extraArgs, 0, args, 1, extraArgs.length);
        return new SpringApplicationBuilder(TomcatLabApplication.class).run(args);
    }

    private int port(ConfigurableApplicationContext context) {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    private void warmUp(HttpClient client, int port) throws Exception {
        HttpResponse<String> response = get(client, port, "/slow?seconds=0&tag=warmup");
        assertThat(response.statusCode()).isEqualTo(200);
    }

    /** 从 /slow 响应的线程名（http-nio-auto-N-exec-X）反推连接器名（http-nio-auto-N）。 */
    private String connectorName(HttpClient client, int port) throws Exception {
        HttpResponse<String> response = get(client, port, "/slow?seconds=0&tag=nameprobe");
        Matcher matcher = Pattern.compile("thread=([a-zA-Z0-9\\-]+)-exec-\\d+").matcher(response.body());
        assertThat(matcher.find()).as("应能从响应中解析线程名: " + response.body()).isTrue();
        return matcher.group(1);
    }

    private HttpResponse<String> get(HttpClient client, int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private CompletableFuture<HttpResponse<String>> getAsync(HttpClient client, int port, String path) {
        return client.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private double awaitMetricAtLeast(HttpClient client, int port, String metric, String tag, double threshold,
            long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        double value = -1;
        while (System.currentTimeMillis() < deadline) {
            value = metricValue(client, port, metric, tag);
            if (value >= threshold) {
                return value;
            }
            Thread.sleep(100);
        }
        return value;
    }

    private double metricValue(HttpClient client, int port, String metric, String tag) throws Exception {
        HttpResponse<String> response = get(client, port, "/actuator/metrics/" + metric + "?tag=" + tag);
        if (response.statusCode() != 200) {
            System.out.println("[lab][debug] " + metric + " HTTP " + response.statusCode() + " body=" + response.body());
            return -1;
        }
        Matcher matcher = VALUE.matcher(response.body());
        if (!matcher.find()) {
            System.out.println("[lab][debug] " + metric + " 解析失败 body=" + response.body());
            return -1;
        }
        return Double.parseDouble(matcher.group(1));
    }
}
