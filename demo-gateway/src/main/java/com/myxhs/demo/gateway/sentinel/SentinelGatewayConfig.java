package com.myxhs.demo.gateway.sentinel;

import com.alibaba.cloud.sentinel.datasource.converter.JsonConverter;
import com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayParamFlowItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.adapter.spring.webflux.callback.WebFluxCallbackManager;
import com.alibaba.csp.sentinel.datasource.Converter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * L45：Gateway × Sentinel 接入（对齐 xhs-project 生产接线：starter + raw gateway-adapter）。
 *
 * <p>三件事：
 * <ol>
 *   <li>{@link SentinelGatewayFilter}：route 级 Sentinel 入口（raw 适配器不自带 Spring 装配，需手动注册 bean）</li>
 *   <li>限流规则：本地程序化加载；若配置了 Nacos 数据源则跳过（等待异步推送，见实验 D）</li>
 *   <li>自定义 BlockHandler：Gateway 适配器 + WebFlux 适配器（starter 自动装配）两个回调都注册</li>
 * </ol>
 *
 * <p>注意：Spring Cloud Gateway（WebFlux 应用）里实际有两套 Sentinel 适配器：
 * starter 的 {@code SentinelWebFluxFilter}（URL 级资源）+ 本类的 {@code SentinelGatewayFilter}（route 级资源）。
 * 1.8.8 的默认 BlockHandler 编译于 Spring 5.x（{@code ServerResponse.status(HttpStatus)} / {@code syncBody}
 * 在 WebFlux 6.1.6 已移除）→ 触发限流会 NoSuchMethodError、请求悬挂；必须自定义（见 T-106）。
 */
@Configuration
public class SentinelGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(SentinelGatewayConfig.class);

    /** 统一限流响应体（与网关其余错误体格式保持一致） */
    private static final String BLOCK_BODY =
            "{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\",\"data\":null}";

    /**
     * 实验开关：
     * false = 保持 Sentinel 1.8.8 默认 BlockHandler（复现 T-106：Spring 6.1.6 上悬挂/报错）；
     * true  = 自定义统一 429 JSON（生产姿势）。
     */
    @Value("${demo.sentinel.custom-block-handler:true}")
    private boolean customBlockHandler;

    /** route 级 Sentinel 入口（raw 适配器需手动注册为 GlobalFilter bean；order = HIGHEST_PRECEDENCE） */
    @Bean
    public SentinelGatewayFilter sentinelGatewayFilter() {
        return new SentinelGatewayFilter();
    }

    /**
     * 实验 D：gw-flow 规则的 JSON 反序列化器（Nacos 规则数据源用）。
     *
     * <p>SCA 已知问题：{@code SentinelDataSourceHandler} 对 rule-type=gw-flow 会按名查找 bean
     * {@code sentinel-json-gw-flow-converter}，但不会自动注册（普通 flow 等会自动注册），
     * 不提供则 Nacos 数据源创建失败 —— xhs-project GatewayConfig 同款处理。
     */
    @Bean("sentinel-json-gw-flow-converter")
    public Converter<String, GatewayFlowRule> sentinelGwFlowConverter(ObjectMapper objectMapper) {
        return new JsonConverter<>(objectMapper, GatewayFlowRule.class);
    }

    @PostConstruct
    public void initBlockHandlers() {
        if (!customBlockHandler) {
            log.warn("[L45-Sentinel] 自定义 BlockHandler 已禁用 —— 将复现 1.8.8 默认 Handler 的 Spring 6 兼容问题");
            return;
        }
        // Gateway 适配器（route 级资源被限流时走这里）
        GatewayCallbackManager.setBlockHandler((exchange, t) -> blockResponse(exchange, t, "gateway"));
        // WebFlux 适配器（starter 自动装配的 SentinelWebFluxFilter，URL 级资源被限流时走这里）
        // ★ T-106：只注册 GatewayCallbackManager 不够 —— 实际生效的 WebExceptionHandler 是 WebFlux 那个
        WebFluxCallbackManager.setBlockHandler((exchange, t) -> blockResponse(exchange, t, "webflux"));
        log.info("[L45-Sentinel] 自定义 BlockHandler 已注册（Gateway + WebFlux 双回调）");
    }

    private Mono<ServerResponse> blockResponse(ServerWebExchange exchange, Throwable t, String source) {
        log.info("[L45-Sentinel][{}] 请求被限流: resource={}, path={}",
                source, t.getClass().getSimpleName(), exchange.getRequest().getURI().getPath());
        return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Mono.just(BLOCK_BODY.getBytes(StandardCharsets.UTF_8)), byte[].class);
    }

    /**
     * 限流规则加载。
     *
     * <p>用 {@link ApplicationReadyEvent} 而不是 {@code @PostConstruct}：Nacos 数据源是异步拉取的，
     * 过早 loadRules 会覆盖随后推送的规则（生产踩坑，见 xhs RateLimitFilter 注释）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initFlowRules(ApplicationReadyEvent event) {
        Environment env = event.getApplicationContext().getEnvironment();
        String nacosAddr = env.getProperty("spring.cloud.sentinel.datasource.flow.nacos.server-addr");
        if (nacosAddr != null && !nacosAddr.isEmpty()) {
            log.info("[L45-Sentinel] Nacos 规则数据源已配置({})，跳过本地规则，等待异步推送", nacosAddr);
            return;
        }
        Set<GatewayFlowRule> rules = new HashSet<>();
        // 实验 B：参数级限流（热点参数）—— 同一路由下按 X-User-Id header 分流：每个用户独立额度 1 次/10s
        // （对照 L36 的 RequestRateLimiter：KeyResolver 只能"选 key"，没有现成的按参数值独立阈值 + 参数类型解析）
        rules.add(new GatewayFlowRule("sentinel-lab")
                .setCount(1)
                .setIntervalSec(10)
                .setParamItem(new GatewayParamFlowItem()
                        .setParseStrategy(SentinelGatewayConstants.PARAM_PARSE_STRATEGY_HEADER)
                        .setFieldName("X-User-Id")));
        GatewayRuleManager.loadRules(rules);
        log.info("[L45-Sentinel] 本地限流规则已加载: {}", rules);
    }
}
