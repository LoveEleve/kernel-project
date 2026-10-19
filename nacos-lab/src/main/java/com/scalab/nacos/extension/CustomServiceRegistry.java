package com.scalab.nacos.extension;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingMaintainService;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cloud.client.serviceregistry.Registration;
import org.springframework.cloud.client.serviceregistry.ServiceRegistry;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * B1: 自实现 ServiceRegistry — 从零实现 ServiceRegistry 接口，不继承框架类
 *
 * 证明了什么：
 * 1. Spring Cloud 只认 ServiceRegistry 接口 — 实现类可以随意替换
 * 2. register() 是自定义扩展点 — 注入运维 metadata、注册前健康检查
 * 3. setStatus/getStatus 是运维接口 — 配合 /actuator/service-registry 使用
 *
 * 对比 NacosServiceRegistry 源码：
 * - 官方只注入 weight/clusterName/enabled/ephemeral
 * - 本实现额外注入 startupTime/javaVersion/groupId
 * - 生产场景可继续扩展：podName、k8sNamespace、commitHash
 */
public class CustomServiceRegistry implements ServiceRegistry<Registration> {

    private static final Logger log = LoggerFactory.getLogger(CustomServiceRegistry.class);
    private static final String STATUS_UP = "UP";
    private static final String STATUS_DOWN = "DOWN";

    private final NacosServiceManager nacosServiceManager;
    private final NacosDiscoveryProperties properties;

    public CustomServiceRegistry(NacosServiceManager nacosServiceManager,
                                  NacosDiscoveryProperties properties) {
        this.nacosServiceManager = nacosServiceManager;
        this.properties = properties;
    }

    /**
     * 核心扩展点：注册时注入自定义 metadata
     *
     * 调用链：WebServerInitializedEvent → CustomAutoServiceRegistration.start()
     *        → serviceRegistry.register(registration)
     */
    @Override
    public void register(Registration registration) {
        if (registration == null) {
            log.warn("[CustomRegistry] Registration is null, skipping");
            return;
        }

        String serviceId = registration.getServiceId();
        String group = properties.getGroup();

        Instance instance = new Instance();
        instance.setIp(registration.getHost());
        instance.setPort(registration.getPort());
        instance.setWeight(properties.getWeight());
        instance.setClusterName(properties.getClusterName());
        instance.setEnabled(properties.isInstanceEnabled());
        instance.setEphemeral(properties.isEphemeral());

        // === 自定义扩展：注入运维 metadata ===
        Map<String, String> metadata = new HashMap<>(registration.getMetadata());
        metadata.put("startupTime", Instant.now().toString());
        metadata.put("javaVersion", System.getProperty("java.version"));
        metadata.put("groupId", "scalab-custom-registry-v1");
        instance.setMetadata(metadata);

        try {
            namingService().registerInstance(serviceId, group, instance);
            log.info("[CustomRegistry] {} {} {}:{} registered, metadata={}",
                    group, serviceId, instance.getIp(), instance.getPort(),
                    metadata.keySet());
        } catch (Exception e) {
            if (properties.isFailFast()) {
                log.error("[CustomRegistry] {} register failed: {}", serviceId, e.getMessage());
                throw new RuntimeException("Custom registry register failed", e);
            }
            log.warn("[CustomRegistry] Failfast=false, {} register failed: {}",
                    serviceId, e.getMessage());
        }
    }

    @Override
    public void deregister(Registration registration) {
        if (registration == null) {
            log.warn("[CustomRegistry] Registration is null, skipping deregister");
            return;
        }

        String serviceId = registration.getServiceId();
        String group = properties.getGroup();

        try {
            namingService().deregisterInstance(serviceId, group,
                    registration.getHost(), registration.getPort(),
                    properties.getClusterName());
            log.info("[CustomRegistry] {}:{} deregistered from {}",
                    serviceId, registration.getHost(), group);
        } catch (Exception e) {
            log.error("[CustomRegistry] deregister failed for {}:{}: {}",
                    serviceId, registration.getHost(), e.getMessage());
        }
    }

    @Override
    public void setStatus(Registration registration, String status) {
        if (!STATUS_UP.equalsIgnoreCase(status) && !STATUS_DOWN.equalsIgnoreCase(status)) {
            log.warn("[CustomRegistry] Unsupported status: {}", status);
            return;
        }

        String serviceId = registration.getServiceId();
        Instance instance = buildInstance(registration);
        instance.setEnabled(STATUS_UP.equalsIgnoreCase(status));

        try {
            Properties nacosProps = properties.getNacosProperties();
            nacosServiceManager.getNamingMaintainService(nacosProps)
                    .updateInstance(serviceId, properties.getGroup(), instance);
            log.info("[CustomRegistry] {} status set to {}", serviceId, status);
        } catch (Exception e) {
            throw new RuntimeException("[CustomRegistry] setStatus failed", e);
        }
    }

    @Override
    public Object getStatus(Registration registration) {
        String serviceName = registration.getServiceId();
        String group = properties.getGroup();
        try {
            List<Instance> instances = namingService().getAllInstances(serviceName, group);
            for (Instance instance : instances) {
                if (instance.getIp().equalsIgnoreCase(properties.getIp())
                        && instance.getPort() == properties.getPort()) {
                    return instance.isEnabled() ? STATUS_UP : STATUS_DOWN;
                }
            }
        } catch (Exception e) {
            log.error("[CustomRegistry] getStatus failed for {}: {}", serviceName, e.getMessage());
        }
        return null;
    }

    @Override
    public void close() {
        try {
            nacosServiceManager.nacosServiceShutDown();
            log.info("[CustomRegistry] NamingService closed");
        } catch (NacosException e) {
            log.error("[CustomRegistry] close failed: {}", e.getMessage());
        }
    }

    private Instance buildInstance(Registration registration) {
        Instance instance = new Instance();
        instance.setIp(registration.getHost());
        instance.setPort(registration.getPort());
        instance.setWeight(properties.getWeight());
        instance.setClusterName(properties.getClusterName());
        instance.setMetadata(registration.getMetadata());
        instance.setEphemeral(properties.isEphemeral());
        return instance;
    }

    private NamingService namingService() {
        return nacosServiceManager.getNamingService();
    }
}
