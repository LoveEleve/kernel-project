package com.scalab.nacos.extension;

import com.alibaba.cloud.nacos.registry.NacosRegistration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * B1: 验证自定义注册器 — 检查 CustomServiceRegistry 注入的 metadata
 */
@RestController
public class RegistrationCompareController {

    @Autowired
    private NacosRegistration registration;

    @Autowired(required = false)
    private CustomServiceRegistry customServiceRegistry;

    /**
     * 查看自定义 registry 注入的 metadata
     */
    @GetMapping("/discovery/custom-metadata")
    public Map<String, Object> customMetadata() {
        Map<String, Object> result = new HashMap<>();
        Map<String, String> metadata = registration.getMetadata();

        result.put("totalKeys", metadata.size());
        result.put("startupTime", metadata.get("startupTime"));
        result.put("javaVersion", metadata.get("javaVersion"));
        result.put("groupId", metadata.get("groupId"));
        result.put("allMetadata", metadata);

        result.put("registryClass", customServiceRegistry != null
                ? customServiceRegistry.getClass().getSimpleName() : "N/A(CustomRegistrationConfig disabled)");
        result.put("note", "startupTime/javaVersion/groupId 由 CustomServiceRegistry.register() 注入。"
                + "去 Nacos 控制台 → sca-lab-dev → nacos-lab → 详情 → 确认 metadata 中包含这些字段。");

        return result;
    }
}
