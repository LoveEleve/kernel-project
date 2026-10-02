package com.myxhs.demo.reactor.l11;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ex02：{@code Schedulers} 三种（L11 要掌握的 ②）。
 *
 * <p>要观察的 3 件事：
 * <ol>
 *   <li>三种调度器的<b>线程名</b>（`parallel-N` / `boundedElastic-N` / `single-N`）</li>
 *   <li>它们的<b>默认规模</b>（源码常量：parallel = CPU 核数；boundedElastic = 10×CPU、每线程 10 万队列）</li>
 *   <li>同一批任务分别丢到三种调度器上，实际用了几条线程</li>
 * </ol>
 *
 * <p>跑：{@code mvn -pl reactor-lab exec:java -Dexec.mainClass=com.myxhs.demo.reactor.l11.Ex02_Schedulers}
 */
public class Ex02_Schedulers {

    static void show(String label, Mono<String> source) {
        // 用 block() 保证输出顺序（只为演示；生产代码不这么写）
        String name = source.block();
        System.out.printf("    %-32s → 线程: %s%n", label, name);
    }

    /** 跑 32 个任务，看实际用到几条线程 */
    static int usedThreads(reactor.core.scheduler.Scheduler scheduler) {
        Set<String> threads = ConcurrentHashMap.newKeySet();
        Flux.range(1, 32)
                .flatMap(i -> Mono.fromCallable(() -> Thread.currentThread().getName())
                        .subscribeOn(scheduler))
                .doOnNext(threads::add)
                .blockLast();
        return threads.size();
    }

    public static void main(String[] args) {
        System.out.println();
        System.out.println("=== Ex02 Schedulers 三种 ===");
        System.out.println();

        System.out.println("CPU 核数 = " + Runtime.getRuntime().availableProcessors());
        System.out.println("parallel       默认线程数 = " + Schedulers.DEFAULT_POOL_SIZE + "（= CPU 核数）");
        System.out.println("boundedElastic 默认线程数 = " + Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE + "（= 10 × CPU）");
        System.out.println("boundedElastic 每线程队列 = " + Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE + " 个任务");
        System.out.println();

        System.out.println("① 线程名（用 subscribeOn 把任务丢到对应调度器）");
        show("默认（不切线程）", Mono.fromCallable(() -> Thread.currentThread().getName()));
        show("Schedulers.parallel()", Mono.fromCallable(() -> Thread.currentThread().getName())
                .subscribeOn(Schedulers.parallel()));
        show("Schedulers.boundedElastic()", Mono.fromCallable(() -> Thread.currentThread().getName())
                .subscribeOn(Schedulers.boundedElastic()));
        show("Schedulers.single()", Mono.fromCallable(() -> Thread.currentThread().getName())
                .subscribeOn(Schedulers.single()));
        System.out.println();

        System.out.println("② 32 个任务实际用到几条线程");
        System.out.println("    parallel        → " + usedThreads(Schedulers.parallel()) + " 条");
        System.out.println("    boundedElastic  → " + usedThreads(Schedulers.boundedElastic()) + " 条");
        System.out.println("    single          → " + usedThreads(Schedulers.single()) + " 条");
        System.out.println();

        System.out.println("对照结论：");
        System.out.println("  · parallel       = CPU 密集（线程数 = CPU 核数，别放阻塞任务）");
        System.out.println("  · boundedElastic = 阻塞逃生舱（弹性扩到 10×CPU，每线程 10 万队列）");
        System.out.println("  · single         = 全局串行（顺序敏感的场景）");
        System.out.println("  · Gateway 相关：阻塞代码一律 subscribeOn(boundedElastic())（L10 的坑 2）");
    }
}
