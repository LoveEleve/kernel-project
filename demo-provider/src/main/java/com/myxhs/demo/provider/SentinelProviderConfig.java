package com.myxhs.demo.provider;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * L45 实验 C：服务层限流（SCA 自动装配的 WebMVC 适配器，SentinelWebInterceptor）。
 *
 * <p>资源名 = Spring MVC 的 {@code BEST_MATCHING_PATTERN_ATTRIBUTE}（这里是 /echo/{msg}），
 * <b>不是</b>原始 URI（/echo/hi）——资源名配错规则不生效是常见坑。
 *
 * <p>对照：网关层规则拦在入口（请求根本不到 provider）；服务层规则拦在 provider 内部
 * （请求已到达，被 SentinelWebInterceptor 挡在 Controller 之前）。
 */
@Configuration
public class SentinelProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(SentinelProviderConfig.class);

    @PostConstruct
    public void initFlowRules() {
        List<FlowRule> rules = new ArrayList<>();
        // 服务层限流：/echo/{msg} QPS=1（单机）
        rules.add(new FlowRule("/echo/{msg}")
                .setCount(1)
                .setGrade(RuleConstant.FLOW_GRADE_QPS));
        FlowRuleManager.loadRules(rules);
        log.info("[L45-Provider] 服务层限流规则已加载: {}", rules);
    }
}
