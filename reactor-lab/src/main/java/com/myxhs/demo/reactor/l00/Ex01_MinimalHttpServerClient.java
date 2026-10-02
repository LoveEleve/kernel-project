package com.myxhs.demo.reactor.l00;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;

/**
 * Ex01：Reactor Netty —— {@code HttpServer} / {@code HttpClient} 的最小使用（对应前置课 L00 · 0.4.4）。
 *
 * <p>要观察的 3 件事：
 * <ol>
 *   <li>{@code handle((request, response) -> ...)} 拿到的是 <b>HTTP 对象</b>（不是字节流）</li>
 *   <li>{@code HttpClient} 的调用是 <b>声明式链</b>：{@code get().uri()...responseContent().aggregate().asString()}</li>
 *   <li>没有业务线程池、没有阻塞等待 —— 全部跑在 EventLoop 上</li>
 * </ol>
 *
 * <p>跑：{@code mvn -pl reactor-lab exec:java -Dexec.mainClass=com.myxhs.demo.reactor.l00.Ex01_MinimalHttpServerClient}
 */
public class Ex01_MinimalHttpServerClient {

    public static void main(String[] args) {
        System.out.println();
        System.out.println("=== Ex01 Reactor Netty：HttpServer + HttpClient 最小使用 ===");
        System.out.println();

        // ① 起一个 HttpServer（port(0) = 让系统分配随机端口，避免冲突）
        DisposableServer server = HttpServer.create()
                .port(0)
                .handle((request, response) -> {
                    System.out.println("  [HttpServer] 收到请求: " + request.method() + " " + request.uri());
                    return response.header("Content-Type", "text/plain;charset=UTF-8")
                            .sendString(Mono.just("hello from HttpServer, uri=" + request.uri()));
                })
                .bindNow();
        System.out.println("  [HttpServer] 已启动，端口 = " + server.port());
        System.out.println();

        // ② 用 HttpClient 调它（声明式链）
        HttpClient client = HttpClient.create();
        String body = client.get()
                .uri("http://localhost:" + server.port() + "/ping?from=demo")
                .responseContent()          // 响应体：流（ByteBufFlux）
                .aggregate()                // 聚合成一个 ByteBuf
                .asString()                 // Mono<String>
                .block(Duration.ofSeconds(3));   // ⚠️ 实验里为方便才 block；Gateway 里绝不这样写

        System.out.println("  [HttpClient] 收到响应: " + body);
        System.out.println();

        // ③ 关闭
        server.disposeNow();
        System.out.println("  [HttpServer] 已关闭");
        System.out.println();

        System.out.println("对照结论：");
        System.out.println("  · handle((request, response) -> ...)：拿到的是【HTTP 对象】，不是字节流（那是 TcpServer）");
        System.out.println("  · HttpClient 是【声明式链】：get().uri()...responseContent()...asString()");
        System.out.println("  · Gateway 转发下游用的就是它的 HttpClient（NettyRoutingFilter 持有）");
    }
}
