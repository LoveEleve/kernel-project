package com.myxhs.demo.reactor.l10;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把 L10 的算子行为变成断言。
 *
 * <p>跑：{@code mvn -pl reactor-lab test}
 */
class L10_OperatorsTest {

    @Test
    void map_and_flatMap_both_give_flat_result() {
        // map：返回普通值
        StepVerifier.create(Mono.just("a").map(String::toUpperCase))
                .expectNext("A")
                .verifyComplete();

        // flatMap：返回 Mono → 自动展平（结果同样是 "A"，不是 Mono）
        StepVerifier.create(Mono.just("a").flatMap(s -> Mono.just(s.toUpperCase())))
                .expectNext("A")
                .verifyComplete();
    }

    @Test
    void then_drops_value_while_thenReturn_keeps() {
        // then() 丢弃值 → 没有 onNext
        StepVerifier.create(Mono.just("DATA").then())
                .verifyComplete();

        // thenReturn 给固定值
        StepVerifier.create(Mono.just("DATA").thenReturn("FIXED"))
                .expectNext("FIXED")
                .verifyComplete();
    }

    @Test
    void doOnNext_only_listens() {
        StepVerifier.create(Flux.range(1, 3).doOnNext(v -> { /* 旁听，不做任何事 */ }))
                .expectNext(1, 2, 3)
                .verifyComplete();
    }

    @Test
    void switchIfEmpty_is_lazy() {
        AtomicBoolean subscribed = new AtomicBoolean(false);
        Mono<String> fallback = Mono.fromCallable(() -> {
            subscribed.set(true);
            return "FALLBACK";
        });

        // 非空 → 兜底流不应被订阅
        StepVerifier.create(Mono.just("VALUE").switchIfEmpty(fallback))
                .expectNext("VALUE")
                .verifyComplete();
        assertThat(subscribed).as("非空时兜底流不应被订阅").isFalse();

        // 空 → 才订阅兜底流
        StepVerifier.create(Mono.<String>empty().switchIfEmpty(fallback))
                .expectNext("FALLBACK")
                .verifyComplete();
        assertThat(subscribed).as("空时才订阅兜底流").isTrue();
    }

    @Test
    void empty_and_error_are_two_different_things() {
        // 空：onComplete 没值 → defaultIfEmpty
        StepVerifier.create(Mono.<String>empty().defaultIfEmpty("DEFAULT"))
                .expectNext("DEFAULT")
                .verifyComplete();

        // 错误：onError → onErrorReturn
        StepVerifier.create(Mono.<String>error(new IllegalStateException("boom")).onErrorReturn("ERR"))
                .expectNext("ERR")
                .verifyComplete();
    }

    @Test
    void filter_removes_non_matching() {
        StepVerifier.create(Flux.range(1, 5).filter(v -> v % 2 == 0))
                .expectNext(2, 4)
                .verifyComplete();
    }
}
