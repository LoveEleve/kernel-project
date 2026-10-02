package com.myxhs.demo.reactor.l10;

import reactor.core.publisher.Mono;

/**
 * Ex01：{@code map} vs {@code flatMap}（对应 L10 · 10.3）。
 *
 * <p>要观察的 3 件事：
 * <ol>
 *   <li>{@code map} 的转换函数返回 <b>普通值</b>（同步转换）</li>
 *   <li>{@code flatMap} 的转换函数返回 <b>Mono</b> → 自动展平，<b>不会</b>变成 {@code Mono<Mono<T>>}</li>
 *   <li>用错会套娃：{@code map} 返回 Mono 时，订阅拿到的是一个"还没订阅的 Mono"</li>
 * </ol>
 *
 * <p>跑：{@code mvn -pl reactor-lab exec:java -Dexec.mainClass=com.myxhs.demo.reactor.l10.Ex01_MapVsFlatMap}
 */
public class Ex01_MapVsFlatMap {

    record User(String name, String orderId) { }

    record Order(String id, String item) { }

    /** 模拟"第一次查询" */
    static Mono<User> getUser() {
        return Mono.just(new User("张三", "O-1001"));
    }

    /** 模拟"再发一次远程调用"（这才是 flatMap 的用武之地） */
    static Mono<Order> getOrder(String orderId) {
        return Mono.fromCallable(() -> {
            System.out.println("        >>> 真正查了一次订单：" + orderId);
            return new Order(orderId, "iPhone");
        });
    }

    public static void main(String[] args) {
        System.out.println();
        System.out.println("=== Ex01 map vs flatMap ===");
        System.out.println();

        System.out.println("① map：同步转换（User → name，普通值）");
        getUser().map(User::name)
                .subscribe(v -> System.out.println("        结果: " + v));
        System.out.println();

        System.out.println("② flatMap：异步展平（User → Mono<Order>）");
        getUser().flatMap(u -> getOrder(u.orderId()))
                .subscribe(v -> System.out.println("        结果: " + v));
        System.out.println();

        System.out.println("③ 反例：用 map 返回 Mono → 套娃（Mono<Mono<Order>>）");
        getUser().map(u -> getOrder(u.orderId()))
                .subscribe(v -> System.out.println("        收到的是: " + v + "  ← 一个【还没订阅】的 Mono！"));
        System.out.println();

        System.out.println("④ 反例的后果：内层那次订单查询根本没发生（上面的 >>> 一次都没打印）");
        System.out.println();

        System.out.println("对照结论：");
        System.out.println("  · 转换函数返回【普通值】→ map");
        System.out.println("  · 转换函数返回【Mono/Flux】→ flatMap（自动展平，不套娃）");
        System.out.println("  · 口诀：下一步要不要\"再发一次远程调用\"？要 → flatMap");
    }
}
