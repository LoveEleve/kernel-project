package com.myxhs.demo.tomcat;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 12 篇线上排查的复现测试——用代码演一遍 jstack/MAT 的判读逻辑。
 *
 * <ul>
 *   <li>{@code busyThreadStackRevealsHotMethod}：栈采样找到热点方法（等价 jstack 定位 CPU 热点）</li>
 *   <li>{@code deadlockDetectedByThreadMxBean}：ThreadMXBean 检测死锁（等价 jstack "Found one Java-level deadlock"）</li>
 *   <li>{@code staticMapLeakGrows}：静态集合增长/清理（等价 MAT 支配树定位"静态集合"引用链）</li>
 * </ul>
 */
class DiagnosticsTest {

    @Test
    void busyThreadStackRevealsHotMethod() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            HttpClient client = HttpClient.newHttpClient();
            int port = port(context);

            CompletableFuture<HttpResponse<String>> spin = getAsync(client, port, "/spin?ms=3000");

            Thread hot = await(3_000, () -> Thread.getAllStackTraces().entrySet().stream()
                    .filter(e -> e.getKey().getName().startsWith("http-nio-")
                            && e.getKey().getName().contains("exec"))
                    .filter(e -> Arrays.stream(e.getValue())
                            .anyMatch(f -> f.getClassName().contains("DiagnosticsController")
                                    && f.getMethodName().equals("spin")))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null));

            assertThat(hot).as("应能在执行 /spin 的 worker 线程栈里找到热方法").isNotNull();
            System.out.println("[lab] jstack 模拟 → 热点线程: " + hot.getName());
            Arrays.stream(hot.getStackTrace()).limit(5)
                    .forEach(f -> System.out.println("[lab]     at " + f));

            spin.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void deadlockDetectedByThreadMxBean() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            HttpClient client = HttpClient.newHttpClient();
            int port = port(context);

            // 两个请求互相等对方放锁 → 死锁（结果 future 永远不会完成，忽略即可）
            getAsync(client, port, "/deadlock/a");
            getAsync(client, port, "/deadlock/b");

            ThreadMXBean threadMxBean = ManagementFactory.getThreadMXBean();
            long[] deadlocked = await(5_000, () -> {
                long[] found = threadMxBean.findDeadlockedThreads();
                return (found != null && found.length >= 2) ? found : null;
            });

            assertThat(deadlocked).as("应检测到两个线程死锁").isNotNull().hasSizeGreaterThanOrEqualTo(2);
            System.out.println("[lab] jstack 模拟 → Found " + deadlocked.length + " Java-level deadlock(s):");
            for (ThreadInfo info : threadMxBean.getThreadInfo(deadlocked, 6)) {
                System.out.println("[lab]   \"" + info.getThreadName() + "\" 等待: "
                        + (info.getLockInfo() != null ? info.getLockInfo() : "(monitor)"));
                Arrays.stream(info.getStackTrace()).limit(4)
                        .forEach(f -> System.out.println("[lab]       at " + f));
            }
        }
    }

    @Test
    void staticMapLeakGrows() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            HttpClient client = HttpClient.newHttpClient();
            int port = port(context);

            HttpResponse<String> after3 = null;
            for (int i = 0; i < 3; i++) {
                after3 = get(client, port, "/leak?mb=1");
            }
            HttpResponse<String> status = get(client, port, "/leak/status");
            HttpResponse<String> cleared = get(client, port, "/leak/clear");

            System.out.println("[lab] MAT 模拟 → 3 次 /leak?mb=1 后: " + after3.body());
            System.out.println("[lab]           清理后: " + cleared.body());
            assertThat(status.body()).contains("leakedMb=3");
            assertThat(cleared.body()).contains("leakedMb=0");
        }
    }

    /** 轮询等待工具：返回第一个非 null 的结果，超时返回 null。 */
    private <T> T await(long timeoutMs, Supplier<T> supplier) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            T result = supplier.get();
            if (result != null) {
                return result;
            }
            Thread.sleep(50);
        }
        return null;
    }

    private ConfigurableApplicationContext start() {
        // shutdown=immediate：死锁/泄漏现场会让 graceful 停机一直等在途请求（拖到 phase 超时）——
        // 排查实验不想要这个副作用（正文把这一点作为"排查与停机联动"的教学点）。
        return new SpringApplicationBuilder(TomcatLabApplication.class)
                .run("--server.port=0", "--server.shutdown=immediate");
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
