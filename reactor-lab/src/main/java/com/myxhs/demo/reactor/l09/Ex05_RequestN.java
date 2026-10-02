package com.myxhs.demo.reactor.l09;

import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;

/**
 * Ex05：背压可见 —— request(n) 决定"什么时候给你多少"。
 *
 * <p>两个对照实验：
 * <ol>
 *   <li><b>完全不 request</b> → 一个数据都不会来（不是"延迟"，是"根本不动"）</li>
 *   <li><b>一次只要 1 个</b> → 源与消费者交替推进（拉模型）</li>
 * </ol>
 *
 * 跑：mvn -pl reactor-lab exec:java -Dexec.mainClass=com.myxhs.demo.reactor.l09.Ex05_RequestN
 */
public class Ex05_RequestN {

    public static void main(String[] args) {
        System.out.println();
        System.out.println("=== Ex05 背压：消费者不 request，就没有数据 ===");
        System.out.println();

        System.out.println("--- ① 订阅者故意不 request ---");
        Flux.range(1, 3)
                .doOnRequest(n -> System.out.println("        [源  ] 收到 request(" + n + ")"))
                .subscribe(new BaseSubscriber<Integer>() {
                    @Override
                    protected void hookOnSubscribe(Subscription s) {
                        System.out.println("        [Sub ] 拿到订阅，但【故意不 request】");
                    }

                    @Override
                    protected void hookOnNext(Integer v) {
                        System.out.println("        [Sub ] onNext(" + v + ")  ← 不该出现");
                    }
                });
        System.out.println("        （结果：源一个都没发。因为是拉模型，不是推模型）");
        System.out.println();

        System.out.println("--- ② 订阅者一次只要 1 个 ---");
        Flux.range(1, 3)
                .doOnRequest(n -> System.out.println("        [源  ] 收到 request(" + n + ")"))
                .subscribe(new BaseSubscriber<Integer>() {
                    @Override
                    protected void hookOnSubscribe(Subscription s) {
                        System.out.println("        [Sub ] hookOnSubscribe → request(1)");
                        request(1);
                    }

                    @Override
                    protected void hookOnNext(Integer v) {
                        System.out.println("        [Sub ] onNext(" + v + ") → request(1)");
                        request(1);
                    }

                    @Override
                    protected void hookOnComplete() {
                        System.out.println("        [Sub ] onComplete（终态）");
                    }
                });
        System.out.println();

        System.out.println("对照结论：");
        System.out.println("  · 数据节奏完全由消费者掌握（request(n)）—— 这就是背压");
        System.out.println("  · 不 request = 流根本不动（所以 Gateway 里框架替你 request）");
        System.out.println("  · 生产者快了怎么办？→ 背压策略（BUFFER/DROP/LATEST/ERROR），见 L11");
    }
}
