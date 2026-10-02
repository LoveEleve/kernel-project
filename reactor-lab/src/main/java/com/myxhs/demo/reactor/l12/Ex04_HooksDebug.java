package com.myxhs.demo.reactor.l12;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;

import java.util.Arrays;

/**
 * Ex04：{@code Hooks.onOperatorDebug()} —— 全局组装栈（L12 · 12.4 的补充演示）。
 *
 * <p>对比三态：
 * <ol>
 *   <li><b>不开</b>：异常里只有"运行位置"（reactor 内部帧 + 你的 lambda 帧各一个），看不到"链是在哪组装的"</li>
 *   <li><b>开启</b>：异常里多出 <b>Assembly trace</b>（每个算子的<b>组装位置</b>，含你的业务代码行号）</li>
 *   <li><b>关闭</b>：恢复原状</li>
 * </ol>
 *
 * <p>⚠️ 开销大（每个算子都要抓组装栈），<b>生产禁用</b>；本地排查时开，排查完关。
 *
 * <p>跑：{@code ./scripts/run-one.sh l12.Ex04_HooksDebug}
 */
public class Ex04_HooksDebug {

    /** 组装一条会出错的链（注意看行号：这就是"组装位置"） */
    static Flux<Integer> chain() {
        return Flux.range(1, 3)
                .map(i -> i * 2)                                  // ← 组装位置 A（第 26 行）
                .map(i -> {                                       // ← 组装位置 B（第 27~30 行）
                    if (i == 4) {
                        throw new IllegalStateException("boom");
                    }
                    return i;
                });
    }

    static void runAndShow(String title) {
        System.out.println(title);
        try {
            chain().blockLast();
        } catch (Exception e) {
            System.out.println("    主 message: " + e.getMessage());

            boolean foundAssembly = false;
            // ① 看主 message
            foundAssembly |= printIfAssembly(e.getMessage(), "    [message] ");
            // ② 看 suppressed（Reactor 通常把组装信息放这儿）
            for (Throwable sup : e.getSuppressed()) {
                foundAssembly |= printIfAssembly(String.valueOf(sup.getMessage()), "    [suppressed] ");
            }
            if (!foundAssembly) {
                System.out.println("    ❌ 没有 Assembly trace（看不到链的组装位置）");
            }

            // ③ 堆栈里的业务帧
            long bizFrames = Arrays.stream(e.getStackTrace())
                    .filter(f -> f.getClassName().contains("Ex04_HooksDebug"))
                    .count();
            System.out.println("    堆栈里的业务帧数: " + bizFrames);
        }
        System.out.println();
    }

    /** 打印含 "Assembly trace" / "Error has been observed" 的内容；返回是否找到 */
    static boolean printIfAssembly(String msg, String prefix) {
        if (msg == null || (!msg.contains("Assembly trace") && !msg.contains("Error has been observed"))) {
            return false;
        }
        for (String line : msg.split("\n")) {
            if (!line.isBlank()) {
                System.out.println(prefix + line);
            }
        }
        return true;
    }

    public static void main(String[] args) {
        System.out.println();
        System.out.println("=== Ex04 Hooks.onOperatorDebug() ===");
        System.out.println();

        runAndShow("① 默认（不开 Hooks）：只有运行位置");

        System.out.println("② 开启 Hooks.onOperatorDebug() 后");
        Hooks.onOperatorDebug();
        runAndShow("   （Hooks 已开）");

        Hooks.resetOnOperatorDebug();
        runAndShow("③ 关闭 Hooks.disableOperatorDebug() 后：恢复原状");

        System.out.println("对照结论：");
        System.out.println("  · 不开：只能看到【运行位置】—— 响应式堆栈难读的根源");
        System.out.println("  · 开启：多出【Assembly trace】= 每个算子的组装位置（含业务行号）← 排查神器");
        System.out.println("  · ⚠️ 全局开关 + 开销大：只本地开、排查完关（生产用 checkpoint 精准打点）");
        System.out.println("  · 更轻量的替代：ReactorDebugAgent.init()（Java agent，按需开启）");
    }
}
