package com.myxhs.demo.reactor.l11;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ex04：{@code ParallelFlux}（L11 · 11.5）。
 *
 * <p>要观察的 3 件事：
 * <ol>
 *   <li>串行：4 个"耗时 300ms"的任务 ≈ <b>1.2s</b></li>
 *   <li>{@code parallel(4)} <b>不配 runOn</b> → 仍在调用线程上串行（≈1.2s）—— 证明"parallel 单独用不会并行"</li>
 *   <li>{@code parallel(4).runOn(parallel)} → 4 条轨道真并行 ≈ <b>0.3s</b>，且用到多条线程</li>
 * </ol>
 *
 * <p>跑：{@code mvn -pl reactor-lab exec:java -Dexec.mainClass=com.myxhs.demo.reactor.l11.Ex04_ParallelFlux}
 */
public class Ex04_ParallelFlux {

    static int cpuWork(int i) {
        try {
            Thread.sleep(300);            // 假装 300ms 的计算
        } catch (InterruptedException ignored) {
        }
        return i;
    }

    public static void main(String[] args) {
        System.out.println();
        System.out.println("=== Ex04 ParallelFlux：parallel + runOn 才是真并行 ===");
        System.out.println("（4 个任务，每个耗时 300ms）");
        System.out.println();

        // ① 串行基线
        long t1 = System.currentTimeMillis();
        Flux.range(1, 4)
                .map(Ex04_ParallelFlux::cpuWork)
                .blockLast();
        long serialMs = System.currentTimeMillis() - t1;
        System.out.printf("    ① 串行（普通 map）                          → %d ms%n", serialMs);

        // ② parallel 但不 runOn
        long t2 = System.currentTimeMillis();
        Flux.range(1, 4)
                .parallel(4)                       // 只分轨道，不指定调度器
                .map(Ex04_ParallelFlux::cpuWork)
                .sequential()
                .blockLast();
        long noRunOnMs = System.currentTimeMillis() - t2;
        System.out.printf("    ② parallel(4) 但【不 runOn】                → %d ms（还是串行！）%n", noRunOnMs);

        // ③ parallel + runOn
        Set<String> threads = ConcurrentHashMap.newKeySet();
        long t3 = System.currentTimeMillis();
        Flux.range(1, 4)
                .parallel(4)
                .runOn(Schedulers.parallel())      // ★ 真正指定执行器
                .map(i -> {
                    threads.add(Thread.currentThread().getName());
                    return cpuWork(i);
                })
                .sequential()
                .blockLast();
        long parallelMs = System.currentTimeMillis() - t3;
        System.out.printf("    ③ parallel(4) + runOn(parallel)             → %d ms（真并行）%n", parallelMs);

        System.out.println();
        System.out.println("    用到的线程: " + threads);
        System.out.println();

        System.out.println("对照结论：");
        System.out.println("  · parallel(n) 单独用【不会】并行 —— 必须配 runOn(scheduler)");
        System.out.println("  · 适用场景：CPU 密集的批量处理（元素多、每个都吃 CPU）");
        System.out.println("  · 对比 publishOn：publishOn 只是换线程（元素仍串行），parallel+runOn 才是分片并行");
    }
}
