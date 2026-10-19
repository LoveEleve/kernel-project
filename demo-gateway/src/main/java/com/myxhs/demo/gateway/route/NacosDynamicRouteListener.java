package com.myxhs.demo.gateway.route;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.AbstractListener;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L44：Nacos 配置中心动态路由（方案 B：自定义监听器 + RouteDefinitionWriter）。
 *
 * <p>从 dataId={@value #DATA_ID} 读取路由 JSON 数组；配置变更时：
 * <ul>
 *   <li>新增/覆盖路由 → {@link RouteDefinitionWriter#save}</li>
 *   <li>删除已下线的托管路由 → {@link RouteDefinitionWriter#delete}</li>
 *   <li>发布 {@link RefreshRoutesEvent} → CachingRouteLocator 重建路由表（无需重启）</li>
 * </ul>
 */
@Component
public class NacosDynamicRouteListener implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(NacosDynamicRouteListener.class);

    private static final String DATA_ID = "demo-gateway-dynamic-routes.json";
    private static final String GROUP = "DEFAULT_GROUP";

    private final NacosConfigManager nacosConfigManager;
    private final RouteDefinitionWriter routeDefinitionWriter;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<String> managedRouteIds = ConcurrentHashMap.newKeySet();

    public NacosDynamicRouteListener(NacosConfigManager nacosConfigManager,
                                     RouteDefinitionWriter routeDefinitionWriter,
                                     ApplicationEventPublisher eventPublisher) {
        this.nacosConfigManager = nacosConfigManager;
        this.routeDefinitionWriter = routeDefinitionWriter;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            ConfigService configService = nacosConfigManager.getConfigService();
            apply(configService.getConfig(DATA_ID, GROUP, 5000));
            configService.addListener(DATA_ID, GROUP, new AbstractListener() {
                @Override
                public void receiveConfigInfo(String configInfo) {
                    log.info("[nacos-route] config changed: {}@{}", DATA_ID, GROUP);
                    apply(configInfo);
                }
            });
            log.info("[nacos-route] listener registered for {}@{}", DATA_ID, GROUP);
        } catch (Exception e) {
            log.warn("[nacos-route] init failed: {}", e.toString());
        }
    }

    private void apply(String content) {
        try {
            List<RouteDefinition> routes = (content == null || content.isBlank())
                    ? List.of()
                    : objectMapper.readValue(content, new TypeReference<List<RouteDefinition>>() {
                    });
            Set<String> newIds = new HashSet<>();
            for (RouteDefinition route : routes) {
                newIds.add(route.getId());
                routeDefinitionWriter.save(Mono.just(route)).block();
            }
            for (String oldId : managedRouteIds) {
                if (!newIds.contains(oldId)) {
                    routeDefinitionWriter.delete(Mono.just(oldId)).block();
                }
            }
            managedRouteIds.clear();
            managedRouteIds.addAll(newIds);
            eventPublisher.publishEvent(new RefreshRoutesEvent(this));
            log.info("[nacos-route] applied {} route(s): {}", routes.size(), newIds);
        } catch (Exception e) {
            log.warn("[nacos-route] apply failed: {}", e.toString());
        }
    }
}
