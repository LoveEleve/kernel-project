package com.scalab.nacos.configcenter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * A-3/4: 配置中心 + 动态刷新
 * 通过 Nacos 控制台修改配置后，调用 /config/current 验证实时生效
 */
@RestController
@RefreshScope
public class DynamicConfigController {

    @Value("${demo.message:hello-from-default}")
    private String demoMessage;

    @Value("${demo.version:v0.0.0}")
    private String demoVersion;

    /**
     * 查看当前配置值（修改 Nacos 后无需重启，直接刷新）
     */
    @GetMapping("/config/current")
    public Map<String, Object> current() {
        Map<String, Object> result = new HashMap<>();
        result.put("demo.message", demoMessage);
        result.put("demo.version", demoVersion);
        return result;
    }
}
