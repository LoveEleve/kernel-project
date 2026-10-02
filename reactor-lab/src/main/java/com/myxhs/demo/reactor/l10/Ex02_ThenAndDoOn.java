package com.myxhs.demo.reactor.l10;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Ex02：{@code then} vs {@code doOnXxx}（对应 L10 · 10.4）。
 *
 * <p>要观察的 4 件事：
 * <ol>
 *   <li>{@code doOnNext} 只是<b>旁听</b>：值照常流下去</li>
 *   <li>{@code then(...)} 是<b>接续</b>：等前面完成、<b>丢弃前面的值</b>，再执行后面的</li>
 *   <li>{@code then()} / {@code thenReturn(v)} / {@code then(Mono)} 三兄弟的区别</li>
 *   <li>为什么"记耗时/改响应"要写在 {@code then(...)} 里 —— 它在 post 阶段</li>
 * </ol>
 *
 * <p>跑：{@code mvn -pl reactor-lab exec:java -Dexec.mainClass=com.myxhs.demo.reactor.l10.Ex02_ThenAndDoOn}
 */
public class Ex02_ThenAndDoOn {

    static void log(String s) {
        System.out.println("        " + s);
    }

    public static void main(String[] args) {
        System.out.println();
        System.out.println("=== Ex02 then vs doOnXxx ===");
        System.out.println();

        System.out.println("① doOnNext：只是旁听 —— 值照常流到下游");
        Flux.range(1, 3)
                .doOnNext(v -> log("[doOnNext] " + v + "（旁听，不改变数据）"))
                .subscribe(v -> log("[下游] 收到 " + v));
        System.out.println();

        System.out.println("② then：等前面完成、丢弃值 —— 下游只会收到 onComplete");
        Flux.range(1, 3)
                .doOnNext(v -> log("[源] 产生 " + v))
                .then(Mono.fromRunnable(() -> log("[then] 收尾（post 阶段）")))
                .subscribe(
                        v -> log("[下游] 收到值: " + v),
                        e -> log("[下游] onError: " + e),
                        () -> log("[下游] onComplete（注意：一个值都没收到）"));
        System.out.println();

        System.out.println("③ 三兄弟：then() / thenReturn(v) / then(Mono)");
        Mono.just("DATA").then()
                .subscribe(v -> log("then()      收到值: " + v + " ← 不会有"),
                        e -> { },
                        () -> log("then()      只有 onComplete"));
        Mono.just("DATA").thenReturn("FIXED")
                .subscribe(v -> log("thenReturn  收到值: " + v));
        Mono.just("DATA").then(Mono.just("NEXT"))
                .subscribe(v -> log("then(Mono)  收到值: " + v));
        System.out.println();

        System.out.println("④ 出错路径：doOnError 旁听 + onErrorResume 兜底");
        Mono.<String>error(new IllegalStateException("下游挂了"))
                .doOnError(e -> log("[doOnError] " + e.getMessage()))
                .onErrorResume(e -> Mono.just("兜底数据"))
                .subscribe(v -> log("[下游] 收到 " + v));
        System.out.println();

        System.out.println("对照结论：");
        System.out.println("  · doOnXxx = 旁听（打日志、清理），不改变数据流");
        System.out.println("  · then    = 接续（等完成、丢值），这就是 post 阶段");
        System.out.println("  · 所以 Gateway 里 chain.filter(exchange).then(记耗时) 才能测到\"整条链\"的耗时");
    }
}
