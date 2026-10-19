package com.myxhs.demo.tomcat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L05/04 篇：Mapper 匹配优先级的复现测试。
 *
 * <p>三个 Servlet 同存：
 * <ul>
 *   <li>{@code /ping-exact} —— EXACT（精确匹配）</li>
 *   <li>{@code /ping-path/*} —— PATH（前缀匹配，最长优先）</li>
 *   <li>DispatcherServlet（Boot 注册在 "/"）—— DEFAULT（兜底）</li>
 * </ul>
 * 断言：精确胜前缀、前缀胜默认。
 */
class MappingLabTest {

    @Test
    void exactBeatsPathBeatsDefault() throws Exception {
        try (ConfigurableApplicationContext context = start(MappingConfig.class)) {
            HttpClient client = HttpClient.newHttpClient();
            int port = port(context);

            HttpResponse<String> exact = get(client, port, "/ping-exact");
            HttpResponse<String> path = get(client, port, "/ping-path/abc");
            HttpResponse<String> other = get(client, port, "/ping-elsewhere");

            System.out.println("[lab] /ping-exact   → status=" + exact.statusCode() + " body=" + exact.body());
            System.out.println("[lab] /ping-path/abc→ status=" + path.statusCode() + " body=" + path.body());
            System.out.println("[lab] /ping-elsewhere → status=" + other.statusCode() + "（兜底进 DispatcherServlet，无对应 Controller 故 404）");

            assertThat(exact.statusCode()).isEqualTo(200);
            assertThat(exact.body()).isEqualTo("exact");
            assertThat(path.statusCode()).isEqualTo(200);
            assertThat(path.body()).isEqualTo("path");
            assertThat(other.statusCode()).isEqualTo(404);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MappingConfig {

        @Bean
        ServletRegistrationBean<ExactServlet> exactServlet() {
            return new ServletRegistrationBean<>(new ExactServlet(), "/ping-exact");
        }

        @Bean
        ServletRegistrationBean<PathServlet> pathServlet() {
            return new ServletRegistrationBean<>(new PathServlet(), "/ping-path/*");
        }
    }

    static class ExactServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().write("exact");
        }
    }

    static class PathServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().write("path");
        }
    }

    private ConfigurableApplicationContext start(Class<?>... sources) {
        Class<?>[] all = new Class<?>[sources.length + 1];
        all[0] = TomcatLabApplication.class;
        System.arraycopy(sources, 0, all, 1, sources.length);
        return new SpringApplicationBuilder(all).run("--server.port=0");
    }

    private int port(ConfigurableApplicationContext context) {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    private HttpResponse<String> get(HttpClient client, int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
