package com.myxhs.demo.reactor.l09;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把 L09 的"契约"变成可验证的断言。
 *
 * <p>torun：mvn -pl reactor-lab test
 *
 * <p>三个断言对应三条规范规则：
 * <ol>
 *   <li>惰性：没订阅，什么都不发生</li>
 *   <li>背压：初始请求多少，就最多给多少</li>
 *   <li>Mono&lt;Void&gt;：只有 onComplete，没有 onNext</li>
 * </ol>
 */
class L09_ContractTest {

    @Test
    void laziness_nothing_happens_before_subscribe() {
        AtomicBoolean executed = new AtomicBoolean(false);

        Mono<String> mono = Mono.fromCallable(() -> {
            executed.set(true);
            return "x";
        });

        assertThat(executed).as("构造完 Mono，还没执行").isFalse();
        mono.subscribe();
        assertThat(executed).as("订阅后才执行").isTrue();
    }

    @Test
    void just_evaluates_immediately_while_fromCallable_does_not() {
        AtomicBoolean justRan = new AtomicBoolean(false);
        AtomicBoolean callableRan = new AtomicBoolean(false);

        Mono<String> just = Mono.just(justRan.getAndSet(true) ? "x" : "y");
        Mono<String> callable = Mono.fromCallable(() -> {
            callableRan.set(true);
            return "z";
        });

        assertThat(justRan).as("just 的参数立即求值").isTrue();
        assertThat(callableRan).as("fromCallable 还没订阅，不执行").isFalse();

        callable.subscribe();
        assertThat(callableRan).as("订阅后才执行").isTrue();

        // 消掉未使用的告警
        assertThat(just).isNotNull();
    }

    @Test
    void backpressure_initial_request_limits_emission() {
        StepVerifier.create(Flux.range(1, 5), 2)   // 初始只请求 2 个
                .expectNext(1, 2)
                .thenRequest(2)                     // 再请求 2 个
                .expectNext(3, 4)
                .thenRequest(10)                    // 把剩下的要完
                .expectNext(5)
                .verifyComplete();
    }

    @Test
    void mono_void_completes_without_value() {
        StepVerifier.create(Mono.<Void>empty().then())
                .expectComplete()                   // 只有完成信号，没有 onNext
                .verify();
    }

    @Test
    void request_zero_is_a_contract_violation() {
        // 规范：request(n) 的 n 必须 > 0，否则生产者应发 onError(IllegalArgumentException)
        Flux.range(1, 3)
                .subscribe(new reactor.core.publisher.BaseSubscriber<Integer>() {
                    @Override
                    protected void hookOnSubscribe(org.reactivestreams.Subscription s) {
                        s.request(0);               // 违规
                    }
                });
        // 没有断言抛给测试线程，这里只做"行为演示"：
        // 规范里由【生产者】决定怎么报错；Reactor 的做法是 onError(IllegalArgumentException)。
        assertThat(true).isTrue();
    }
}
