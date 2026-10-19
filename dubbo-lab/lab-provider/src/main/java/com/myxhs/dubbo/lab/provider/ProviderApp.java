package com.myxhs.dubbo.lab.provider;

import com.myxhs.dubbo.lab.api.DemoService;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider 启动入口（API 风格，刻意不走 Spring 自动配置——先看清核心链路）。
 * <p>
 * 直连模式：registry=N/A，consumer 用 dubbo:// 直连本机 20880（零外部依赖）。
 * 注册中心模式：-Dlab.registry=nacos://127.0.0.1:8848
 * 接口级超时（提供方）：-Dlab.timeout.provider=5000（0=不设置）
 * </p>
 */
public class ProviderApp {

    public static void main(String[] args) {
        if (Boolean.getBoolean("lab.spi.inspect")) {
            ExtensionLoader<Protocol> protocolLoader = ExtensionLoader.getExtensionLoader(Protocol.class);
            System.out.println("[spi] Protocol adaptive class = "
                    + protocolLoader.getAdaptiveExtension().getClass().getName());
            System.out.println("[spi] Protocol 'dubbo' instance class = "
                    + protocolLoader.getExtension("dubbo").getClass().getName());
            ExtensionLoader<Filter> filterLoader = ExtensionLoader.getExtensionLoader(Filter.class);
            System.out.println("[spi] Filter supported names = " + filterLoader.getSupportedExtensions());
        }
        String registry = System.getProperty("lab.registry", RegistryConfig.NO_AVAILABLE);
        int providerTimeout = Integer.getInteger("lab.timeout.provider", 0);
        int port = Integer.getInteger("lab.port", 20880);
        String urlTag = System.getProperty("lab.provider.url.tag", "");
        String group = System.getProperty("lab.group", "");
        boolean qos = Boolean.getBoolean("lab.qos");
        int threads = Integer.getInteger("lab.threads", 0);
        int queues = Integer.getInteger("lab.queues", -1);
        int executes = Integer.getInteger("lab.executes", 0);
        String protocols = System.getProperty("lab.protocols", "dubbo");
        int triPort = Integer.getInteger("lab.tri.port", 50051);

        ServiceConfig<DemoService> service = new ServiceConfig<>();
        service.setInterface(DemoService.class);
        service.setRef(new DemoServiceImpl());
        if (!group.isEmpty()) {
            // 服务分组实验：group 是 service key 的一部分（接口:group:version）
            service.setGroup(group);
        }
        // lab 约定：禁用权重 warmup，LB 实验按等权观察；url.tag 非空时注册 dubbo.tag 参数（标签路由实验）
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("warmup", "0");
        if (!urlTag.isEmpty()) {
            params.put("dubbo.tag", urlTag);
        }
        if (executes > 0) {
            // 启用 ExecuteLimitFilter：既限并发，也让 RpcStatus 计数（QoS count 命令用）
            params.put("executes", String.valueOf(executes));
        }
        service.setParameters(params);
        if (providerTimeout > 0) {
            service.setTimeout(providerTimeout);
            System.out.println("[lab-provider] 提供方接口级超时 = " + providerTimeout + "ms");
        }

        ApplicationConfig application = new ApplicationConfig("lab-provider");
        // lab 约定：默认关闭 QoS（22222 端口）；-Dlab.qos=true 打开（治理实验用）
        application.setQosEnable(qos);

        List<ProtocolConfig> protocolConfigs = new ArrayList<>();
        for (String name : protocols.split(",")) {
            String protocolName = name.trim();
            // tri 用独立端口（默认 50051，TripleProtocol 默认端口）
            int protocolPort = "tri".equals(protocolName) ? triPort : port;
            ProtocolConfig protocolConfig = new ProtocolConfig(protocolName, protocolPort);
            if (threads > 0) {
                protocolConfig.setThreads(threads);
            }
            if (queues >= 0) {
                protocolConfig.setQueues(queues);
            }
            protocolConfigs.add(protocolConfig);
        }

        DubboBootstrap.getInstance()
                .application(application)
                .registry(new RegistryConfig(registry))
                .protocols(protocolConfigs)
                .service(service)
                .start()
                .await();
    }
}
