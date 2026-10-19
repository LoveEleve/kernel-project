package com.scalab.sentinel.core;

import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * B-01: 自定义 ProcessorSlot——记录每个请求的耗时。
 *
 * <p>实现 {@link AbstractLinkedProcessorSlot}，覆盖 entry/exit 方法，
 * 在 entry 中记录开始时间，在 exit 中计算耗时并打印日志。
 * 不改变流量行为——纯粹是观测性的扩展点。
 *
 * <p>接入方式：
 * <ol>
 *   <li>在 {@code META-INF/services/com.alibaba.csp.sentinel.slotchain.ProcessorSlot}
 *       文件中加一行 {@code com.scalab.sentinel.core.TimingSlot}</li>
 *   <li>重启 sentinel-lab → SPI 自动加载 → 插入链中</li>
 * </ol>
 */
public class TimingSlot extends AbstractLinkedProcessorSlot<Object> {

    private static final Logger log = LoggerFactory.getLogger(TimingSlot.class);

    /** 记录开始时间的 ThreadLocal——每个请求独立 */
    private final ThreadLocal<Long> startTimeHolder = new ThreadLocal<>();

    @Override
    public void entry(Context context, ResourceWrapper resourceWrapper, Object param,
                      int count, boolean prioritized, Object... args) throws Throwable {
        // 记录开始时间（纳秒精度）
        startTimeHolder.set(System.nanoTime());
        // 传递给下一个 Slot（关键——不调 fireEntry 链条就断了）
        fireEntry(context, resourceWrapper, param, count, prioritized, args);
    }

    @Override
    public void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args) {
        try {
            Long startNanos = startTimeHolder.get();
            if (startNanos != null) {
                long costMs = (System.nanoTime() - startNanos) / 1_000_000;
                log.info("[TimingSlot] resource={} costMs={}", resourceWrapper.getName(), costMs);
            }
        } finally {
            // 清理 ThreadLocal（防止泄漏——Tomcat 线程池复用）
            startTimeHolder.remove();
            // 传递给前一个 Slot 的 exit（关键——不调 fireExit 链条就断了）
            fireExit(context, resourceWrapper, count, args);
        }
    }
}
