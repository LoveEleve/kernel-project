package com.myxhs.demo.reactor.l11;

import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Ex01：背压策略 4 种（L11 要掌握的 ①）。
 *
 * <p>场景：生产者 <b>20ms 产一个（共 5 个）</b>，消费者 <b>100ms 处理一个</b>（慢 5 倍），
 * 且消费者【一次只 request(1)】→ 处理期间没有额度 → 溢出。
 *
 * <p>要观察的 4 件事（溢出之后怎么办）：
 * <ol>
 *   <li><b>默认（ERROR）</b>：溢出直接报错（快速失败）</li>
 *   <li>{@code onBackpressureBuffer()}：全部缓存 → 慢消费者<b>最终收全</b></li>
 *   <li>{@code onBackpressureDrop()}：没额度就丢 → <b>收到一部分</b></li>
 *   <li>{@code onBackpressureLatest()}：只留最新一个 → 收到一部分，<b>末尾是最新值</b></li>
 * </ol>
 *
 * <p>跑：{@code mvn -pl reactor-lab exec:java -Dexec.mainClass=com.myxhs.demo.reactor.l11.Ex01_BackpressureStrategies}
 */
public class Ex01_BackpressureStrategies {

    /** 快源：每 20ms 产一个，共 5 个 */
    static Flux<Long> fastSource() {
        return Flux.interval(Duration.ofMillis(20)).take(5);
    }

    static void demo(String name, Function<Flux<Long>, Flux<Long>> strategy) throws InterruptedException {
        List<Long> received = new CopyOnWriteArrayList<>();
        Throwable[] error = new Throwable[1];
        CountDownLatch done = new CountDownLatch(1);

        strategy.apply(fastSource()).subscribe(new BaseSubscriber<Long>() {
            @Override
            protected void hookOnSubscribe(Subscription s) {
                request(1);                          // 一次只要 1 个
            }

            @Override
            protected void hookOnNext(Long v) {
                received.add(v);
                // ★ 关键：慢消费【不能在发射线程上 sleep】——那会把生产者的定时器一起堵住，
                //   就永远看不到溢出。正确做法：在别的线程上延迟 100ms 再要下一个。
                Schedulers.boundedElastic().schedule(
                        () -> request(1), 100, TimeUnit.MILLISECONDS);
            }

            @Override
            protected void hookOnError(Throwable t) {
                error[0] = t;
                done.countDown();
            }

            @Override
            protected void hookOnComplete() {
                done.countDown();
            }
        });

        done.await(3, TimeUnit.SECONDS);
        if (error[0] != null) {
            System.out.printf("    %-22s → ❌ onError: %s%n", name, error[0].getMessage());
        } else {
            System.out.printf("    %-22s → ✅ 收到 %d 个: %s%n", name, received.size(), received);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println();
        System.out.println("=== Ex01 背压策略 4 种 ===");
        System.out.println("（生产者 20ms/个共 5 个；消费者 100ms/个且一次只 request(1)）");
        System.out.println();

        demo("默认（ERROR）", f -> f);
        demo("onBackpressureBuffer", Flux::onBackpressureBuffer);
        demo("onBackpressureDrop", Flux::onBackpressureDrop);
        demo("onBackpressureLatest", Flux::onBackpressureLatest);

        System.out.println();
        System.out.println("对照结论：");
        System.out.println("  · 默认 = 快速失败（报错），让你立刻发现问题");
        System.out.println("  · BUFFER = 不丢数据，但吃内存（生产要设上限）");
        System.out.println("  · DROP   = 丢新的，适合可丢的实时数据（监控指标/日志）");
        System.out.println("  · LATEST = 只留最新，适合\"只要最新状态\"（行情/位置）");
    }
}
