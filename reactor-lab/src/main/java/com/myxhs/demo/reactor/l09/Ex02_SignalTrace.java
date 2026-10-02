package com.myxhs.demo.reactor.l09;

import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;

/**
 * Ex02：用 Reactor 观察四类信号 —— 和 Ex01 手写的对照。
 *
 * <p>要观察的 2 件事：
 * <ol>
 *   <li><b>信号顺序</b>：onSubscribe → request(n) → onNext × n → … → onComplete</li>
 *   <li><b>拉模型</b>：订阅者一次只要 1 个 → 源和消费者"交替"推进（这就是背压的样子）</li>
 * </ol>
 *
 * <p>注意：doOnXxx 只是"旁听"，真正决定数据流节奏的是 Subscriber 的 request。
 *
 * 跑：mvn -pl reactor-lab exec:java -Dexec.mainClass=com.myxhs.demo.reactor.l09.Ex02_SignalTrace
 */
public class Ex02_SignalTrace {

    public static void main(String[] args) {
        System.out.println();
        System.out.println("=== Ex02 用 Reactor 观察信号（订阅者一次只要 1 个）===");
        System.out.println();
        // doXxx()是什么呢? 就是旁路代码（打日志，做观察）
        Flux.range(1, 3)
                .doOnSubscribe(s -> System.out.println("  [信号] onSubscribe"))
                .doOnRequest(n -> System.out.println("  [信号] request(" + n + ")"))
                .doOnNext(v -> System.out.println("  [信号] onNext(" + v + ")"))
                .doOnComplete(() -> System.out.println("  [信号] onComplete（终态）"))
                .doOnError(e -> System.out.println("  [信号] onError(" + e + ")（终态）"))
                // BaseSubscriber: Reactor 提供的一个简化版 Subscriber 基类
                .subscribe(new BaseSubscriber<Integer>() {

                    @Override
                    protected void hookOnSubscribe(Subscription s) {
                        System.out.println("  [Sub ] hookOnSubscribe：先要 1 个");
                        request(1);
                    }

                    @Override
                    protected void hookOnNext(Integer v) {
                        System.out.println("  [Sub ] hookOnNext(" + v + ")：再要 1 个");
                        request(1);
                    }

                    @Override
                    protected void hookOnComplete() {
                        System.out.println("  [Sub ] hookOnComplete：结束");
                    }
                });

        System.out.println();
        System.out.println("对照结论：");
        System.out.println("  · 源和消费者【交替】推进：request(1) → onNext → request(1) → onNext …");
        System.out.println("  · 如果订阅者不 request，就一个数据都不会来（背压 = 消费者驱动）");
        System.out.println("  · doOnXxx 只旁听，不改变节奏");
    }
}
