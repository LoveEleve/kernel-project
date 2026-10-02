package com.myxhs.demo.tomcat.web;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 优雅停机实验接口。
 *
 * <p>四个端点各演示一件事：
 * <ul>
 *   <li>{@code /slow}：同步慢请求——占住 worker 线程，对应 Tomcat 的
 *       {@code StandardWrapper.getCountAllocated()}</li>
 *   <li>{@code /async}：异步 Servlet（Callable）——容器线程先释放，对应
 *       {@code StandardContext.getInProgressAsyncCount()}</li>
 *   <li>{@code /sse}：SSE 长连接——典型的"停机必须等/必须超时"场景</li>
 *   <li>{@code /inflight}：返回当前在处理的同步/异步请求数——测试用它确认
 *       "请求已进入服务端"再触发停机（确定性复现）</li>
 * </ul>
 */
@RestController
public class SlowController {

    private final AtomicInteger syncInFlight = new AtomicInteger();

    private final AtomicInteger asyncInFlight = new AtomicInteger();

    @GetMapping("/slow")
    public String slow(@RequestParam(defaultValue = "3") int seconds,
                       @RequestParam(defaultValue = "sync") String tag) throws InterruptedException {
        int current = syncInFlight.incrementAndGet();
        long start = System.currentTimeMillis();
        try {
            Thread.sleep(seconds * 1000L);
            return "done tag=" + tag
                    + " syncInFlight=" + current
                    + " elapsedMs=" + (System.currentTimeMillis() - start)
                    + " thread=" + Thread.currentThread().getName();
        }
        finally {
            syncInFlight.decrementAndGet();
        }
    }

    @GetMapping("/async")
    public Callable<String> async(@RequestParam(defaultValue = "3") int seconds) {
        asyncInFlight.incrementAndGet();
        long start = System.currentTimeMillis();
        return () -> {
            try {
                Thread.sleep(seconds * 1000L);
                return "async done elapsedMs=" + (System.currentTimeMillis() - start)
                        + " thread=" + Thread.currentThread().getName();
            }
            finally {
                asyncInFlight.decrementAndGet();
            }
        };
    }

    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sse(@RequestParam(defaultValue = "10") int seconds) {
        SseEmitter emitter = new SseEmitter((seconds + 5) * 1000L);
        Thread sender = new Thread(() -> {
            try {
                for (int i = 1; i <= seconds; i++) {
                    emitter.send("tick " + i);
                    Thread.sleep(1000L);
                }
                emitter.complete();
            }
            catch (Exception ex) {
                emitter.completeWithError(ex);
            }
        }, "sse-sender");
        sender.setDaemon(true);
        sender.start();
        return emitter;
    }

    @GetMapping("/inflight")
    public String inFlight() {
        return "inFlight=" + syncInFlight.get() + " asyncInFlight=" + asyncInFlight.get();
    }

    /** 10 篇：RemoteIpValve 实验——返回 Tomcat 看到的 remoteAddr（配 forward-headers-strategy=native 后可被 XFF 还原）。 */
    @GetMapping("/remote-addr")
    public String remoteAddr(HttpServletRequest request) {
        return "remoteAddr=" + request.getRemoteAddr() + " xff=" + request.getHeader("X-Forwarded-For");
    }

    /** Filter 实验：返回上一次请求的耗时（由 RequestTimingFilter 记录）。 */
    @GetMapping("/lab/filter-last-cost")
    public String lastFilterCost() {
        return "lastCostMs=" + RequestTimingFilter.getLastCostMs();
    }
}
