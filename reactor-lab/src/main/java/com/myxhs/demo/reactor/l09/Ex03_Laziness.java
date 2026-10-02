package com.myxhs.demo.reactor.l09;

import reactor.core.publisher.Mono;

/**
 * Ex03：惰性（Laziness）—— 构造 ≠ 执行。
 *
 * <p>要观察的 3 件事：
 * <ol>
 *   <li>{@code Mono.just(slow())} —— <b>构造时就执行</b>（立即求值）</li>
 *   <li>{@code Mono.fromCallable(() -> slow())} / {@code Mono.defer(...)} —— <b>订阅时才执行</b></li>
 *   <li>冷流：<b>每次订阅都会重新执行</b>一遍</li>
 * </ol>
 *
 * <p>这就是 L10 那个坑的根：在过滤器里写 {@code Mono.just(阻塞调用())}，
 * 阻塞发生在【构造 Mono 的那一刻】——而那一刻通常就在 EventLoop 线程上。
 *
 * 跑：mvn -pl reactor-lab exec:java -Dexec.mainClass=com.myxhs.demo.reactor.l09.Ex03_Laziness
 */
public class Ex03_Laziness {

    /** 假装这是一次耗时/有副作用的调用（DB、Redis、RPC…） */
    static String slow(String tag) {
        System.out.println("        >>> slow(" + tag + ") 执行了！");
        return tag;
    }

    public static void main(String[] args) {
        System.out.println();
        System.out.println("=== Ex03 惰性：构造 ≠ 执行 ===");
        System.out.println();

        System.out.println("① 构造 Mono.just(slow(\"just\")) —— 注意：现在就执行了！");
        Mono<String> a = Mono.just(slow("just"));
        System.out.println();

        System.out.println("② 构造 Mono.fromCallable(() -> slow(\"fromCallable\"))");
        Mono<String> b = Mono.fromCallable(() -> slow("fromCallable"));
        System.out.println();

        System.out.println("③ 构造 Mono.defer(() -> Mono.just(slow(\"defer\")))");
        Mono<String> c = Mono.defer(() -> Mono.just(slow("defer")));
        System.out.println();

        System.out.println(">>> 到这里为止，只有 ① 执行过 —— ②③ 还什么都没做（惰性）");
        System.out.println();

        System.out.println("④ 现在订阅 a");
        a.subscribe(v -> System.out.println("        <<< a 收到: " + v));
        System.out.println();

        System.out.println("⑤ 现在订阅 b（这时才执行 fromCallable 里的代码）");
        b.subscribe(v -> System.out.println("        <<< b 收到: " + v));
        System.out.println();

        System.out.println("⑥ 再订阅 b 一次（冷流：会再执行一遍）");
        b.subscribe(v -> System.out.println("        <<< b 收到: " + v));
        System.out.println();

        System.out.println("⑦ 订阅 c");
        c.subscribe(v -> System.out.println("        <<< c 收到: " + v));
        System.out.println();

        System.out.println("对照结论：");
        System.out.println("  · just  = 立即求值（构造时就跑）");
        System.out.println("  · fromCallable / defer = 延迟求值（订阅时才跑）");
        System.out.println("  · 冷流每次订阅都重跑（想让多个订阅者共享数据 → 需要 Sinks/热流，见 L12）");
    }
}
