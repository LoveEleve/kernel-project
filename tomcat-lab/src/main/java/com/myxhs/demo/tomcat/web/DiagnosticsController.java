package com.myxhs.demo.tomcat.web;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 12 篇线上排查实验接口——三个"可复现的坏请求"。
 *
 * <ul>
 *   <li>{@code /spin?ms=N}：CPU 型（worker 忙循环，用 jstack/栈采样能定位到本方法）</li>
 *   <li>{@code /leak?mb=N} + {@code /leak/status} + {@code /leak/clear}：内存型
 *       （静态 List 持有 byte[]，对应 MAT 里的"静态集合"常客）</li>
 *   <li>{@code /deadlock/a} + {@code /deadlock/b}：hang 型（两个端点反序抢锁 → 真死锁，
 *       ThreadMXBean.findDeadlockedThreads / jstack 可直接检测）</li>
 * </ul>
 */
@RestController
public class DiagnosticsController {

    private static final Object LOCK_A = new Object();

    private static final Object LOCK_B = new Object();

    private static final CountDownLatch A_ENTERED = new CountDownLatch(1);

    private static final CountDownLatch B_ENTERED = new CountDownLatch(1);

    private static final List<byte[]> LEAKED = new CopyOnWriteArrayList<>();

    private static volatile long spinSink;

    @GetMapping("/spin")
    public String spin(@RequestParam(defaultValue = "3000") long ms) {
        long start = System.nanoTime();
        long acc = 0;
        while ((System.nanoTime() - start) < ms * 1_000_000L) {
            acc += 1;
        }
        spinSink = acc; // 防止 JIT 把循环优化掉
        return "spun " + ms + "ms acc=" + acc;
    }

    @GetMapping("/leak")
    public String leak(@RequestParam(defaultValue = "1") int mb) {
        LEAKED.add(new byte[mb * 1024 * 1024]);
        return leakStatus();
    }

    @GetMapping("/leak/status")
    public String leakStatus() {
        return "leakedMb=" + LEAKED.size() + " (held by static List<byte[]>)";
    }

    @GetMapping("/leak/clear")
    public String leakClear() {
        LEAKED.clear();
        return leakStatus();
    }

    /** 死锁 A 半场：先拿 LOCK_A，等 B 进场后再抢 LOCK_B。 */
    @GetMapping("/deadlock/a")
    public String deadlockA() throws InterruptedException {
        synchronized (LOCK_A) {
            A_ENTERED.countDown();
            B_ENTERED.await(5, TimeUnit.SECONDS);
            synchronized (LOCK_B) {
                return "A done";
            }
        }
    }

    /** 死锁 B 半场：先拿 LOCK_B，等 A 进场后再抢 LOCK_A（与 A 相反顺序）。 */
    @GetMapping("/deadlock/b")
    public String deadlockB() throws InterruptedException {
        synchronized (LOCK_B) {
            B_ENTERED.countDown();
            A_ENTERED.await(5, TimeUnit.SECONDS);
            synchronized (LOCK_A) {
                return "B done";
            }
        }
    }
}
