package com.scalab.loadbalancer.support;

import org.springframework.cloud.client.ServiceInstance;

/**
 * LoadBalancer 实现的公共工具。
 */
public final class InstanceKeys {

    private InstanceKeys() {
    }

    /**
     * 实例标识：host:port。避免依赖 ServiceInstance 的 equals/hashCode
     * （注册中心刷新会产生"新对象"，map 按对象做 key 会累积内存——D6 review 发现的坑）。
     */
    public static String of(ServiceInstance instance) {
        return instance.getHost() + ":" + instance.getPort();
    }
}
