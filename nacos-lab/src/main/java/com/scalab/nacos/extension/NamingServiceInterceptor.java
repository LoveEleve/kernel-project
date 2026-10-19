package com.scalab.nacos.extension;

import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * B4: 自定义 NamingService 拦截器 — 通过包装模式拦截 SDK 层的服务发现操作
 *
 * B1 替换了 Spring Cloud 层的 ServiceRegistry（注册入口）
 * B4 拦截 Nacos SDK 层的 NamingService（服务发现入口）
 *
 * B1 vs B4 的控制粒度：
 *   B1: 控制 "谁被注册到 Spring Cloud" — LoadBalancer / DiscoveryClient 走这条
 *   B4: 拦截 "Nacos SDK 的原始调用" — registerInstance / deregisterInstance / selectInstances
 *
 * 设计：用包装模式（Decorator）而非实现 NamingService 接口
 * 原因：Nacos 3.x 的 NamingService 接口有 40+ 方法且每个版本都变化
 * 包装模式：只拦截关心的几个方法，其余透传
 */
@Component
public class NamingServiceInterceptor {

    private static final Logger log = LoggerFactory.getLogger(NamingServiceInterceptor.class);

    private final AtomicLong registerCount = new AtomicLong(0);
    private final AtomicLong deregisterCount = new AtomicLong(0);
    private final AtomicLong queryCount = new AtomicLong(0);

    @Autowired
    private NacosServiceManager nacosServiceManager;

    private NamingService namingService;

    @PostConstruct
    public void init() {
        this.namingService = nacosServiceManager.getNamingService();
        log.info("[NamingInterceptor] Initialized");
    }

    /**
     * 注册拦截
     */
    public void registerInstance(String serviceName, String groupName, Instance instance) throws NacosException {
        registerCount.incrementAndGet();
        log.info("[NamingInterceptor] register #{}: {}@{} {}:{}, metadata={}",
                registerCount.get(), serviceName, groupName, instance.getIp(), instance.getPort(),
                instance.getMetadata().keySet());
        namingService.registerInstance(serviceName, groupName, instance);
    }

    /**
     * 注销拦截
     */
    public void deregisterInstance(String serviceName, String groupName, String ip, int port, String clusterName) throws NacosException {
        deregisterCount.incrementAndGet();
        log.info("[NamingInterceptor] deregister #{}: {}@{} {}:{}/{}",
                deregisterCount.get(), serviceName, groupName, ip, port, clusterName);
        namingService.deregisterInstance(serviceName, groupName, ip, port, clusterName);
    }

    /**
     * 查询拦截
     */
    public List<Instance> selectInstances(String serviceName, String groupName, boolean healthy) throws NacosException {
        queryCount.incrementAndGet();
        List<Instance> result = namingService.selectInstances(serviceName, groupName, healthy);
        log.debug("[NamingInterceptor] selectInstances #{}: {}@{} → {} (healthy={})",
                queryCount.get(), serviceName, groupName, result.size(), healthy);
        return result;
    }

    /**
     * 获取被包装的原始 NamingService（用于其他未拦截的方法）
     */
    public NamingService getDelegate() {
        return nacosServiceManager.getNamingService();
    }

    public long getRegisterCount() { return registerCount.get(); }
    public long getDeregisterCount() { return deregisterCount.get(); }
    public long getQueryCount() { return queryCount.get(); }
}
