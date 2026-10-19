package com.scalab.nacos.configcenter;

import com.scalab.nacos.config.DemoProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 共享配置演示
 *
 * 三维配置模型：
 *   Data ID  — 配置文件名（app name + profile）
 *   Group    — 功能分组（DATASOURCE_GROUP, REDIS_GROUP, DEFAULT_GROUP）
 *   Namespace — 环境隔离（sca-lab-dev）
 *
 * 共享机制：
 *   多个应用通过 spring.config.import 引入同一份 shared-*.yaml，
 *   无需在每个应用里重复定义 DB/Redis/MQ 连接信息。
 *   修改一处 → 所有引用的应用自动感知（需 @RefreshScope 或 @ConfigurationProperties）
 */
@RestController
public class SharedConfigController {

    @Autowired
    private DemoProperties demoProperties;

    @Autowired
    private SharedDatasourceProperties sharedDatasource;

    @Autowired
    private SharedRedisProperties sharedRedis;

    /**
     * 查看所有配置来源：自有 + 共享
     * 对比 Data ID / Group / Namespace 三维
     */
    @GetMapping("/config/all-sources")
    public Map<String, Object> allSources() {
        Map<String, Object> result = new HashMap<>();

        // 自有配置（nacos-lab.yaml, group=DEFAULT_GROUP）
        Map<String, Object> own = new HashMap<>();
        own.put("dataId", "nacos-lab.yaml");
        own.put("group", "DEFAULT_GROUP");
        own.put("message", demoProperties.getMessage());
        own.put("version", demoProperties.getVersion());
        own.put("welcome", demoProperties.getWelcome());
        result.put("own_config", own);

        // 共享配置 1（shared-datasource.yaml, group=DATASOURCE_GROUP）
        Map<String, Object> ds = new HashMap<>();
        ds.put("dataId", "shared-datasource.yaml");
        ds.put("group", "DATASOURCE_GROUP");
        ds.put("url", sharedDatasource.getUrl());
        ds.put("username", sharedDatasource.getUsername());
        ds.put("maxPoolSize", sharedDatasource.getMaxPoolSize());
        ds.put("minIdle", sharedDatasource.getMinIdle());
        ds.put("connectTimeout", sharedDatasource.getConnectTimeout());
        result.put("shared_datasource", ds);

        // 共享配置 2（shared-redis.yaml, group=REDIS_GROUP）
        Map<String, Object> redis = new HashMap<>();
        redis.put("dataId", "shared-redis.yaml");
        redis.put("group", "REDIS_GROUP");
        redis.put("host", sharedRedis.getHost());
        redis.put("port", sharedRedis.getPort());
        redis.put("timeout", sharedRedis.getTimeout());
        redis.put("maxTotal", sharedRedis.getMaxTotal());
        result.put("shared_redis", redis);

        result.put("note", "三个配置来自不同 Data ID + Group，但同属 sca-lab-dev namespace。"
                + "修改共享配置后，/config/all-sources 应自动看到新值。");

        return result;
    }
}
