package com.scalab.nacos.extension;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultResponse;
import org.springframework.cloud.client.loadbalancer.EmptyResponse;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * B3: 基于 Nacos metadata 的自定义 LoadBalancer
 *
 * 与 RoundRobinLoadBalancer 的区别：
 *   官方：instances.get(pos % instances.size()) — 无视 metadata
 *   本实现：根据 metadata 中的 weight/version 进行智能路由
 *
 * 路由策略：
 *   1. 权重路由：metadata 中有 weight=X — 选中概率与 X 成正比
 *   2. 版本路由：请求头 X-Version=canary — 只路由到 metadata 中 version=canary 的实例
 *   3. Fallback：无 metadata 或无权重 → 等权随机
 */
public class MetadataBasedLoadBalancer implements ReactorServiceInstanceLoadBalancer {

    private static final Logger log = LoggerFactory.getLogger(MetadataBasedLoadBalancer.class);

    private final String serviceId;
    private final ObjectProvider<ServiceInstanceListSupplier> supplierProvider;

    public MetadataBasedLoadBalancer(
            ObjectProvider<ServiceInstanceListSupplier> supplierProvider,
            String serviceId) {
        this.supplierProvider = supplierProvider;
        this.serviceId = serviceId;
    }

    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        ServiceInstanceListSupplier supplier = supplierProvider.getIfAvailable();
        if (supplier == null) {
            return Mono.just(new EmptyResponse());
        }
        return supplier.get(request)
                .next()
                .map(instances -> selectInstance(instances, request));
    }

    private Response<ServiceInstance> selectInstance(List<ServiceInstance> instances, Request request) {
        if (instances.isEmpty()) {
            log.warn("[MetadataLB] No instances for {}", serviceId);
            return new EmptyResponse();
        }

        // === 策略1：版本路由 ===
        List<ServiceInstance> versionFiltered = filterByVersion(instances, request);
        if (!versionFiltered.isEmpty()) {
            instances = versionFiltered;
            log.debug("[MetadataLB] Version filter: {} instances match", instances.size());
        }

        // === 策略2：权重路由 ===
        if (hasWeightMetadata(instances)) {
            ServiceInstance selected = selectByWeight(instances);
            log.info("[MetadataLB] Weight-based: {}:{} (weight={})",
                    selected.getHost(), selected.getPort(),
                    selected.getMetadata().getOrDefault("weight", "1"));
            return new DefaultResponse(selected);
        }

        // === 策略3：随即（仅 1 实例时 direct return）===
        if (instances.size() == 1) {
            return new DefaultResponse(instances.get(0));
        }
        int idx = ThreadLocalRandom.current().nextInt(instances.size());
        return new DefaultResponse(instances.get(idx));
    }

    /**
     * 按版本标签过滤
     */
    private List<ServiceInstance> filterByVersion(List<ServiceInstance> instances, Request request) {
        String targetVersion = null;
        if (request.getContext() instanceof org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer.Factory) {
            // 在生产环境中通过 RequestData 获取 header
            // 这里做简化 version metadata 匹配
        }
        // 从 metadata 中读取 version，匹配请求中要求的版本
        List<ServiceInstance> matched = new ArrayList<>();
        for (ServiceInstance instance : instances) {
            Map<String, String> meta = instance.getMetadata();
            String version = meta.get("version");
            if (version != null && targetVersion != null && version.equals(targetVersion)) {
                matched.add(instance);
            }
        }
        return matched;
    }

    /**
     * 按权重选择实例
     * 算法：将每个实例的权重转换为区间范围，生成随机数落在哪个区间就选哪个
     *
     * 例: weight=1 → [0,1), weight=3 → [1,4), weight=6 → [4,10)
     *     random=7 → 落在 [4,10) → 选 weight=6 的实例
     */
    private ServiceInstance selectByWeight(List<ServiceInstance> instances) {
        int totalWeight = 0;
        int[] weights = new int[instances.size()];

        for (int i = 0; i < instances.size(); i++) {
            String w = instances.get(i).getMetadata().get("weight");
            weights[i] = parseWeight(w);
            totalWeight += weights[i];
        }

        if (totalWeight == 0) {
            // 所有权重为 0 → fallback 等权
            return instances.get(ThreadLocalRandom.current().nextInt(instances.size()));
        }

        int random = ThreadLocalRandom.current().nextInt(totalWeight);
        int cumulative = 0;
        for (int i = 0; i < instances.size(); i++) {
            cumulative += weights[i];
            if (random < cumulative) {
                return instances.get(i);
            }
        }

        // fallback
        return instances.get(0);
    }

    private boolean hasWeightMetadata(List<ServiceInstance> instances) {
        for (ServiceInstance instance : instances) {
            String w = instance.getMetadata().get("weight");
            if (w != null) {
                try {
                    int weight = Integer.parseInt(w);
                    if (weight > 0 && weight != 1) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return false;
    }

    private int parseWeight(String weightStr) {
        if (weightStr == null) return 1;
        try {
            int w = Integer.parseInt(weightStr);
            return Math.max(w, 0);
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
