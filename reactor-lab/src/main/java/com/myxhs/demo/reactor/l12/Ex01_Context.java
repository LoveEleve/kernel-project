package com.myxhs.demo.reactor.l12;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.HashMap;
import java.util.Map;

/**
 * Ex01：{@code Context}（L12 要掌握的 ①）。
 *
 * <p>要观察的 5 件事：
 * <ol>
 *   <li>写（`contextWrite` 在链底）+ 读（`deferContextual` 在链中）</li>
 *   <li>不可变 + 叠加（两次 contextWrite）</li>
 *   <li>跟<b>订阅</b>走：同一段链，两次订阅可以写不同 Context</li>
 *   <li>跨线程不丢（切到 boundedElastic 仍读得到）</li>
 *   <li>Gateway 的桥接做法：把 {@code ContextView} 存进 `attributes`</li>
 * </ol>
 *
 * <p>跑：{@code ./scripts/run-one.sh l12.Ex01_Context}
 */
public class Ex01_Context {

    static String me() {
        return Thread.currentThread().getName();
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println();
        System.out.println("=== Ex01 Context ===");
        System.out.println();

        System.out.println("① 基本：写 + 读");
        Mono.deferContextual(ctx -> Mono.just("读到 traceId=" + ctx.get("traceId")))
                .contextWrite(Context.of("traceId", "T-123"))
                .subscribe(v -> System.out.println("    " + v));
        System.out.println();

        System.out.println("② 不可变 + 叠加：两次 contextWrite");
        Mono.deferContextual(ctx -> Mono.just(
                        "traceId=" + ctx.get("traceId") + ", zone=" + ctx.getOrDefault("zone", "<无>")))
                .contextWrite(Context.of("traceId", "T-111"))
                .contextWrite(c -> c.put("zone", "shanghai"))
                .subscribe(v -> System.out.println("    " + v));
        System.out.println();

        System.out.println("③ 跟【订阅】走：同一段链，两次订阅写不同的值");
        Mono<String> chain = Mono.deferContextual(ctx -> Mono.just("traceId=" + ctx.get("traceId")));
        chain.contextWrite(Context.of("traceId", "T-A")).subscribe(v -> System.out.println("    订阅1 → " + v));
        chain.contextWrite(Context.of("traceId", "T-B")).subscribe(v -> System.out.println("    订阅2 → " + v));
        System.out.println();

        System.out.println("④ 跨线程不丢：subscribeOn(boundedElastic) 后，在 elastic 线程上仍读得到");
        Mono.deferContextual(ctx -> Mono.just("线程=" + me() + ", traceId=" + ctx.get("traceId")))
                .subscribeOn(Schedulers.boundedElastic())       // 整条链（含读 Context 的算子）切到 elastic
                .contextWrite(Context.of("traceId", "T-跨线程"))
                .subscribe(v -> System.out.println("    " + v));
        Thread.sleep(300);   // 等异步输出
        System.out.println();

        System.out.println("⑤ Gateway 的桥接做法：ContextView → attributes（源码 RoutePredicateHandlerMapping:88）");
        Map<String, Object> attributes = new HashMap<>();          // 模拟 exchange.getAttributes()
        Mono.deferContextual(ctxView -> {
                    attributes.put("gatewayReactorContext", ctxView);    // ★ Context → attribute
                    return Mono.just("已把 ContextView 存入 attribute");
                })
                .contextWrite(Context.of("traceId", "T-观测"))
                .subscribe(v -> System.out.println("    " + v));

        // 之后【任何只有 exchange 的过滤器】都能读回来（无需 Context）
        ContextView ctxView = (ContextView) attributes.get("gatewayReactorContext");
        System.out.println("    从 attribute 读回：traceId=" + ctxView.get("traceId"));
        System.out.println();

        System.out.println("对照结论：");
        System.out.println("  · Context 不可变；从【订阅点向上】传播（contextWrite 写底部、上面读）");
        System.out.println("  · Context 跟订阅走、跨线程不丢（所以不用 ThreadLocal）");
        System.out.println("  · 与 attributes 的分工：Context=链级 / attributes=请求级；Gateway 把 Context 存进 attribute 桥接两者");
    }
}
