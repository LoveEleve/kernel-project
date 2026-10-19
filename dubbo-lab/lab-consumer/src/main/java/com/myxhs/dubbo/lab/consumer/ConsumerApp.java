package com.myxhs.dubbo.lab.consumer;

import com.myxhs.dubbo.lab.api.DemoService;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.MethodConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.service.GenericService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Consumer 启动入口（API 风格）。
 * <p>
 * 直连模式：registry=N/A（默认直连 dubbo://127.0.0.1:20880）。
 * 注册中心模式：-Dlab.registry=nacos://127.0.0.1:8848
 * 超时实验参数：
 *   -Dlab.timeout.ref=3000       消费方接口级超时（默认 3000；0=不设置）
 *   -Dlab.timeout.method=1000    消费方 slow 方法级超时（0=不设置）
 *   -Dlab.slow.ms=2000           本次 slow 调用传入的毫秒数
 *   -Dlab.direct.url=...         直连 URL（可带参数，如 ?timeout=5000）
 *   -Dlab.retries=2              Failover 重试次数（默认 0=不重试；重试实验用）
 *   -Dlab.name=biz-error         sayHello 入参（默认 timeout-lab；biz-error 触发提供方业务异常）
 *   -Dlab.tag=A                  标签路由：客户端附件 dubbo.tag（默认空=不设标签）
 *   -Dlab.trace.id=tr-123        隐式传参：客户端附件 lab-trace-id（默认空；响应附件见 provider ack）
 *   -Dlab.cache=lru              消费方缓存：reference 参数 cache（默认空=不开缓存）
 *   -Dlab.fixed.name=true        loop 模式固定入参为 name（缓存实验用；默认 loop-i 各不相同）
 *   -Dlab.concurrent=5           并发压测：N 个 slow(slow.ms) 异步并发（默认 0=关）
 *   -Dlab.generic=true           泛化调用：GenericService.$invoke（不需要接口类）
 *   -Dlab.debug=true             打印调用前后客户端附件（观察附件生命周期）
 * </p>
 */
public class ConsumerApp {

    public static void main(String[] args) throws Exception {
        String registry = System.getProperty("lab.registry", RegistryConfig.NO_AVAILABLE);
        boolean direct = RegistryConfig.NO_AVAILABLE.equals(registry);
        String directUrl = System.getProperty("lab.direct.url", "dubbo://127.0.0.1:20880");
        int refTimeout = Integer.getInteger("lab.timeout.ref", 3000);
        int methodTimeout = Integer.getInteger("lab.timeout.method", 0);
        int slowMs = Integer.getInteger("lab.slow.ms", 2000);
        int loop = Integer.getInteger("lab.loop", 1);
        long intervalMs = Long.getLong("lab.interval.ms", 0L);
        String lb = System.getProperty("lab.lb", "");
        boolean async = Boolean.getBoolean("lab.async");
        int retries = Integer.getInteger("lab.retries", 0);
        String name = System.getProperty("lab.name", "timeout-lab");
        String tag = System.getProperty("lab.tag", "");
        String traceId = System.getProperty("lab.trace.id", "");
        String cache = System.getProperty("lab.cache", "");
        boolean fixedName = Boolean.getBoolean("lab.fixed.name");
        int concurrent = Integer.getInteger("lab.concurrent", 0);
        boolean generic = Boolean.getBoolean("lab.generic");
        String routerRule = System.getProperty("lab.router.rule", "");
        String group = System.getProperty("lab.group", "");
        int actives = Integer.getInteger("lab.actives", 0);

        ReferenceConfig<DemoService> reference = new ReferenceConfig<>();
        reference.setInterface(DemoService.class);
        if (direct) {
            reference.setUrl(directUrl);
        }
        if (refTimeout > 0) {
            reference.setTimeout(refTimeout);
        }
        if (methodTimeout > 0) {
            MethodConfig method = new MethodConfig();
            method.setName("slow");
            method.setTimeout(methodTimeout);
            reference.setMethods(List.of(method));
        }
        // lab 约定：默认关闭重试隔离超时行为；重试放大实验通过 -Dlab.retries=2 打开
        reference.setRetries(retries);
        if (!lb.isEmpty()) {
            reference.setLoadbalance(lb);
        }
        java.util.Map<String, String> refParams = new java.util.HashMap<>();
        if (!cache.isEmpty()) {
            refParams.put("cache", cache);
        }
        if (!routerRule.isEmpty()) {
            // 条件路由：router=condition 激活 ConditionStateRouterFactory（按名激活），rule 从 URL 读取
            refParams.put("router", "condition");
            refParams.put("rule", routerRule);
        }
        if (actives > 0) {
            // actives：激活 ActiveLimitFilter（消费侧并发上限），同时维护消费侧 RpcStatus 活跃数（leastactive 依赖它）
            refParams.put("actives", String.valueOf(actives));
        }
        if (!refParams.isEmpty()) {
            reference.setParameters(refParams);
        }
        if (!group.isEmpty()) {
            // 服务分组：group 是 service key 的一部分（接口:group:version），按订阅隔离
            reference.setGroup(group);
        }
        if (!Boolean.parseBoolean(System.getProperty("lab.check", "true"))) {
            // 启动不检查 provider 可用性（QoS offline 摘流量实验用；默认 true 保持旧行为）
            reference.setCheck(false);
        }

        ApplicationConfig application = new ApplicationConfig("lab-consumer");
        // lab 约定：默认关闭 QoS（22222 端口）；-Dlab.qos=true 打开（路由快照/诊断实验用）
        application.setQosEnable(Boolean.getBoolean("lab.qos"));

        if (generic) {
            // 泛化调用：不依赖 lab-api 接口类，按"接口名+方法+参数类型"调用
            ReferenceConfig<GenericService> genericReference = new ReferenceConfig<>();
            genericReference.setInterface("com.myxhs.dubbo.lab.api.DemoService");
            genericReference.setGeneric("true");
            if (direct) {
                genericReference.setUrl(directUrl);
            }
            if (refTimeout > 0) {
                genericReference.setTimeout(refTimeout);
            }
            genericReference.setRetries(retries);
            DubboBootstrap.getInstance()
                    .application(application)
                    .registry(new RegistryConfig(registry))
                    .reference(genericReference)
                    .start();
            GenericService genericService = genericReference.get();
            System.out.println("[lab-consumer] generic $invoke = " + genericService.$invoke(
                    "sayHello", new String[]{"java.lang.String"}, new Object[]{"generic-lab"}));
            DubboBootstrap.getInstance().stop();
            System.out.println("[lab-consumer] done");
            System.exit(0);
        }

        DubboBootstrap.getInstance()
                .application(application)
                .registry(new RegistryConfig(registry))
                .reference(reference)
                .start();

        DemoService demo = reference.get();
        System.out.println("[lab-consumer] 配置: ref.timeout=" + (refTimeout > 0 ? refTimeout + "ms" : "未设置")
                + ", method(slow).timeout=" + (methodTimeout > 0 ? methodTimeout + "ms" : "未设置")
                + ", retries=" + retries + ", lb=" + (lb.isEmpty() ? "random(默认)" : lb)
                + (tag.isEmpty() ? "" : ", tag=" + tag)
                + ", directUrl=" + (direct ? directUrl : "(registry)"));
        if (async) {
            // 异步实验：两次 slow(1000) 并发发出，对比同步串行
            long t0 = System.currentTimeMillis();
            CompletableFuture<String> f1 = RpcContext.getServiceContext().asyncCall(() -> demo.slow(1000));
            CompletableFuture<String> f2 = RpcContext.getServiceContext().asyncCall(() -> demo.slow(1000));
            System.out.println("[lab-consumer] async: " + f1.get(10, TimeUnit.SECONDS)
                    + " / " + f2.get(10, TimeUnit.SECONDS));
            long asyncMs = System.currentTimeMillis() - t0;
            long t1 = System.currentTimeMillis();
            demo.slow(1000);
            demo.slow(1000);
            long syncMs = System.currentTimeMillis() - t1;
            System.out.println("[lab-consumer] 两次 slow(1000): async=" + asyncMs + "ms, sync=" + syncMs + "ms");
        } else if (concurrent > 1) {
            // 并发压测：N 个 slow 异步并发（线程池耗尽 / 最少活跃实验用）
            long submitIntervalMs = Long.getLong("lab.submit.interval.ms", 0L);
            List<CompletableFuture<String>> futures = new ArrayList<>();
            long t0 = System.currentTimeMillis();
            for (int i = 1; i <= concurrent; i++) {
                futures.add(RpcContext.getServiceContext().asyncCall(() -> demo.slow(slowMs)));
                if (i < concurrent && submitIntervalMs > 0) {
                    try {
                        Thread.sleep(submitIntervalMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    System.out.println("[lab-consumer] concurrent#" + (i + 1) + " = "
                            + futures.get(i).get(20, TimeUnit.SECONDS));
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    String msg = String.valueOf(cause.getMessage()).replace('\n', ' ');
                    if (msg.length() > 120) {
                        msg = msg.substring(0, 120);
                    }
                    System.out.println("[lab-consumer] concurrent#" + (i + 1) + " -> FAIL: "
                            + cause.getClass().getSimpleName() + " | " + msg);
                }
            }
            System.out.println("[lab-consumer] " + concurrent + " 个并发 slow(" + slowMs + ") 总耗时 = "
                    + (System.currentTimeMillis() - t0) + "ms");
        } else if (loop > 1) {
            // 注册中心实验：循环调用，观察 provider 上下线时的目录通知
            for (int i = 1; i <= loop; i++) {
                boolean debug = Boolean.getBoolean("lab.debug");
                try {
                    if (debug) {
                        System.out.println("[lab-consumer] call#" + i + " 设置前 attachments="
                                + RpcContext.getClientAttachment().getObjectAttachments());
                    }
                    if (!tag.isEmpty()) {
                        // 标签路由：客户端附件随本次调用带到 provider 侧（每次调用都要设置）
                        RpcContext.getClientAttachment().setAttachment("dubbo.tag", tag);
                    }
                    String r = demo.sayHello(fixedName ? name : "loop-" + i);
                    if (debug) {
                        System.out.println("[lab-consumer] call#" + i + " 结束后 attachments="
                                + RpcContext.getClientAttachment().getObjectAttachments());
                    }
                    System.out.println("[lab-consumer] call#" + i + " -> OK: " + r);
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? "" : e.getMessage().replace('\n', ' ');
                    if (msg.length() > 140) {
                        msg = msg.substring(0, 140);
                    }
                    String cause = e.getCause() == null ? ""
                            : " | cause=" + e.getCause().getClass().getSimpleName() + ": "
                                    + String.valueOf(e.getCause().getMessage()).replace('\n', ' ');
                    if (cause.length() > 120) {
                        cause = cause.substring(0, 120);
                    }
                    System.out.println("[lab-consumer] call#" + i + " -> FAIL: "
                            + e.getClass().getSimpleName() + " | " + msg + cause);
                }
                if (i < loop && intervalMs > 0) {
                    try {
                        Thread.sleep(intervalMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } else {
            try {
                if (!tag.isEmpty()) {
                    RpcContext.getClientAttachment().setAttachment("dubbo.tag", tag);
                }
                if (!traceId.isEmpty()) {
                    // 隐式传参：消费方附件随请求带到 provider 侧
                    RpcContext.getClientAttachment().setAttachment("lab-trace-id", traceId);
                }
                System.out.println("[lab-consumer] sayHello = " + demo.sayHello(name));
            } catch (Exception e) {
                System.out.println("[lab-consumer] sayHello(" + name + ") -> FAIL: " + e.getClass().getName()
                        + " | " + String.valueOf(e.getMessage()).replace('\n', ' '));
            }
            if (!traceId.isEmpty()) {
                // 响应附件：provider 在 RpcContext.getServerContext() 设置的附件随响应带回
                System.out.println("[lab-consumer] 响应附件 = "
                        + RpcContext.getClientResponseContext().getObjectAttachments());
            }
            if (Boolean.getBoolean("lab.debug")) {
                System.out.println("[lab-consumer] sayHello 后 client attachments = "
                        + RpcContext.getClientAttachment().getObjectAttachments());
            }

            long start = System.currentTimeMillis();
            try {
                String result = demo.slow(slowMs);
                System.out.println("[lab-consumer] slow(" + slowMs + ") -> OK in "
                        + (System.currentTimeMillis() - start) + "ms: " + result);
            } catch (Exception e) {
                System.out.println("[lab-consumer] slow(" + slowMs + ") -> FAIL in "
                        + (System.currentTimeMillis() - start) + "ms: " + e.getClass().getSimpleName());
            }
        }

        DubboBootstrap.getInstance().stop();
        System.out.println("[lab-consumer] done");
        System.exit(0);
    }
}
