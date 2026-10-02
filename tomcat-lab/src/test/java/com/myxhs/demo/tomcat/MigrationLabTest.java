package com.myxhs.demo.tomcat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 14 篇 jakarta 迁移的复现测试。
 *
 * <ul>
 *   <li>{@code jakartaFilterIsRegisteredAndRuns}：Boot 3 下的标准写法
 *       （{@code jakarta.servlet.Filter} + {@code FilterRegistrationBean<Filter>}）——javax 写法在 Boot 3 编不过</li>
 *   <li>{@code oversizedRequestHeaderRejectedWith400}：Boot 3 改名后的属性
 *       {@code server.max-http-request-header-size}（Boot 2 叫 {@code server.max-http-header-size}，旧名静默失效）</li>
 * </ul>
 */
class MigrationLabTest {

    @Test
    void jakartaFilterIsRegisteredAndRuns() throws Exception {
        try (ConfigurableApplicationContext context = start(FilterConfig.class)) {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = get(client, port(context), "/inflight", null);
            String header = response.headers().firstValue("X-Lab-Filter").orElse("(缺失)");
            System.out.println("[lab] jakarta Filter → X-Lab-Filter=" + header + " status=" + response.statusCode());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(header).isEqualTo("jakarta");
        }
    }

    @Test
    void oversizedRequestHeaderRejectedWith400() throws Exception {
        try (ConfigurableApplicationContext context =
                     startWithArgs("--server.max-http-request-header-size=1KB")) {
            HttpClient client = HttpClient.newHttpClient();
            int port = port(context);

            HttpResponse<String> rejected = get(client, port, "/inflight", "x".repeat(2048));
            HttpResponse<String> normal = get(client, port, "/inflight", null);

            System.out.println("[lab] 2KB 头（限 1KB）→ status=" + rejected.statusCode()
                    + "；正常请求 → status=" + normal.statusCode());
            assertThat(rejected.statusCode()).as("超限请求头应被 Tomcat 拒绝（400）").isEqualTo(400);
            assertThat(normal.statusCode()).isEqualTo(200);
        }
    }

    /** 迁移后的标准注册方式：泛型必须是 jakarta.servlet.Filter。 */
    @Configuration(proxyBeanMethods = false)
    static class FilterConfig {

        @Bean
        FilterRegistrationBean<TimingFilter> labFilter() {
            FilterRegistrationBean<TimingFilter> registration = new FilterRegistrationBean<>(new TimingFilter());
            registration.addUrlPatterns("/*");
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }

    /** jakarta 命名空间的过滤器。 */
    static class TimingFilter implements Filter {

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            ((HttpServletResponse) response).setHeader("X-Lab-Filter", "jakarta");
            chain.doFilter(request, response);
        }
    }

    private ConfigurableApplicationContext start(Class<?>... sources) {
        Class<?>[] all = new Class<?>[sources.length + 1];
        all[0] = TomcatLabApplication.class;
        System.arraycopy(sources, 0, all, 1, sources.length);
        return new SpringApplicationBuilder(all).run("--server.port=0");
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

    private HttpResponse<String> get(HttpClient client, int port, String path, String bigHeader) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (bigHeader != null) {
            builder.header("X-Big", bigHeader);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
