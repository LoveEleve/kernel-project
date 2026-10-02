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
 * 自定义 Filter 的复现测试（demo：{@code RequestTimingFilter} + {@code FilterConfig}）。
 *
 * <p>验证点：
 * <ul>
 *   <li>Filter 按 URL 匹配（"/*"）对所有端点生效；</li>
 *   <li>响应阶段可写头（X-Lab-Timing / X-Lab-Timing-Ms）；</li>
 *   <li>Filter 在 Servlet 之前执行（响应头已写入即返回，Servlet 也已执行）。</li>
 * </ul>
 */
class FilterLabTest {

    @Test
    void customFilterRunsForAllUrls() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            HttpClient client = HttpClient.newHttpClient();
            int port = port(context);

            HttpResponse<String> inflight = get(client, port, "/inflight");
            HttpResponse<String> slow = get(client, port, "/slow?seconds=1&tag=filter");
            HttpResponse<String> cost = get(client, port, "/lab/filter-last-cost");

            System.out.println("[lab] /inflight → X-Lab-Timing="
                    + inflight.headers().firstValue("X-Lab-Timing").orElse("(缺失)")
                    + "（调用链前设置的头，一定生效）");
            System.out.println("[lab] /slow     → X-Lab-Timing="
                    + slow.headers().firstValue("X-Lab-Timing").orElse("(缺失)"));
            System.out.println("[lab] 耗时上报：" + cost.body());

            assertThat(inflight.statusCode()).isEqualTo(200);
            assertThat(slow.statusCode()).isEqualTo(200);
            assertThat(inflight.headers().firstValue("X-Lab-Timing")).contains("on");
            assertThat(slow.headers().firstValue("X-Lab-Timing")).contains("on");
            // 耗时不可靠地写进响应头（响应已提交），改为端点查询：/slow 睡了 1s，耗时必然 > 500ms
            assertThat(cost.body()).contains("lastCostMs=");
            long lastCost = Long.parseLong(cost.body().replace("lastCostMs=", "").trim());
            assertThat(lastCost).as("Filter 应记录到 /slow 的耗时").isGreaterThanOrEqualTo(500L);
        }
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(TomcatLabApplication.class).run("--server.port=0");
    }

    private int port(ConfigurableApplicationContext context) {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    private HttpResponse<String> get(HttpClient client, int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
