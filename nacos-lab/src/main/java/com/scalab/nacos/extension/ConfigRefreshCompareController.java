package com.scalab.nacos.extension;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * B2 验证端点：比较自定义 Listener vs @RefreshScope 的差异
 *
 * 两种路径同时触发（同一个 dataId 上注册了两个 Listener）：
 *   NacosContextRefresher 的 Listener → RefreshScope 全量刷新
 *   自定义 CustomConfigChangeHandler 的 Listener → 精确处理
 */
@RestController
public class ConfigRefreshCompareController {

    @Autowired
    private CustomConfigChangeHandler customHandler;

    /**
     * 查询自定义 Listener 的变更历史
     */
    @GetMapping("/config/custom-listener-status")
    public Map<String, Object> status() {
        Map<String, Object> result = new HashMap<>();
        result.put("changeCount", customHandler.getChangeCount());
        result.put("lastChangeTime", customHandler.getLastChangeTime() > 0
                ? Instant.ofEpochMilli(customHandler.getLastChangeTime()).toString()
                : "never");
        result.put("lastConfigContent", customHandler.getLastConfigContent());

        if (customHandler.getChangeCount() == 0) {
            result.put("note", "修改 Nacos 配置 → 等 3s → 再调这个接口 → changeCount 应为 1。"
                    + "同时调 /config/refresh-compare 对比 RefreshScope 路径是否也刷新了。");
        } else {
            result.put("note", "自定义 Listener 已触发 " + customHandler.getChangeCount()
                    + " 次。这证明了 ConfigService.addListener() 可以不依赖 @RefreshScope 工作。");
        }

        return result;
    }
}
