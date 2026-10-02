package com.myxhs.demo.tomcat.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 13 篇云原生实验接口：暴露进程级 JVM 信息。
 *
 * <p>用 JVM 参数模拟 K8s 限制后对比输出：
 * <ul>
 *   <li>{@code -XX:MaxRAMPercentage=10} → {@code maxMemoryMb} 按物理内存 10% 计算</li>
 *   <li>{@code -XX:ActiveProcessorCount=2} → {@code processors=2}（对齐 CPU limit）</li>
 *   <li>{@code --server.tomcat.basedir=...} → 影响 Boot 的 baseDir（docBase 默认在 {@code java.io.tmpdir}）</li>
 * </ul>
 */
@RestController
public class JvmInfoController {

    @GetMapping("/jvm-info")
    public String jvmInfo() {
        Runtime runtime = Runtime.getRuntime();
        return "processors=" + runtime.availableProcessors()
                + " maxMemoryMb=" + (runtime.maxMemory() >> 20)
                + " totalMemoryMb=" + (runtime.totalMemory() >> 20)
                + " javaVersion=" + System.getProperty("java.version")
                + " tmpdir=" + System.getProperty("java.io.tmpdir");
    }
}
