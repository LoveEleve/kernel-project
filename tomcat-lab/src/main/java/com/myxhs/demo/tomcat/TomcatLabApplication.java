package com.myxhs.demo.tomcat;

import org.apache.catalina.startup.Tomcat;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 内嵌 Tomcat 实验室入口。
 *
 * <p>用途：给 Tomcat 面试篇（01~10）提供"能跑、能改、能观察"的复现代码。
 * 当前包含：
 * <ul>
 *   <li>07 优雅停机：{@code /slow}（同步慢请求）、{@code /async}（异步 Servlet）、{@code /sse}、{@code /inflight}（在途计数）</li>
 *   <li>08 异步 Servlet：{@code /deferred-timeout}、{@code /deferred-ok}、{@code /raw-async}（超时接管 vs 容器兜底）</li>
 *   <li>09 调参排障：actuator 指标（{@code tomcat.threads.busy} 等，mbeanregistry 已打开）+ TuningTest + CLOSE_WAIT 脚本</li>
 * </ul>
 */
@SpringBootApplication
public class TomcatLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(TomcatLabApplication.class, args);
    }
}
