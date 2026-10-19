package com.myxhs.dubbo.lab.provider;

import com.myxhs.dubbo.lab.api.DemoService;
import com.myxhs.dubbo.lab.api.User;
import org.apache.dubbo.rpc.RpcContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DemoService 实现：所有实验的 provider 侧行为都从这里开始演化。
 */
public class DemoServiceImpl implements DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoServiceImpl.class);

    /** 多实例实验用：-Dlab.provider.tag=A/B 区分是哪个 provider 处理的 */
    private static final String TAG = System.getProperty("lab.provider.tag", "");

    /** 最少活跃实验用：-Dlab.slow.extra=800 让该实例的 slow 额外多睡一会（制造负载差异） */
    private static final int SLOW_EXTRA = Integer.getInteger("lab.slow.extra", 0);

    /** 最少活跃实验用：-Dlab.slow.echo.tag=true 让 slow 返回值带上实例标识 */
    private static final boolean SLOW_ECHO_TAG = Boolean.getBoolean("lab.slow.echo.tag");

    private static String tag() {
        return TAG.isEmpty() ? "" : "-" + TAG;
    }

    @Override
    public String sayHello(String name) {
        log.info("[provider{}] sayHello 收到调用, name={}, 线程={}", tag(), name, Thread.currentThread().getName());
        Object traceId = RpcContext.getServerAttachment().getObjectAttachment("lab-trace-id");
        if (traceId != null) {
            log.info("[provider] sayHello 收到隐式参数 lab-trace-id={}", traceId);
            // 响应附件：随本次响应带回消费方（消费方从 getClientResponseContext 读取）
            RpcContext.getServerContext().setAttachment("lab-provider-ack", "ack-" + traceId);
        }
        if (name.startsWith("biz-error")) {
            throw new IllegalStateException("biz error: " + name);
        }
        return "hello " + name + " from lab-provider" + tag();
    }

    @Override
    public String slow(int millis) {
        log.info("[provider] slow 开始, millis={}, 线程={}", millis, Thread.currentThread().getName());
        try {
            Thread.sleep(millis + SLOW_EXTRA);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("[provider] slow 结束, millis={}", millis);
        return "slept " + millis + "ms" + (SLOW_ECHO_TAG ? " from lab-provider" + tag() : "");
    }

    @Override
    public User getUser(long id) {
        log.info("[provider] getUser, id={}", id);
        return new User(id, "user-" + id);
    }
}
