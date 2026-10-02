package com.myxhs.demo.reactor.l12;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Ex02：{@code Sinks}（L12 要掌握的 ②）—— 把"回调式 API"变成流。
 *
 * <p>要观察的 4 件事：
 * <ol>
 *   <li>{@code Sinks.one()}：手工推【一个】值 → `asMono()`</li>
 *   <li><b>multicast 三种口味的区别</b>（无订阅者时怎么办）—— 这决定 {@code EmitResult} 是 OK 还是 FAIL_ZERO_SUBSCRIBER</li>
 *   <li>{@code directBestEffort} 是<b>纯热流</b>：订阅<b>之前</b>的事件收不到</li>
 *   <li>{@code onBackpressureBuffer} 会<b>缓冲</b>：首个订阅者能收到订阅前的事件</li>
 * </ol>
 *
 * <p>跑：{@code ./scripts/run-one.sh l12.Ex02_Sinks}
 */
public class Ex02_Sinks {

    /** 模拟"回调式"的第三方 SDK（它只会喊 onEvent，不认识 Reactor） */
    static class LegacySdk {
        interface Listener {
            void onEvent(String event);
        }

        private static Listener listener;

        static void register(Listener l) {
            listener = l;
        }

        static void fire(String event) {
            if (listener != null) {
                listener.onEvent(event);
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println();
        System.out.println("=== Ex02 Sinks ===");
        System.out.println();

        System.out.println("① Sinks.one()：推一个值（把回调结果变成 Mono）");
        Sinks.One<String> one = Sinks.one();
        Mono<String> mono = one.asMono();
        mono.subscribe(v -> System.out.println("    订阅者收到: " + v));
        System.out.println("    tryEmitValue → " + one.tryEmitValue("回调结果"));
        System.out.println();

        System.out.println("② multicast 三种口味：无订阅者时怎么办（★ 关键区别）");
        System.out.println("    onBackpressureBuffer 无订阅者 → "
                + Sinks.many().multicast().onBackpressureBuffer().tryEmitNext("x") + "   （缓冲，不失败）");
        System.out.println("    directBestEffort     无订阅者 → "
                + Sinks.many().multicast().directBestEffort().tryEmitNext("x"));
        System.out.println("    directAllOrNothing   无订阅者 → "
                + Sinks.many().multicast().directAllOrNothing().tryEmitNext("x"));
        System.out.println();

        System.out.println("③ 纯热流（directBestEffort）：订阅【之前】的事件收不到");
        Sinks.Many<String> hot = Sinks.many().multicast().directBestEffort();
        System.out.println("    （先，无订阅者）emit e0 → " + hot.tryEmitNext("e0"));
        hot.asFlux().subscribe(v -> System.out.println("    订阅者收到: " + v));
        System.out.println("    （后）emit e1 → " + hot.tryEmitNext("e1"));
        System.out.println("    （后）emit e2 → " + hot.tryEmitNext("e2"));
        System.out.println();

        System.out.println("④ 对比（onBackpressureBuffer）：订阅【之前】的事件被缓冲，首个订阅者能补收到");
        Sinks.Many<String> buffered = Sinks.many().multicast().onBackpressureBuffer();
        System.out.println("    （先，无订阅者）emit b0 → " + buffered.tryEmitNext("b0"));
        buffered.asFlux().subscribe(v -> System.out.println("    订阅者收到: " + v));
        System.out.println("    （后）emit b1 → " + buffered.tryEmitNext("b1"));
        System.out.println();

        System.out.println("⑤ 典型用途：把回调式 SDK 接到流上（回调 → 流）");
        Sinks.Many<String> bus = Sinks.many().multicast().directBestEffort();
        LegacySdk.register(event -> bus.tryEmitNext(event));          // 回调里往 Sinks 推
        bus.asFlux().subscribe(e -> System.out.println("    链上收到 SDK 事件: " + e));
        LegacySdk.fire("event-A");
        LegacySdk.fire("event-B");
        Thread.sleep(200);
        System.out.println();

        System.out.println("对照结论：");
        System.out.println("  · Sinks = 你手工往里推数据的 Publisher：asMono() / asFlux() 暴露给下游");
        System.out.println("  · tryEmitXxx 返回 EmitResult（OK / FAIL_ZERO_SUBSCRIBER / FAIL_OVERFLOW / FAIL_CANCELLED）—— 不抛异常");
        System.out.println("  · ⚠️ 不检查返回值 = 静默丢事件；选哪种 multicast 口味取决于【要不要缓冲/能不能丢】");
        System.out.println("  · Gateway 源码零引用 Sinks；SSE/事件聚合类场景才需要它");
    }
}
