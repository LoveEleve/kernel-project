package com.myxhs.demo.reactor.l11;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把 L11 的调度语义变成断言（只断言确定性行为；多线程并发数靠 Ex04 演示）。
 *
 * <p>跑：{@code mvn -pl reactor-lab test}
 */
class L11_SchedulingTest {

    @Test
    void subscribeOn_moves_the_whole_chain_to_the_scheduler() {
        String threadName = Mono.fromCallable(() -> Thread.currentThread().getName())
                .subscribeOn(Schedulers.parallel())
                .block();
        assertThat(threadName).as("subscribeOn 后源跑在 parallel 线程上").startsWith("parallel");
    }

    @Test
    void publishOn_moves_only_the_downstream() {
        AtomicReference<String> upstreamThread = new AtomicReference<>();
        AtomicReference<String> downstreamThread = new AtomicReference<>();

        Mono.just("x")
                .doOnNext(v -> upstreamThread.set(Thread.currentThread().getName()))
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(v -> downstreamThread.set(Thread.currentThread().getName()))
                .block();

        assertThat(upstreamThread.get()).as("上游仍在调用线程").doesNotStartWith("boundedElastic");
        assertThat(downstreamThread.get()).as("下游切换到 boundedElastic").startsWith("boundedElastic");
    }

    @Test
    void parallel_without_runOn_stays_on_one_thread() {
        Set<String> threads = ConcurrentHashMap.newKeySet();
        Flux.range(1, 8)
                .parallel(4)                                  // 只分轨道，不指定调度器
                .map(i -> {
                    threads.add(Thread.currentThread().getName());
                    return i;
                })
                .sequential()
                .blockLast();

        assertThat(threads).as("parallel 不配 runOn → 仍在同一条线程上串行").hasSize(1);
    }

    @Test
    void parallel_with_runOn_produces_correct_results() {
        List<Integer> values = Flux.range(1, 200)
                .parallel(4)
                .runOn(Schedulers.parallel())
                .map(i -> i * 2)
                .sequential()
                .collectList()
                .block();

        assertThat(values).as("分片并行不丢不重").hasSize(200).contains(2, 400);
    }
}
