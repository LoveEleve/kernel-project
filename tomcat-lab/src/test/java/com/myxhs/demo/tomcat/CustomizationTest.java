package com.myxhs.demo.tomcat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import jakarta.servlet.ServletException;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.embedded.tomcat.TomcatConnectorCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 10 篇替换与定制的复现测试。
 *
 * <ul>
 *   <li>{@code customValveAddsHeader}：自定义 Valve（引擎级）给所有响应加 Header</li>
 *   <li>{@code connectorCustomizerSetsServerHeader}：TomcatConnectorCustomizer 改 Server 响应头</li>
 *   <li>{@code propertyDrivenKeepAliveDisablesConnection}：属性驱动 keep-alive=1（含"自定义 customizer 被 Boot 属性覆盖"的坑）</li>
 *   <li>{@code remoteIpValveHonorsXForwardedFor}：forward-headers-strategy=native → RemoteIpValve 还原真实 IP</li>
 * </ul>
 */
class CustomizationTest {

    @Test
    void customValveAddsHeader() throws Exception {
        try (ConfigurableApplicationContext context = start(ValveConfig.class)) {
            HttpResponse<String> response = get(port(context), "/inflight", null);
            String header = response.headers().firstValue("X-Lab-Valve").orElse("(缺失)");
            System.out.println("[lab] 自定义 Valve → X-Lab-Valve=" + header + " status=" + response.statusCode());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(header).isEqualTo("hit");
        }
    }

    @Test
    void connectorCustomizerSetsServerHeader() throws Exception {
        try (ConfigurableApplicationContext context = start(ConnectorConfig.class)) {
            HttpResponse<String> response = get(port(context), "/inflight", null);
            String server = response.headers().firstValue("Server").orElse("(无)");
            System.out.println("[lab] ConnectorCustomizer → Server=" + server);
            assertThat(server).isEqualTo("xhs-lab/1.0");
        }
    }

    @Test
    void propertyDrivenKeepAliveDisablesConnection() throws Exception {
        // 注意：不要用自定义 TomcatProtocolHandlerCustomizer 去改 maxKeepAliveRequests——
        // Boot 属性驱动的 customizer（server.tomcat.*）由 BeanPostProcessor 后注册，
        // 会覆盖用户先加的同类 customizer（实测：自定义 1 被 Boot 的默认 100 覆盖）。
        // 要改这类属性，直接用属性（下面这样）或自定义 ServletWebServerFactory Bean。
        try (ConfigurableApplicationContext context =
                     startWithArgs("--server.tomcat.max-keep-alive-requests=1")) {
            // 必须用裸 Socket：JDK HttpClient 会把 Connection 这类逐跳头从响应里藏掉。
            // maxKeepAliveRequests=1 时 Tomcat 在响应后主动关连接（Http11Processor:386-391/:1011），
            // 所以裸 Socket 能读完（若设置未生效，readAllBytes 会卡到 SO_TIMEOUT）。
            String raw = rawGet(port(context), "/inflight");
            String head = raw.lines().limit(8).reduce("", (a, b) -> a + b + "\n");
            System.out.println("[lab] max-keep-alive-requests=1 → 原始响应头:\n" + head);
            assertThat(raw).contains("Connection: close");
        }
    }

    @Test
    void remoteIpValveHonorsXForwardedFor() throws Exception {
        try (ConfigurableApplicationContext context = startWithArgs("--server.forward-headers-strategy=native")) {
            HttpResponse<String> response = get(port(context), "/remote-addr", "1.2.3.4");
            System.out.println("[lab] forward-headers-strategy=native → " + response.body());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("remoteAddr=1.2.3.4");
        }
    }

    /** 通过 WebServerFactoryCustomizer 挂一个引擎级 Valve（对所有请求生效）。 */
    @Configuration(proxyBeanMethods = false)
    static class ValveConfig {

        @Bean
        WebServerFactoryCustomizer<TomcatServletWebServerFactory> labValveCustomizer() {
            return factory -> factory.addEngineValves(new LabHeaderValve());
        }
    }

    /** 通过 TomcatConnectorCustomizer 改 Server 响应头（Boot 不设置该属性，不会被属性 customizer 覆盖）。 */
    @Configuration(proxyBeanMethods = false)
    static class ConnectorConfig {

        @Bean
        TomcatConnectorCustomizer labConnectorCustomizer() {
            return connector -> connector.setProperty("server", "xhs-lab/1.0");
        }
    }

    /** 最小 Valve：给响应加一个 Header，然后交给下一个 Valve。 */
    static class LabHeaderValve extends ValveBase {

        @Override
        public void invoke(Request request, Response response) throws IOException, ServletException {
            response.setHeader("X-Lab-Valve", "hit");
            getNext().invoke(request, response);
        }
    }

    private ConfigurableApplicationContext start(Class<?>... sources) {
        Class<?>[] all = new Class<?>[sources.length + 1];
        all[0] = TomcatLabApplication.class;
        System.arraycopy(sources, 0, all, 1, sources.length);
        return new SpringApplicationBuilder(all).run("--server.port=0");
    }

    private ConfigurableApplicationContext startWithArgs(String... args) {
        return new SpringApplicationBuilder(TomcatLabApplication.class).run(prependPort(args));
    }

    private String[] prependPort(String[] args) {
        String[] all = new String[args.length + 1];
        all[0] = "--server.port=0";
        System.arraycopy(args, 0, all, 1, args.length);
        return all;
    }

    private int port(ConfigurableApplicationContext context) {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    private HttpResponse<String> get(int port, String path, String xForwardedFor) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (xForwardedFor != null) {
            builder.header("X-Forwarded-For", xForwardedFor);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 裸 Socket GET：读原始响应（服务端应主动关连接，否则 5s 超时暴露问题）。 */
    private String rawGet(int port, String path) throws IOException {
        try (java.net.Socket socket = new java.net.Socket("localhost", port)) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
