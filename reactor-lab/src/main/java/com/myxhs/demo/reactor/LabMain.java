package com.myxhs.demo.reactor;

import com.myxhs.demo.reactor.l00.Ex01_MinimalHttpServerClient;
import com.myxhs.demo.reactor.l00.Ex02_MinimalTcpServerClient;
import com.myxhs.demo.reactor.l00.Ex03_MinimalWebFlux;
import com.myxhs.demo.reactor.l09.Ex01_CustomPublisher;
import com.myxhs.demo.reactor.l10.Ex01_MapVsFlatMap;
import com.myxhs.demo.reactor.l10.Ex02_ThenAndDoOn;
import com.myxhs.demo.reactor.l10.Ex03_FilterAndFallback;
import com.myxhs.demo.reactor.l11.Ex01_BackpressureStrategies;
import com.myxhs.demo.reactor.l11.Ex02_Schedulers;
import com.myxhs.demo.reactor.l11.Ex03_PublishOnVsSubscribeOn;
import com.myxhs.demo.reactor.l11.Ex04_ParallelFlux;
import com.myxhs.demo.reactor.l11.Ex05_EventLoopVsScheduler;
import com.myxhs.demo.reactor.l12.Ex01_Context;
import com.myxhs.demo.reactor.l12.Ex02_Sinks;
import com.myxhs.demo.reactor.l12.Ex03_Debug;
import com.myxhs.demo.reactor.l12.Ex04_HooksDebug;
import com.myxhs.demo.reactor.l09.Ex02_SignalTrace;
import com.myxhs.demo.reactor.l09.Ex03_Laziness;
import com.myxhs.demo.reactor.l09.Ex04_MonoVoid;
import com.myxhs.demo.reactor.l09.Ex05_RequestN;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * reactor-lab 总入口：按课号顺序跑全部实验。
 *
 * <p>用法：
 * <pre>
 *   mvn -pl reactor-lab exec:java                                        # 跑全部
 *   mvn -pl reactor-lab exec:java -Dexec.mainClass=...l09.Ex03_Laziness   # 只跑一个
 * </pre>
 *
 * <p>加新实验：在 {@link #LABS} 里加一行（保持"课号/实验名 → 方法引用"的结构）。
 */
public class LabMain {

    /** 按课号 + 实验号排序（LinkedHashMap 保序） */
    private static final Map<String, Runnable> LABS = new LinkedHashMap<>();

    static {
        // ---------- L00 前置 · 0.4.4 Reactor Netty 传输层 ----------
        LABS.put("L00/Ex01 HttpServer + HttpClient 最小使用", () -> Ex01_MinimalHttpServerClient.main(new String[0]));
        LABS.put("L00/Ex02 TcpServer + TcpClient 最小使用（对比）", () -> Ex02_MinimalTcpServerClient.main(new String[0]));
        LABS.put("L00/Ex03 Spring WebFlux 最小使用", () -> Ex03_MinimalWebFlux.main(new String[0]));

        // ---------- L09 Reactive Streams 与 Reactor 模型 ----------
        LABS.put("L09/Ex01 手写 Publisher（信号顺序）", () -> Ex01_CustomPublisher.main(new String[0]));
        LABS.put("L09/Ex02 Reactor 信号轨迹（拉模型）", () -> Ex02_SignalTrace.main(new String[0]));
        LABS.put("L09/Ex03 惰性（构造 ≠ 执行）", () -> Ex03_Laziness.main(new String[0]));
        LABS.put("L09/Ex04 Mono<Void> 的语义", () -> Ex04_MonoVoid.main(new String[0]));
        LABS.put("L09/Ex05 背压可见（request(n)）", () -> Ex05_RequestN.main(new String[0]));

        // ---------- L10 Reactor 算子 ----------
        LABS.put("L10/Ex01 map vs flatMap", () -> Ex01_MapVsFlatMap.main(new String[0]));
        LABS.put("L10/Ex02 then vs doOnXxx", () -> Ex02_ThenAndDoOn.main(new String[0]));
        LABS.put("L10/Ex03 过滤与兜底", () -> Ex03_FilterAndFallback.main(new String[0]));

        // ---------- L11 背压与调度 ----------
        LABS.put("L11/Ex01 背压策略 4 种", () -> {
            try {
                Ex01_BackpressureStrategies.main(new String[0]);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        LABS.put("L11/Ex02 Schedulers 三种", () -> Ex02_Schedulers.main(new String[0]));
        LABS.put("L11/Ex03 publishOn vs subscribeOn", () -> Ex03_PublishOnVsSubscribeOn.main(new String[0]));
        LABS.put("L11/Ex04 ParallelFlux", () -> Ex04_ParallelFlux.main(new String[0]));
        LABS.put("L11/Ex05 EventLoop vs Schedulers（真实验）", () -> Ex05_EventLoopVsScheduler.main(new String[0]));

        // ---------- L12 Context / Sinks / 调试 ----------
        LABS.put("L12/Ex01 Context（写/读/跨线程/桥接 attributes）", () -> {
            try {
                Ex01_Context.main(new String[0]);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        LABS.put("L12/Ex02 Sinks（回调 → 流 / EmitResult）", () -> {
            try {
                Ex02_Sinks.main(new String[0]);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        LABS.put("L12/Ex03 调试三件套（log / checkpoint）", () -> Ex03_Debug.main(new String[0]));
        LABS.put("L12/Ex04 Hooks.onOperatorDebug（Assembly trace）", () -> Ex04_HooksDebug.main(new String[0]));
    }

    public static void main(String[] args) {
        int total = LABS.size();
        System.out.println("==============================================================");
        System.out.println("reactor-lab | 共 " + total + " 个实验（按课号顺序）");
        System.out.println("==============================================================");

        int i = 1;
        for (Map.Entry<String, Runnable> e : LABS.entrySet()) {
            System.out.println();
            System.out.println("--------------------------------------------------------------");
            System.out.println("[" + i++ + "/" + total + "] " + e.getKey());
            System.out.println("--------------------------------------------------------------");
            e.getValue().run();
        }

        System.out.println();
        System.out.println("全部实验跑完。");
    }
}
