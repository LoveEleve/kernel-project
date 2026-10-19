package com.scalab.nacos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * 对比 @ConfigurationProperties（不需 @RefreshScope）vs @Value + @RefreshScope
 *
 * 机制差异：
 *   @ConfigurationProperties：Spring Boot 启动时绑定一次，NacosContextRefresher
 *     触发 ContextRefresher.refresh() 时 Environment 更新 → 属性重新绑定，Bean 不重建。
 *     适合场景：配置项多、结构化的配置类。
 *
 *   @Value + @RefreshScope：每次配置变更后 Bean 被代理重建（proxy 不变，target 重建），
 *     所有注入的属性都重新解析。代价是 Bean 重建开销（对于简单 Bean 可忽略，
 *     复杂依赖 Bean 需注意）。
 *     适合场景：少量独立配置项。
 *
 * 验证：
 *   1. 调 /config/refresh-compare 查看两种方式的当前值
 *   2. 修改 Nacos 配置 → 等 3 秒 → 再调 /config/refresh-compare
 *   3. 两种方式都应该拿到新值
 */
@Component
@RefreshScope
@ConfigurationProperties(prefix = "demo")
public class DemoProperties {

    private String message = "default";
    private String version = "v0.0.0";
    private String welcome = "welcome-from-default";

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getWelcome() {
        return welcome;
    }

    public void setWelcome(String welcome) {
        this.welcome = welcome;
    }
}
