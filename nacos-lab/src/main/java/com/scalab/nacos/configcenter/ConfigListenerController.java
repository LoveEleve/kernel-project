package com.scalab.nacos.configcenter;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.listener.Listener;
import com.scalab.nacos.config.DemoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A-4: 配置监听 + 动态刷新
 *
 * 两种刷新方式对比：
 * 1. @Value + @RefreshScope    — NacosContextRefresher 检测变更 → 重建 Bean（本次实验）
 * 2. ConfigService.addListener — 编程式，自定义回调，不重建 Bean（本次实验）
 * 3. @NacosValue(autoRefreshed=true) — Nacos 2.x+ push 模式，不重建 Bean（sdk 层已废弃，不推荐）
 */
@RestController
@RefreshScope
public class ConfigListenerController {

    private static final Logger log = LoggerFactory.getLogger(ConfigListenerController.class);

    @Autowired
    private NacosConfigManager nacosConfigManager;

    @Autowired
    private DemoProperties demoProperties;

    // ===== 方式一：@Value + @RefreshScope =====
    @Value("${demo.welcome:暂无欢迎语}")
    private String welcome;

    // ===== 方式二：编程式 ConfigService 监听 =====
    private final AtomicInteger changeCount = new AtomicInteger(0);
    private volatile String lastValue = "未触发";

    /**
     * 注册编程式监听器（首次调用时懒加载）
     */
    private void ensureListener() {
        if (changeCount.get() > 0) return; // 已注册过
        try {
            nacosConfigManager.getConfigService().addListener(
                    "nacos-lab.yaml", "DEFAULT_GROUP",
                    new Listener() {
                        @Override
                        public Executor getExecutor() {
                            return null;
                        }

                        @Override
                        public void receiveConfigInfo(String configInfo) {
                            int count = changeCount.incrementAndGet();
                            lastValue = configInfo;
                            log.info("[编程式监听] 第{}次变更: {}", count, configInfo);
                        }
                    });
            log.info("[编程式监听] 已注册: nacos-lab.yaml");
        } catch (Exception e) {
            log.warn("[编程式监听] 注册失败: {}", e.getMessage());
        }
    }

    @GetMapping("/config/refresh-status")
    public Map<String, Object> refreshStatus() {
        ensureListener();

        Map<String, Object> result = new HashMap<>();
        result.put("refreshScope_welcome", welcome);
        result.put("listener_changeCount", changeCount.get());
        result.put("listener_lastValue", lastValue);
        return result;
    }

    /**
     * @ConfigurationProperties vs @Value + @RefreshScope 对比
     *
     * 修改 Nacos 配置后调这个接口，验证两种方式都能拿到新值：
     * - properties_* 来自 @ConfigurationProperties（Bean 不重建，只更新属性）
     * - value_* 来自 @Value + @RefreshScope（Bean 代理重建）
     */
    @GetMapping("/config/refresh-compare")
    public Map<String, Object> refreshCompare() {
        Map<String, Object> result = new HashMap<>();

        // @ConfigurationProperties 方式
        result.put("properties_message", demoProperties.getMessage());
        result.put("properties_version", demoProperties.getVersion());
        result.put("properties_welcome", demoProperties.getWelcome());

        // @Value + @RefreshScope 方式
        result.put("value_welcome", welcome);

        result.put("note", "@ConfigurationProperties 不重建 Bean（粒度更细）| "
                + "@Value+@RefreshScope 重建 Bean（适合少量属性）");

        return result;
    }
}
