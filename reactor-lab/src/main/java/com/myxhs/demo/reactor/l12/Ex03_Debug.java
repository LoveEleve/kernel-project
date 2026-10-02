package com.myxhs.demo.reactor.l12;

import reactor.core.publisher.Flux;

/**
 * Ex03：调试三件套（L12 · 12.4）。
 *
 * <p>要观察的 3 件事：
 * <ol>
 *   <li>{@code log()}：打印<b>信号流</b>（onSubscribe / request / onNext / onComplete + 线程）</li>
 *   <li>{@code checkpoint("说明")}：异常里留下"<b>路标</b>"（assembly traceback）—— 响应式堆栈难读的解药</li>
 *   <li>{@code Hooks.onOperatorDebug()}：<b>全局</b>组装栈，开销大（生产禁用）—— 只说明不开启</li>
 * </ol>
 *
 * <p>跑：{@code ./scripts/run-one.sh l12.Ex03_Debug}
 */
public class Ex03_Debug {

    public static void main(String[] args) {
        System.out.println();
        System.out.println("=== Ex03 调试三件套 ===");
        System.out.println();

        System.out.println("① log()：看信号流（含线程）");
        Flux.range(1, 3)
                .map(i -> i * 2)
                .log("【我的链】")
                .subscribe(v -> System.out.println("    订阅者收到: " + v));
        System.out.println();

        System.out.println("② checkpoint(\"说明\")：异常堆栈里留路标");
        try {
            Flux.range(1, 3)
                    .map(i -> {
                        if (i == 2) {
                            throw new IllegalStateException("boom");
                        }
                        return i;
                    })
                    .checkpoint("【订单查询之后的 map】")
                    .blockLast();
        } catch (Exception e) {
            System.out.println("    异常类型: " + e.getClass().getSimpleName());
            System.out.println("    异常 message: " + e.getMessage());
            // ★ checkpoint 的路标在【suppressed 异常】的 message 里：
            //    "Error has been observed at the following site(s): *__checkpoint ⇢ 【你的说明】"
            System.out.println("    suppressed 数 = " + e.getSuppressed().length);
            for (Throwable sup : e.getSuppressed()) {
                String msg = String.valueOf(sup.getMessage());
                if (msg.contains("checkpoint") || msg.contains("路标")) {
                    System.out.println("    ★ 找到路标（在 suppressed 里）:");
                    for (String line : msg.split("\n")) {
                        if (!line.isBlank()) {
                            System.out.println("        " + line);
                        }
                    }
                }
            }
            System.out.println("    原始堆栈前 3 帧（看不到业务语义 —— 这就是响应式堆栈难读的原因）:");
            for (int i = 0; i < Math.min(3, e.getStackTrace().length); i++) {
                System.out.println("      " + e.getStackTrace()[i]);
            }
        }
        System.out.println();

        System.out.println("③ Hooks.onOperatorDebug()：全局组装栈（开销大，生产禁用）");
        System.out.println("    用法（本地排查时）：");
        System.out.println("      Hooks.onOperatorDebug();   // 启动时开");
        System.out.println("      ...复现异常...");
        System.out.println("      Hooks.disableOperatorDebug();");
        System.out.println();

        System.out.println("对照结论：");
        System.out.println("  · 优先 checkpoint(\"业务语义说明\") —— 精准、开销小，异常里直接看到路标");
        System.out.println("  · log() 用来看【信号节奏 + 线程】，排查【卡住/不发出】类问题");
        System.out.println("  · Hooks.onOperatorDebug() 是全局开关，只在本地复现时用");
    }
}
