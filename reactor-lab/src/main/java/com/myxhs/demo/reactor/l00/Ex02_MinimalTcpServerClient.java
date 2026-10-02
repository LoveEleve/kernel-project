package com.myxhs.demo.reactor.l00;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.tcp.TcpClient;
import reactor.netty.tcp.TcpServer;

import java.time.Duration;

/**
 * Ex02：Reactor Netty —— {@code TcpServer} / {@code TcpClient}（和 Http 版对比，对应 L00 · 0.4.4 附录）。
 *
 * <p>为什么要看这个：理解"Reactor Netty 有两层"——
 * <b>TCP 层</b>（字节流）和 <b>HTTP 层</b>（在 TCP 之上加 HTTP 编解码 + 请求/响应对象）。
 *
 * <p>要观察的 3 件事：
 * <ol>
 *   <li>{@code TcpServer.handle((inbound, outbound) -> ...)} 拿到的是 <b>收发字节流</b>，<b>没有任何 HTTP 语义</b></li>
 *   <li>{@code HttpServer} 是在 TCP 之上加了 HTTP 编解码；API 形状一样（create → handle → bindNow）</li>
 *   <li><b>Gateway 零引用 TcpServer/TcpClient</b> —— 入站用 HttpServer、出站用 HttpClient</li>
 * </ol>
 *
 * <p>跑：{@code mvn -pl reactor-lab exec:java -Dexec.mainClass=com.myxhs.demo.reactor.l00.Ex02_MinimalTcpServerClient}
 */
public class Ex02_MinimalTcpServerClient {

    public static void main(String[] args) {
        System.out.println();
        System.out.println("=== Ex02 Reactor Netty：TcpServer + TcpClient 最小使用（对比 Http 版）===");
        System.out.println();

        // ① TcpServer：handle 的两个参数是【入站/出站字节流】
        DisposableServer server = TcpServer.create()
                .port(0)
                .handle((inbound, outbound) -> {
                    System.out.println("  [TcpServer] 有连接进来（注意：看不到任何 URL / Header / 方法）");
                    // 没有 HTTP 语义：直接往连接里写字节；返回的 Publisher 完成 → 连接关闭
                    return outbound.sendString(Mono.just("hello over TCP\n"));
                })
                .bindNow();
        System.out.println("  [TcpServer] 已启动，端口 = " + server.port());
        System.out.println();

        // ② TcpClient：连接 + 读字节
        String received = TcpClient.create()
                .host("localhost")
                .port(server.port())
                .connect()                                  // Mono<? extends Connection>
                .flatMap(conn -> conn.inbound()
                        .receive()                          // 收字节（ByteBufFlux）
                        .aggregate()
                        .asString()
                        .doOnTerminate(conn::dispose))      // 读完关连接
                .block(Duration.ofSeconds(3));

        System.out.println("  [TcpClient] 收到: " + (received == null ? "(超时/空)" : received.trim()));
        System.out.println();

        server.disposeNow();
        System.out.println("  [TcpServer] 已关闭");
        System.out.println();

        System.out.println("对照结论：");
        System.out.println("  · TCP 层：拿到的是【字节流】—— 自定义协议（IM、二进制协议）才用它");
        System.out.println("  · HTTP 层：在 TCP 之上加【编解码 + HttpServerRequest/Response】（见 Ex01）");
        System.out.println("  · Gateway 只用 HTTP 层（HttpServer 入站 / HttpClient 出站），TcpServer/TcpClient 零引用");
    }
}
