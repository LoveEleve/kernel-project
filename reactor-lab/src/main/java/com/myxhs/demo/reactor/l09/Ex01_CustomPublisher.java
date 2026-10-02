package com.myxhs.demo.reactor.l09;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Ex01：手写 Publisher / Subscriber / Subscription —— 把"信号顺序"变成肉眼可见的输出。
 *
 * <p>为什么要有这个实验：L09 讲的 5 条规范规则，光看文字记不住；
 * 自己实现一遍，规则就"长"在脑子里了。
 *
 * <p>要观察的 3 件事：
 * <ol>
 *   <li>subscribe 之后，第一个信号一定是 onSubscribe（且只来一次）</li>
 *   <li>onNext 的数量 = 累计 request(n)（消费者驱动）</li>
 *   <li>最后是 onComplete（终态），之后不会再有信号</li>
 * </ol>
 *
 * 跑：mvn -pl reactor-lab exec:java -Dexec.mainClass=com.myxhs.demo.reactor.l09.Ex01_CustomPublisher
 */
public class Ex01_CustomPublisher {

    /**
     * 数据源：支持背压的"计数器"（发 0..max-1）。
     * 关键：它不是"想发就发"，而是"只在被 request 的额度内发"。
     */
    static class RangePublisher implements Publisher<Integer> {

        private final int max;

        RangePublisher(int max) {
            this.max = max;
        }

        @Override
        public void subscribe(Subscriber<? super Integer> subscriber) {
            // 规则①：subscribe 后必须【最先】发 onSubscribe，且只能一次
            subscriber.onSubscribe(new Subscription() {
                private int emitted = 0;          // 已发出的个数
                private long requested = 0;       // 累计被请求的额度（规则②：多次 request 累加）
                private boolean cancelled = false;

                @Override
                public void request(long n) {
                    if (n <= 0) {             // 规则②：n <= 0 是违规的
                        subscriber.onError(new IllegalArgumentException("request(n) 必须 > 0，收到 " + n));
                        return;
                    }
                    requested += n;
                    // 规则③：发出的数量不得超过累计请求额度
                    while (!cancelled && emitted < max && emitted < requested) {
                        subscriber.onNext(emitted++);
                    }
                    // 规则⑤：正常发完 → onComplete（终态，只发一次）
                    if (!cancelled && emitted == max) {
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                    // 规则④：cancel 之后不再发任何信号
                    cancelled = true;
                    System.out.println("      [Pub ] 收到 cancel() → 不会再发信号");
                }
            });
        }
    }

    public static void main(String[] args) {
        System.out.println();
        System.out.println("=== Ex01 手写 Publisher：观察信号顺序 ===");
        System.out.println("（源：0..4 共 5 个；订阅者先要 2 个、再要 10 个）");
        System.out.println();

        new RangePublisher(5)
                .subscribe(new Subscriber<Integer>() {

            @Override
            public void onSubscribe(Subscription s) {
                System.out.println("  [Sub ] ← onSubscribe   （第一个信号，只来一次）");
                System.out.println("  [Sub ] → request(2)");
                s.request(2);
                System.out.println("  [Sub ] → request(10)   （累加后额度 = 12，但源只有 5 个）");
                s.request(10);
            }

            @Override
            public void onNext(Integer v) {
                System.out.println("  [Sub ] ← onNext(" + v + ")");
            }

            @Override
            public void onError(Throwable t) {
                System.out.println("  [Sub ] ← onError(" + t.getMessage() + ")");
            }

            @Override
            public void onComplete() {
                System.out.println("  [Sub ] ← onComplete    （终态：之后不会再有信号）");
            }
        });

        System.out.println();
        System.out.println("对照结论：");
        System.out.println("  · onSubscribe 第一个来、只来一次");
        System.out.println("  · onNext 恰好 5 个（≤ 累计请求额度）");
        System.out.println("  · onComplete 是最后一个信号（终态唯一）");
    }
}
