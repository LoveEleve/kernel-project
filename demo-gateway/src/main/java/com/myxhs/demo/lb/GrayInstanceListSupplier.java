package com.myxhs.demo.lb;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.DelegatingServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * L16 自定义负载均衡①：<b>灰度（按实例 metadata 的 version 过滤）</b>。
 *
 * <p>用途：只把流量发给 <b>version=v2</b> 的实例（金丝雀发布 / 灰度验证）。
 * 实例的 metadata 是**服务实例自己注册时上报的**（见 demo-provider 的
 * {@code spring.cloud.nacos.discovery.metadata.version}）。
 *
 * <p>要点：继承 {@link DelegatingServiceInstanceListSupplier}，只<b>过滤</b> {@code delegate} 给的实例列表 ——
 * 这就是"**选实例**"（与"染色/打标签"分开：打标签在**实例注册**时做，选实例在**消费端**做）。
 */
public class GrayInstanceListSupplier extends DelegatingServiceInstanceListSupplier {

    /** 实例 metadata 的 key（与 demo-provider 上报的一致） */
    private static final String VERSION_KEY = "version";

    private final String requiredVersion;

    public GrayInstanceListSupplier(ServiceInstanceListSupplier delegate, String requiredVersion) {
        super(delegate);
        this.requiredVersion = requiredVersion;
    }

    @Override
    public Flux<List<ServiceInstance>> get() {
        return delegate.get().map(instances -> instances.stream()
                .filter(this::matches)
                .toList());
    }

    private boolean matches(ServiceInstance instance) {
        return requiredVersion.equals(instance.getMetadata().get(VERSION_KEY));
    }
}
