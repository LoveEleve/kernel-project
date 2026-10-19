package com.scalab.nacos.extension;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.listener.Listener;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * B2: 自定义 ConfigChangedListener — 不使用 @RefreshScope，通过 Nacos SDK 原生
 * ConfigService.addListener() 实现精确到单个配置项的变更处理。
 *
 * 与 NacosContextRefresher 的对比：
 *
 * NacosContextRefresher 路径（全量刷新）:
 *   ConfigService.addListener → AbstractSharedListener
 *     → publishEvent(RefreshEvent)
 *       → ContextRefresher.refresh()
 *         → RefreshScope.refreshAll()
 *           → 销毁所有 @RefreshScope Bean → 重建
 *           → 代价：连接池/线程池/缓存全部重置
 *
 * 本 Handler 路径（精确刷新）:
 *   ConfigService.addListener → 自定义 Listener.receiveConfigInfo(configInfo)
 *     → 解析 configInfo，提取变化的 key
 *       → 只更新受影响的业务对象（如调整阈值、开关切换）
 *       → 其他 Bean 完全不受影响
 *
 * 验证方法：
 *   1. 启动，日志输出 "[CustomListener] 已注册 on nacos-lab.yaml"
 *   2. 调 /config/custom-listener-status → changeCount=0
 *   3. Nacos 控制台修改配置
 *   4. 等 3s，调 /config/custom-listener-status → changeCount=1
 *   5. 对比 /config/refresh-compare → RefreshScope 路径也同时刷新了
 *      （因为同一个 dataId 上 NacosContextRefresher 也注册了 Listener）
 */
@Component
public class CustomConfigChangeHandler {

    private static final Logger log = LoggerFactory.getLogger(CustomConfigChangeHandler.class);

    private static final String DATA_ID = "nacos-lab.yaml";
    private static final String GROUP = "DEFAULT_GROUP";

    @Autowired
    private NacosConfigManager nacosConfigManager;

    private final AtomicInteger changeCount = new AtomicInteger(0);
    private final AtomicLong lastChangeTime = new AtomicLong(0);
    private volatile String lastConfigContent = null;

    @PostConstruct
    public void init() {
        try {
            nacosConfigManager.getConfigService().addListener(DATA_ID, GROUP,
                    new Listener() {
                        @Override
                        public Executor getExecutor() {
                            // null → 使用 Nacos SDK 内部线程池
                            // 生产环境应传入自定义线程池，避免阻塞 Nacos 内部线程
                            return null;
                        }

                        @Override
                        public void receiveConfigInfo(String configInfo) {
                            int count = changeCount.incrementAndGet();
                            lastChangeTime.set(System.currentTimeMillis());
                            lastConfigContent = configInfo;

                            log.info("[CustomListener] 第{}次变更: {} -> {}",
                                    count, DATA_ID, configInfo.replace("\n", " | "));
                        }
                    });
            log.info("[CustomListener] 已注册: dataId={}, group={}", DATA_ID, GROUP);
        } catch (Exception e) {
            log.error("[CustomListener] 注册失败: {}", e.getMessage(), e);
        }
    }

    public int getChangeCount() {
        return changeCount.get();
    }

    public long getLastChangeTime() {
        return lastChangeTime.get();
    }

    public String getLastConfigContent() {
        return lastConfigContent;
    }
}
