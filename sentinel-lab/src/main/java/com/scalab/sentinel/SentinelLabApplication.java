package com.scalab.sentinel;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.scalab.sentinel.core.FileFlowRuleDataSource;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityRule;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.CircuitBreakerStrategy;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.alibaba.csp.sentinel.slots.system.SystemRule;
import com.alibaba.csp.sentinel.slots.system.SystemRuleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;

@SpringBootApplication
public class SentinelLabApplication implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SentinelLabApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(SentinelLabApplication.class, args);
    }

    /**
     * A-01 + A-02: 用 ApplicationRunner 延迟加载规则（Spring 容器完全初始化后）。
     *
     * <p>不能用 @PostConstruct——此时 Sentinel 内核的 SPI Slot 链还未完全就绪，
     * FlowRuleManager/DegradeRuleManager 的规则加载可能被静默跳过。
     * ApplicationRunner 在 Spring Boot 启动完成、内嵌 Tomcat 就绪后才执行，
     * 确保 Sentinel 所有组件（SpiLoader/SlotChain/Transport）均已初始化。
     */
    @Override
    public void run(ApplicationArguments args) {
        // A-01: QPS=100 流控规则
        FlowRule flowRule = new FlowRule();
        flowRule.setResource("sentinelApi");
        flowRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        flowRule.setCount(100);
        FlowRuleManager.loadRules(Collections.singletonList(flowRule));
        log.info("[SentinelLab] Flow rules loaded: sentinelApi QPS=100");

        // A-02: 异常数=1 熔断规则
        DegradeRule degradeRule = new DegradeRule();
        degradeRule.setResource("sentinelApi");
        degradeRule.setGrade(CircuitBreakerStrategy.ERROR_COUNT.getType());
        degradeRule.setCount(1);
        degradeRule.setTimeWindow(3);
        degradeRule.setMinRequestAmount(1);
        degradeRule.setStatIntervalMs(10000);
        DegradeRuleManager.loadRules(Collections.singletonList(degradeRule));
        log.info("[SentinelLab] Degrade rules loaded: sentinelApi ERROR_COUNT=1, timeWindow=3s");

        // A-04: 热点参数限流——itemId=hot 时 QPS=1，其他放行
        ParamFlowRule paramRule = new ParamFlowRule();
        paramRule.setResource("sentinelApi");
        paramRule.setParamIdx(0);               // 第 0 个参数为热点 key
        paramRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        paramRule.setCount(100);
        paramRule.setDurationInSec(1);
        ParamFlowRuleManager.loadRules(Collections.singletonList(paramRule));
        log.info("[SentinelLab] Param rules loaded: sentinelApi paramIdx=0 QPS=100");

        // A-06: 授权规则——黑名单拦截 origin=blocked-app
        AuthorityRule authRule = new AuthorityRule();
        authRule.setResource("sentinelApi");
        authRule.setLimitApp("blocked-app");
        authRule.setStrategy(RuleConstant.AUTHORITY_BLACK);
        AuthorityRuleManager.loadRules(Collections.singletonList(authRule));
        log.info("[SentinelLab] Authority rules loaded: sentinelApi BLACK blocked-app");

        // A-05: 系统自适应——设极低 QPS 阈值，并发请求应触发 SystemBlockException
        SystemRule systemRule = new SystemRule();
        systemRule.setHighestSystemLoad(-1);     // 不限制 Load
        systemRule.setHighestCpuUsage(-1);       // 不限制 CPU
        systemRule.setAvgRt(-1);                 // 不限制 RT
        systemRule.setMaxThread(-1);             // 不限制线程
        systemRule.setQps(1);                    // 全局入口 QPS=1
        SystemRuleManager.loadRules(Collections.singletonList(systemRule));
        log.info("[SentinelLab] System rules loaded: QPS=1");

        // B-03: 从本地文件加载流控规则（FileRefreshableDataSource + JSON Converter）
        FileFlowRuleDataSource.init("/tmp/flow-rules.json");
    }

    /** 调用 nacos-lab 的 RestTemplate */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
