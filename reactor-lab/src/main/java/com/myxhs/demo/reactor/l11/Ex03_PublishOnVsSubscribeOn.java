package com.myxhs.demo.reactor.l11;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Ex03：{@code publishOn} vs {@code subscribeOn}（L11 要掌握的 ③）。
 *
 * <p>版式统一：每一段都打印 <b>上游 map / 下游 map / subscribe</b> 三个阶段各自所在的线程，
 * 这样"谁被切走了"一眼就能看出来。
 *
 * <p>要观察的 4 件事：
 * <ol>
 *   <li>不切线程：三段都在<b>当前线程</b></li>
 *   <li>{@code subscribeOn}：<b>整条链</b>（含上游 map）都在指定调度器上</li>
 *   <li>{@code publishOn}：<b>上游 map 仍在调用线程</b>，只有【它之后】的段被切走</li>
 *   <li>组合拳：{@code subscribeOn(boundedElastic)}（跑阻塞）+ {@code publishOn(parallel)}（跑计算）</li>
 * </ol>
 *
 * <p>跑：{@code ./scripts/run-one.sh l11.Ex03_PublishOnVsSubscribeOn}
 */
public class Ex03_PublishOnVsSubscribeOn {

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    static String me() {
        return Thread.currentThread().getName();
    }

    /** 统一版式：上游 map → 下游 map → subscribe（都打印线程名） */
    static void stage(String title, Flux<Integer> chain, Runnable trailer) {
        System.out.println(title);
        chain.subscribe(v -> System.out.println("    subscribe@" + me()));
        if (trailer != null) {
            trailer.run();
        }
        sleep(300);          // 等异步输出打印完（否则会和下一段交错）
        System.out.println();
    }

    public static void main(String[] args) {
        System.out.println();
        System.out.println("=== Ex03 publishOn vs subscribeOn（看线程名）===");
        System.out.println("（注：Maven exec:java 下 main 线程名可能是全类名，正常现象）");
        System.out.println();

        // ① 不切线程
        stage("① 不切线程：三段都在当前线程",
                Flux.range(1, 2)
                        .map(v -> { System.out.println("    上游 map@" + me()); return v; })
                        .map(v -> { System.out.println("    下游 map@" + me()); return v; }),
                null);

        // ② subscribeOn：整条链（含上游）都被带过去
        stage("② subscribeOn(parallel)：【整条链】都在 parallel（含上游 map）",
                Flux.range(1, 2)
                        .map(v -> { System.out.println("    上游 map@" + me()); return v; })
                        .subscribeOn(Schedulers.parallel())
                        .map(v -> { System.out.println("    下游 map@" + me()); return v; }),
                null);

        // ③ publishOn：上游留在调用线程，只有它之后的段被切走
        stage("③ publishOn(parallel)：上游 map【仍在调用线程】，只有它之后的被切走",
                Flux.range(1, 2)
                        .map(v -> { System.out.println("    上游 map@" + me()); return v; })
                        .publishOn(Schedulers.parallel())
                        .map(v -> { System.out.println("    下游 map@" + me()); return v; }),
                null);

        // ④ 组合拳
        stage("④ 组合拳：subscribeOn(boundedElastic) 跑阻塞 + publishOn(parallel) 跑计算",
                Flux.range(1, 2)
                        .map(v -> { System.out.println("    [阻塞阶段]@" + me()); return v; })
                        .subscribeOn(Schedulers.boundedElastic())
                        .publishOn(Schedulers.parallel())
                        .map(v -> { System.out.println("    [计算阶段]@" + me()); return v; }),
                null);

        System.out.println("对照结论：");
        System.out.println("  · subscribeOn：影响【订阅发生在哪】→ 整条链（含上游）都在那个线程上");
        System.out.println("  · publishOn  ：影响【它之后】→ 上游仍留在调用线程");
        System.out.println("  · 组合拳：subscribeOn(boundedElastic) 处理阻塞 + publishOn(parallel) 处理计算");
        System.out.println("  · Gateway 场景：过滤器里调阻塞客户端 → 必须 subscribeOn(boundedElastic())");
    }
}
