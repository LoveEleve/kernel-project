package com.myxhs.demo.lb;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.DelegatingServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * L16 自定义负载均衡②：<b>同 Zone 优先（Zone 亲和）</b>。
 *
 * <p>用途：网关自己在一个 zone（如 shanghai），**优先**把流量发给**同 zone** 的实例；
 * 同 zone 没有可用实例时，<b>回退</b>到全部实例（避免"同区全挂 → 整个不可用"）。
 *
 * <p>对比灰度：<b>灰度是"筛掉不要的"，Zone 是"优先挑想要的（挑不到就用全部）"</b> —— 语义不同。
 * （SCLB 也内置了 {@code withZonePreference()}；这里手写一遍是为了看清"选实例"的实现方式。）
 */
public class ZonePreferredInstanceListSupplier extends DelegatingServiceInstanceListSupplier {

    private static final String ZONE_KEY = "zone";

    /** 调用方（网关）自己所在的 zone */
    private final String preferredZone;

    public ZonePreferredInstanceListSupplier(ServiceInstanceListSupplier delegate, String preferredZone) {
        super(delegate);
        this.preferredZone = preferredZone;
    }

    @Override
    public Flux<List<ServiceInstance>> get() {
        return delegate.get().map(instances -> {
            List<ServiceInstance> sameZone = instances.stream()
                    .filter(this::inPreferredZone)
                    .toList();
            // 同 zone 有实例 → 只用它们；否则回退全部（高可用优先）
            return sameZone.isEmpty() ? instances : sameZone;
        });
    }

    private boolean inPreferredZone(ServiceInstance instance) {
        return preferredZone.equals(instance.getMetadata().get(ZONE_KEY));
    }
}
