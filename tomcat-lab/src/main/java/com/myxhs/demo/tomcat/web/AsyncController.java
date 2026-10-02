package com.myxhs.demo.tomcat.web;

import java.io.IOException;
import java.util.concurrent.Callable;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * 08 篇异步 Servlet 实验接口。
 *
 * <ul>
 *   <li>{@code /deferred-timeout}：DeferredResult + timeoutResult——超时后走兜底结果（200）</li>
 *   <li>{@code /deferred-ok}：DeferredResult 正常完成（对照）</li>
 *   <li>{@code /raw-async?mode=ok|timeout|timeout-handled}：原生 Servlet 3.0 AsyncContext，
 *       演示"超时未处理（容器兜底）"vs"超时被 listener 处理"</li>
 * </ul>
 *
 * <p>配套测试：{@code AsyncServletTest}（结合 {@code spring.mvc.async.request-timeout} 观察超时行为）。
 */
@RestController
public class AsyncController {

    /** DeferredResult 超时兜底：超时后返回 200 + fallback 文本（timeoutResult 生效）。 */
    @GetMapping("/deferred-timeout")
    public DeferredResult<String> deferredTimeout(@RequestParam(defaultValue = "1000") long timeoutMs) {
        DeferredResult<String> result = new DeferredResult<>(timeoutMs, "deferred timeout fallback");
        // 故意不 setResult：等它超时
        return result;
    }

    /** DeferredResult 正常完成（对照）。 */
    @GetMapping("/deferred-ok")
    public DeferredResult<String> deferredOk(@RequestParam(defaultValue = "300") long delayMs) {
        DeferredResult<String> result = new DeferredResult<>(5_000L);
        Thread worker = new Thread(() -> result.setResult("deferred ok after " + delayMs + "ms"), "deferred-worker");
        worker.setDaemon(true);
        worker.start();
        return result;
    }

    /**
     * 原生 AsyncContext：容器线程立刻返回，worker 线程里完成响应。
     *
     * <pre>
     * mode=ok              worker 睡 workMs(默认 500) 后 complete → 200 "raw done"（timeout 默认 5000 不会先到）
     * mode=timeout         setTimeout(timeoutMs)，无人处理超时 → 观察容器兜底（错误页）
     *                      建议：?mode=timeout&timeoutMs=500&workMs=5000
     * mode=timeout-handled setTimeout(timeoutMs) + onTimeout 里 complete → 200 "timeout handled"
     *                      建议：?mode=timeout-handled&timeoutMs=500&workMs=5000
     * </pre>
     */
    @GetMapping("/raw-async")
    public void rawAsync(@RequestParam(defaultValue = "ok") String mode,
                         @RequestParam(defaultValue = "5000") long timeoutMs,
                         @RequestParam(defaultValue = "500") long workMs,
                         HttpServletRequest request) throws IOException {
        AsyncContext async = request.startAsync();
        async.setTimeout(timeoutMs);

        if ("timeout-handled".equals(mode)) {
            async.addListener(new AsyncListener() {
                @Override
                public void onTimeout(AsyncEvent event) throws IOException {
                    event.getAsyncContext().getResponse().getWriter().write("timeout handled by listener");
                    event.getAsyncContext().complete();
                }

                @Override
                public void onStartAsync(AsyncEvent event) {
                }

                @Override
                public void onError(AsyncEvent event) {
                }

                @Override
                public void onComplete(AsyncEvent event) {
                }
            });
        }

        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(workMs);
                async.getResponse().getWriter().write("raw done after " + workMs + "ms");
                async.complete();
            }
            catch (Exception ex) {
                // 超时未处理时容器已接管，这里写入会失败——记录即可
                System.out.println("[lab] raw-async(" + mode + ") worker 收尾失败: "
                        + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }, "raw-async-worker");
        worker.setDaemon(true);
        worker.start();
    }
}
