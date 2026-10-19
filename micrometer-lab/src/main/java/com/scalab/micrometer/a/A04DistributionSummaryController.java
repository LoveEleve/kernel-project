package com.scalab.micrometer.a;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ThreadLocalRandom;

/**
 * A-04 DistributionSummary 验证——对照 D1 §二 newDistributionSummary。
 *
 * DistributionSummary 与 Timer 的区别：
 * - Timer：记录时间维度（durations），自动转换为秒
 * - DistributionSummary：记录任意值维度（如 payload size），需要指定 baseUnit（如 "bytes"）
 *
 * 验证点：
 * 1. {@code DistributionSummary.builder().register()} 创建
 * 2. {@code summary.record(value)} 记录单个值
 * 3. Prometheus 输出：{@code _bytes_count / _bytes_sum / _bytes_max} + histogram
 * 4. count/totalAmount/mean/max 四维指标
 */
@RestController
@RequestMapping("/a04")
public class A04DistributionSummaryController {

    private final DistributionSummary responseSize;

    public A04DistributionSummaryController(MeterRegistry registry) {
        this.responseSize = DistributionSummary.builder("a04.response.size")
                .description("A04 响应体大小分布")
                .baseUnit("bytes")                                   // ← 指定单位为 bytes
                .tag("endpoint", "/a04/demo")
                .publishPercentileHistogram(true)                    // ← 预分桶直方图
                .minimumExpectedValue(100.0)                         // ← 最小期望 100 bytes
                .maximumExpectedValue(10000.0)                       // ← 最大期望 10KB
                .publishPercentiles(0.5, 0.95)                      // ← P50/P95
                .register(registry);
    }

    /** 模拟不同大小的响应——每次随机 100~10000 bytes */
    @GetMapping("/demo")
    public String demo() {
        int size = ThreadLocalRandom.current().nextInt(100, 10000);
        responseSize.record(size);
        return "Response size: " + size + " bytes"
                + " | count=" + responseSize.count()
                + " mean=" + String.format("%.0f", responseSize.mean())
                + " max=" + responseSize.max()
                + " total=" + String.format("%.0f", responseSize.totalAmount());
    }

    /** 查看当前统计 */
    @GetMapping("/stats")
    public String stats() {
        return "count=" + responseSize.count()
                + " mean=" + String.format("%.0fb", responseSize.mean())
                + " max=" + responseSize.max() + "b"
                + " total=" + String.format("%.0fb", responseSize.totalAmount());
    }
}
