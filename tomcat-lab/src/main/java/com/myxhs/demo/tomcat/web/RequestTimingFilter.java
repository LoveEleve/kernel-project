package com.myxhs.demo.tomcat.web;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 示例：自定义 Filter（记录请求耗时）。
 *
 * <p>要点：
 * <ul>
 *   <li>继承 {@link OncePerRequestFilter}：保证一次请求只执行一次
 *       （forward/include/async dispatch 不重复；内部用 request attribute 标记）</li>
 *   <li>请求阶段代码在 {@code chain.doFilter()} 之前执行；响应阶段代码在其后（finally）</li>
 *   <li>不调用 {@code chain.doFilter()} 即为短路，目标 Servlet 不会执行</li>
 * </ul>
 *
 * <p><b>响应头的提交时机（本 demo 演示的坑）</b>：
 * 代码在 {@code chain.doFilter()} 之后运行时，Servlet 往往已经把响应写出并提交；
 * 此时 {@code setHeader(...)} 会被静默忽略。因此：
 * 需要一定出现在响应头里的标记（如 {@code X-Lab-Timing}）必须在调用链之前设置；
 * 耗时这类"事后才知道"的值，可靠做法是日志/指标上报（本 demo 的 LAST_COST_MS + /lab/filter-last-cost），
 * 或包装 Response 缓冲响应体（如 {@code ContentCachingResponseWrapper}）后再写头。
 */
public class RequestTimingFilter extends OncePerRequestFilter {

    private static final AtomicLong LAST_COST_MS = new AtomicLong(-1);

    /** 最近一次请求耗时（毫秒），供 /lab/filter-last-cost 查询。 */
    public static long getLastCostMs() {
        return LAST_COST_MS.get();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        response.setHeader("X-Lab-Timing", "on");   // 请求阶段：调用链之前设置，保证生效
        try {
            chain.doFilter(request, response);       // 放行：下一个 Filter 或 Servlet
        }
        finally {
            long costMs = (System.nanoTime() - start) / 1_000_000;
            LAST_COST_MS.set(costMs);
            System.out.println("[lab] RequestTimingFilter " + request.getMethod() + " "
                    + request.getRequestURI() + " cost=" + costMs + "ms"
                    + "（响应已提交=" + response.isCommitted() + "，耗时通过日志/端点上报）");
        }
    }
}
