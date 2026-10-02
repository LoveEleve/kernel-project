package com.myxhs.demo.reactor.l00;

import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;

/**
 * Ex03：Spring WebFlux 的<b>最小使用</b>（不引 Spring Boot）—— 对应前置课 L00 · 0.6。
 *
 * <p>目的：看清 WebFlux 的三层拼装关系（Gateway 本质上就是这套 + 一组 Gateway 自己的扩展点）：
 * <pre>
 *   RouterFunction（路由表）
 *        ↓ RouterFunctions.toHttpHandler(...)
 *   HttpHandler（WebFlux 对外的统一形态）
 *        ↓ ReactorHttpHandlerAdapter（桥）
 *   Reactor Netty HttpServer（传输层，Ex01 已见）
 * </pre>
 *
 * <p>要点：
 * <ol>
 *   <li><b>Standalone 也能跑</b>：不引 Spring Boot，只要 spring-webflux + reactor-netty</li>
 *   <li>{@code ReactorHttpHandlerAdapter} 就是 Spring Boot 里 {@code NettyWebServer} 干的事</li>
 *   <li>Spring Boot 做的"自动配置"就是：把 RouterFunction/DispatcherHandler 等 Bean 装配好</li>
 * </ol>
 *
 * <p>跑：{@code mvn -pl reactor-lab exec:java -Dexec.mainClass=com.myxhs.demo.reactor.l00.Ex03_MinimalWebFlux}
 */
public class Ex03_MinimalWebFlux {

    public static void main(String[] args) {
        System.out.println();
        System.out.println("=== Ex03 Spring WebFlux 最小使用（不引 Spring Boot）===");
        System.out.println();

        // ① 路由表（WebFlux 的"函数式端点"写法）
        RouterFunction<ServerResponse> routes = RouterFunctions.route()
                .GET("/hello", request -> ServerResponse.ok().bodyValue("hello webflux"))
                .GET("/echo/{name}", request -> ServerResponse.ok()
                        .bodyValue("echo: " + request.pathVariable("name")))
                .build();

        // ② RouterFunction → HttpHandler
        HttpHandler httpHandler = RouterFunctions.toHttpHandler(routes);

        // ③ HttpHandler → Reactor Netty HttpServer（Spring Boot 的 NettyWebServer 就干这个）
        DisposableServer server = HttpServer.create()
                .port(0)
                .handle(new ReactorHttpHandlerAdapter(httpHandler))
                .bindNow();
        System.out.println("  [WebFlux] 已启动，端口 = " + server.port());
        System.out.println();

        // ④ 调用两个端点
        HttpClient client = HttpClient.create();
        for (String path : new String[] {"/hello", "/echo/my-xhs"}) {
            String body = client.get()
                    .uri("http://localhost:" + server.port() + path)
                    .responseContent().aggregate().asString()
                    .block(Duration.ofSeconds(3));
            System.out.println("  [HttpClient] GET " + path + " → " + body);
        }
        System.out.println();

        server.disposeNow();
        System.out.println("  [WebFlux] 已关闭");
        System.out.println();

        System.out.println("对照结论：");
        System.out.println("  · WebFlux 最小形态 = RouterFunction → HttpHandler → ReactorHttpHandlerAdapter → HttpServer");
        System.out.println("  · Spring Boot 的自动配置 = 把上面的装配好（外加 DispatcherHandler / 各 HandlerMapping 注册）");
        System.out.println("  · Gateway 就是这套 + 自己的扩展点：RoutePredicateHandlerMapping（HandlerMapping）+ FilteringWebHandler（WebHandler）");
        System.out.println("  · ⚠️ DispatcherHandler 没有 setter，只能由 ApplicationContext 注入策略 —— 所以最小例子用 RouterFunction 形态");
    }
}
