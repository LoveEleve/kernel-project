package com.myxhs.demo.reactor.l11;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;

/**
 * Ex05：EventLoop 线程 vs Schedulers 线程（L11 补充实验）。
 *
 * <p>回答三个问题：
 * <ol>
 *   <li><b>默认</b>：请求处理（handler / 过滤器）跑在哪个线程？ → 预期 <b>EventLoop</b>（reactor-http-epoll-N）</li>
 *   <li>切到 boundedElastic 后，<b>后续阶段</b>在哪跑？ → 预期<b>仍在 boundedElastic</b>（不会自动切回）</li>
 *   <li>那"发响应"这种 IO 是谁执行的？ → <b>仍是 EventLoop</b>（业务线程 ≠ IO 线程）</li>
 * </ol>
 *
 * <p>跑：{@code ./scripts/run-one.sh l11.Ex05_EventLoopVsScheduler}
 */
public class Ex05_EventLoopVsScheduler {

    static String me() {
        return Thread.currentThread().getName();
    }

    public static void main(String[] args) {
        System.out.println();
        System.out.println("=== Ex05 EventLoop vs Schedulers ===");
        System.out.println();

        DisposableServer server = HttpServer.create()
                .port(0)
                .handle((request, response) -> {
                    String path = request.uri();

                    if (path.startsWith("/blocking")) {
                        // 模拟"过滤器里要调阻塞客户端"的正确姿势
                        return Mono.fromCallable(() -> {
                                    System.out.println("    [阻塞任务]  @" + me() + "   ← 已切到 boundedElastic");
                                    Thread.sleep(100);          // 阻塞：在 boundedElastic 上安全
                                    return "blocked-ok";
                                })
                                .subscribeOn(Schedulers.boundedElastic())      // ★ 把阻塞段挪出 EventLoop
                                .flatMap(result -> {
                                    // 这里没有 publishOn → 仍在 boundedElastic（不会自动切回 EventLoop）
                                    System.out.println("    [阻塞之后]  @" + me() + "   ← 注意：没有切回 EventLoop");
                                    return response.sendString(Mono.just("ok:" + path)).then();
                                });
                    }

                    // 默认路径：handler 直接在 EventLoop 上执行
                    System.out.println("    [默认 handler] @" + me());
                    return response.sendString(Mono.just("ok:" + path)).then();
                })
                .bindNow();
        System.out.println("  [HttpServer] 已启动，端口 = " + server.port());
        System.out.println();

        HttpClient client = HttpClient.create();

        System.out.println("① 默认请求（看服务端 handler 跑在哪个线程）");
        System.out.println("   [客户端] 响应: " + client.get()
                .uri("http://localhost:" + server.port() + "/hello")
                .responseContent().aggregate().asString().block(Duration.ofSeconds(3)));
        System.out.println();

        System.out.println("② 带阻塞的请求（subscribeOn(boundedElastic) 之后）");
        System.out.println("   [客户端] 响应: " + client.get()
                .uri("http://localhost:" + server.port() + "/blocking")
                .responseContent().aggregate().asString().block(Duration.ofSeconds(3)));
        System.out.println();

        server.disposeNow();
        System.out.println("对照结论：");
        System.out.println("  · 默认：请求处理（handler / 过滤器链）跑在 【EventLoop】(reactor-http-epoll-N)");
        System.out.println("  · subscribeOn(boundedElastic)：阻塞段被挪到 boundedElastic，EventLoop 被解放");
        System.out.println("  · 切走后【不会自动切回】—— 后续阶段仍在 boundedElastic（Reactor 线程模型：谁切走就一直在那）");
        System.out.println("  · 但\"发响应\"这种 IO 仍由 EventLoop 执行（业务线程 ≠ IO 线程）");
    }
}
