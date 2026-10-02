package com.myxhs.demo.reactor.l10;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Ex03：过滤与兜底（对应 L10 · 10.5）。
 *
 * <p>要观察的 4 件事：
 * <ol>
 *   <li>{@code filter}：不满足条件 → 变成空流（不是抛错）</li>
 *   <li>{@code defaultIfEmpty(v)}：空 → 给<b>默认值</b></li>
 *   <li>{@code switchIfEmpty(publisher)}：空 → 换<b>另一条流</b>（注意：非空时它<b>不会被订阅</b>）</li>
 *   <li>{@code onErrorReturn} / {@code onErrorResume}：<b>错误</b>兜底（空 vs 错误是两回事）</li>
 * </ol>
 *
 * <p>跑：{@code mvn -pl reactor-lab exec:java -Dexec.mainClass=com.myxhs.demo.reactor.l10.Ex03_FilterAndFallback}
 */
public class Ex03_FilterAndFallback {

    static void log(String s) {
        System.out.println("        " + s);
    }

    public static void main(String[] args) {
        System.out.println();
        System.out.println("=== Ex03 过滤与兜底 ===");
        System.out.println();

        System.out.println("① filter：不满足 → 直接过滤掉（不抛错）");
        Flux.range(1, 5)
                .filter(v -> v % 2 == 0)
                .subscribe(v -> log("保留: " + v));
        System.out.println();

        System.out.println("② defaultIfEmpty：空 → 给默认【值】");
        Mono.<String>empty()
                .defaultIfEmpty("默认值")
                .subscribe(v -> log("收到: " + v));
        System.out.println();

        System.out.println("③ switchIfEmpty：空 → 换一条【流】（可以做进一步调用）");
        Mono.<String>empty()
                .switchIfEmpty(Mono.fromCallable(() -> {
                    log(">>> 兜底流被订阅了（相当于去查备份源）");
                    return "从备份源取到的值";
                }))
                .subscribe(v -> log("收到: " + v));
        System.out.println();

        System.out.println("④ 对照：非空时，switchIfEmpty 里的流【不会被订阅】（不会白调一次）");
        Mono.just("本来就有值")
                .switchIfEmpty(Mono.fromCallable(() -> {
                    log(">>> 这行【不该出现】");
                    return "X";
                }))
                .subscribe(v -> log("收到: " + v));
        System.out.println();

        System.out.println("⑤ 错误兜底：onErrorReturn（固定值）");
        Mono.<String>error(new IllegalStateException("下游挂了"))
                .onErrorReturn("错误兜底值")
                .subscribe(v -> log("收到: " + v));
        System.out.println();

        System.out.println("⑥ 错误兜底：onErrorResume（换成另一条流，可带原因）");
        Mono.<String>error(new IllegalStateException("下游挂了"))
                .onErrorResume(e -> Mono.just("降级数据（原因: " + e.getMessage() + "）"))
                .subscribe(v -> log("收到: " + v));
        System.out.println();

        System.out.println("对照结论：");
        System.out.println("  · \"空\"（onComplete 无值）用 defaultIfEmpty / switchIfEmpty");
        System.out.println("  · \"错误\"（onError）用 onErrorReturn / onErrorResume —— 两者不是一回事");
        System.out.println("  · switchIfEmpty 的流是【惰性】的：非空时不会被订阅（所以不会白调）");
    }
}
