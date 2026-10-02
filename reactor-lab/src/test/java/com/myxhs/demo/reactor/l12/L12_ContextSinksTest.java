package com.myxhs.demo.reactor.l12;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把 L12 的 Context / Sinks 语义变成断言。
 *
 * <p>跑：{@code mvn -pl reactor-lab test}
 */
class L12_ContextSinksTest {

    @Test
    void context_is_read_from_bottom_up() {
        Mono<String> mono = Mono.deferContextual(ctx -> Mono.just("v=" + ctx.get("k")))
                .contextWrite(Context.of("k", "V1"));
        StepVerifier.create(mono).expectNext("v=V1").verifyComplete();
    }

    @Test
    void context_follows_the_subscription() {
        // 同一段链，两次订阅写不同 Context；不写则读不到
        Mono<String> chain = Mono.deferContextual(ctx -> Mono.just("k=" + ctx.getOrDefault("k", "<none>")));

        StepVerifier.create(chain.contextWrite(Context.of("k", "A")))
                .expectNext("k=A").verifyComplete();
        StepVerifier.create(chain.contextWrite(Context.of("k", "B")))
                .expectNext("k=B").verifyComplete();
        StepVerifier.create(chain)
                .expectNext("k=<none>").verifyComplete();
    }

    @Test
    void context_survives_thread_switch() {
        Mono<String> mono = Mono.deferContextual(ctx -> Mono.just(
                        Thread.currentThread().getName() + "|" + ctx.get("k")))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .contextWrite(Context.of("k", "T"));

        StepVerifier.create(mono)
                .expectNextMatches(v -> v.startsWith("boundedElastic") && v.endsWith("|T"))
                .verifyComplete();
    }

    @Test
    void sinks_one_emits_single_value() {
        Sinks.One<String> one = Sinks.one();
        StepVerifier.create(one.asMono())
                .then(() -> one.tryEmitValue("hello"))
                .expectNext("hello")
                .verifyComplete();
    }

    @Test
    void sinks_many_direct_requires_subscriber() {
        Sinks.Many<String> hot = Sinks.many().multicast().directBestEffort();
        assertThat(hot.tryEmitNext("nobody"))
                .as("directBestEffort 无订阅者 → FAIL_ZERO_SUBSCRIBER（不抛异常）")
                .isEqualTo(Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER);
    }

    @Test
    void sinks_many_buffer_keeps_events_without_subscriber() {
        Sinks.Many<String> buffered = Sinks.many().multicast().onBackpressureBuffer();
        assertThat(buffered.tryEmitNext("b0")).isEqualTo(Sinks.EmitResult.OK);

        StepVerifier.create(buffered.asFlux())
                .expectNext("b0")           // 订阅前的事件被缓冲下来了
                .thenCancel()
                .verify();
    }
}
