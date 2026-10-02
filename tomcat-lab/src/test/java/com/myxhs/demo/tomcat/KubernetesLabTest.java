package com.myxhs.demo.tomcat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 13 篇云原生（K8s）的本地可复现部分。
 *
 * <ul>
 *   <li>{@code docBaseIsCreatedUnderJavaIoTmpdir}：内嵌 Tomcat 的 docBase 临时目录落在 java.io.tmpdir
 *       （只读根文件系统要挂 emptyDir / 配 server.tomcat.basedir）</li>
 *   <li>{@code shutdownPhaseTimeoutCutsInFlightRequest}：shutdown-phase 超时会强停在途请求
 *       （K8s 的 terminationGracePeriodSeconds 必须大于它）</li>
 *   <li>{@code jvmInfoReflectsProcessRuntime}：/jvm-info 与进程运行时一致（配合脚本对比 JVM 参数）</li>
 * </ul>
 */
class KubernetesLabTest {

    @Test
    void docBaseIsCreatedUnderJavaIoTmpdir() throws Exception {
        long before = countTomcatDocBases();
        try (ConfigurableApplicationContext context = start()) {
            long after = countTomcatDocBases();
            System.out.println("[lab] java.io.tmpdir=" + System.getProperty("java.io.tmpdir")
                    + "  tomcat-docbase 目录数: " + before + " → " + after);
            assertThat(after).as("启动后应在 java.io.tmpdir 下多出 tomcat-docbase 目录").isGreaterThan(before);
        }
    }

    @Test
    void shutdownPhaseTimeoutCutsInFlightRequest() throws Exception {
        try (ConfigurableApplicationContext context =
                     startWithArgs("--spring.lifecycle.timeout-per-shutdown-phase=1s")) {
            HttpClient client = HttpClient.newHttpClient();
            int port = port(context);

            CompletableFuture<HttpResponse<String>> slow = getAsync(client, port, "/slow?seconds=5&tag=cut");
            awaitInFlight(client, port);

            long start = System.currentTimeMillis();
            context.close();
            long closeMillis = System.currentTimeMillis() - start;

            System.out.println("[lab] shutdown-phase=1s + 5s 慢请求 → closeMillis=" + closeMillis);
            assertThat(closeMillis).as("1s 超时后应强制继续（远小于 5s 请求时长）")
                    .isBetween(500L, 3_500L);

            try {
                HttpResponse<String> response = slow.get(3, TimeUnit.SECONDS);
                System.out.println("[lab] 在途请求结局: status=" + response.statusCode() + " body=" + response.body());
            }
            catch (Exception ex) {
                System.out.println("[lab] 在途请求被硬断: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }
    }

    @Test
    void jvmInfoReflectsProcessRuntime() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            HttpClient client = HttpClient.newHttpClient();
            String body = get(client, port(context), "/jvm-info").body();
            System.out.println("[lab] /jvm-info → " + body);
            assertThat(body).contains("processors=" + Runtime.getRuntime().availableProcessors());
            assertThat(body).contains("maxMemoryMb=" + (Runtime.getRuntime().maxMemory() >> 20));
            assertThat(body).contains("tmpdir=" + System.getProperty("java.io.tmpdir"));
        }
    }

    private long countTomcatDocBases() throws IOException {
        Path tmp = Paths.get(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> list = Files.list(tmp)) {
            return list.filter(p -> p.getFileName().toString().startsWith("tomcat-docbase")).count();
        }
    }

    private void awaitInFlight(HttpClient client, int port) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> response = get(client, port, "/inflight");
            if (response.body().contains("inFlight=1")) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("慢请求未在 5s 内进入服务端");
    }

    private ConfigurableApplicationContext start() {
        return startWithArgs();
    }

    private ConfigurableApplicationContext startWithArgs(String... args) {
        String[] all = new String[args.length + 1];
        all[0] = "--server.port=0";
        System.arraycopy(args, 0, all, 1, args.length);
        return new SpringApplicationBuilder(TomcatLabApplication.class).run(all);
    }

    private int port(ConfigurableApplicationContext context) {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    private CompletableFuture<HttpResponse<String>> getAsync(HttpClient client, int port, String path) {
        return client.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(HttpClient client, int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
