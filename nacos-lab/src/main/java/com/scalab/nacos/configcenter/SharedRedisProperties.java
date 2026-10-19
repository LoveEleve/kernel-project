package com.scalab.nacos.configcenter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * 共享配置 — Redis（从 shared-redis.yaml 加载）
 */
@Component
@RefreshScope
@ConfigurationProperties(prefix = "redis")
public class SharedRedisProperties {

    private String host = "n/a";
    private int port = 0;
    private int timeout = 0;
    private int maxTotal = 0;

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public int getTimeout() { return timeout; }
    public void setTimeout(int timeout) { this.timeout = timeout; }
    public int getMaxTotal() { return maxTotal; }
    public void setMaxTotal(int maxTotal) { this.maxTotal = maxTotal; }
}
