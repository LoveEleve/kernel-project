package com.myxhs.demo.reactor.l09;

import reactor.core.publisher.Mono;

/**
 * Ex04：{@code Mono<Void>} 到底是什么。
 *
 * <p>要观察的 3 件事：
 * <ol>
 *   <li>{@code Mono<Void>} = <b>没有值，只有"完成"</b>；不会有 onNext，只有 onComplete</li>
 *   <li>{@code Mono.just(null)} 直接抛 NPE；空值要用 {@code Mono.empty()} / {@code Mono.justOrEmpty(null)}</li>
 *   <li>Gateway 的过滤器返回 {@code Mono<Void>} —— 语义就是"整条链做完没做完"</li>
 * </ol>
 *
 * 跑：mvn -pl reactor-lab exec:java -Dexec.mainClass=com.myxhs.demo.reactor.l09.Ex04_MonoVoid
 */
public class Ex04_MonoVoid {

    /** 模拟"写响应"这种只有副作用、没有返回值的动作 */
    static Mono<Void> writeSomething() {
        return Mono.fromRunnable(() -> System.out.println("        >>> 副作用执行（相当于写响应）"))
                .then();
    }

    public static void main(String[] args) {
        System.out.println();
        System.out.println("=== Ex04 Mono<Void> 的语义 ===");
        System.out.println();

        System.out.println("① Mono.empty() —— 完成，但没有值");
        Mono.empty()
                .doOnSuccess(v -> System.out.println("        onSuccess 的值 = " + v + "（null = 没有值）"))
                .subscribe();
        System.out.println();

        System.out.println("② Mono.just(null) —— 直接抛异常（Reactor 不允许 null 值）");
        try {
            Mono<String> bad = Mono.just((String) null);
            System.out.println("        走到了这里？" + bad);
        } catch (Exception e) {
            System.out.println("        " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        System.out.println();

        System.out.println("③ Mono.justOrEmpty(null) —— 安全写法，等价于 empty");
        Mono.justOrEmpty((String) null)
                .doOnSuccess(v -> System.out.println("        onSuccess 的值 = " + v + "（v 是 null）"))
                .subscribe();
        System.out.println();

        System.out.println("④ Mono<Void>：只关心\"做完没\"（Gateway 过滤器返回的就是它）");
        writeSomething()
                .doOnSuccess(v -> System.out.println("        <<< 完成信号到了（v = " + v + "）"))
                .subscribe();
        System.out.println();

        System.out.println("⑤ 用三个回调订阅 Mono<Void>：只会看到 onComplete");
        writeSomething().subscribe(
                v -> System.out.println("        永远看不到这行（没有 onNext）"),
                e -> System.out.println("        onError: " + e),
                () -> System.out.println("        只看到这行（onComplete）"));
        System.out.println();

        System.out.println("对照结论：");
        System.out.println("  · Mono<Void> 的语义 = 没有值，但有【完成/错误】信号");
        System.out.println("  · 所以它可以 .then(...) —— 等它的 onComplete 再接下一条");
        System.out.println("  · 这解释了 Gateway：chain.filter(exchange).then(收尾)");
    }
}
