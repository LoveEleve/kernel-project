package com.scalab.nacos.configcenter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * 共享配置 — MySQL 数据源（从 shared-datasource.yaml 加载）
 * 通过 spring.config.import: nacos:shared-datasource.yaml?group=DATASOURCE_GROUP 引入
 */
@Component
@RefreshScope
@ConfigurationProperties(prefix = "datasource")
public class SharedDatasourceProperties {

    private String url = "n/a";
    private String username = "n/a";
    private int maxPoolSize = 0;
    private int minIdle = 0;
    private int connectTimeout = 0;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public int getMaxPoolSize() { return maxPoolSize; }
    public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }
    public int getMinIdle() { return minIdle; }
    public void setMinIdle(int minIdle) { this.minIdle = minIdle; }
    public int getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(int connectTimeout) { this.connectTimeout = connectTimeout; }
}
