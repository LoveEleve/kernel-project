# D12: 传输层 — CommandCenter + HeartbeatSender

> 源码位置: `sentinel-transport/sentinel-transport-common/` + `sentinel-transport/sentinel-transport-simple-http/`
> 核心接口: `CommandCenter.java`(45行) / `HeartbeatSender.java`(44行)
> 前置: D8（数据源）理解规则从 Nacos 加载；此处是 Dashboard 直接操作规则的通道

---

## 一、CommandCenter：Dashboard 的操作入口

Sentinel 启动时在 **8719 端口**开启一个内嵌 HTTP Server（`SimpleHttpCommandCenter`），Dashboard 通过 REST API 直接操作客户端的规则和指标：

```
Dashboard → GET  http://client:8719/tree              → 读取调用树
Dashboard → POST http://client:8719/setRules           → 修改流控规则
Dashboard → GET  http://client:8719/getRules?type=flow → 查询当前规则
```

**CommandCenter 接口**：

```java
public interface CommandCenter {
    void beforeStart() throws Exception;  // 准备工作（注册 CommandHandler）
    void start() throws Exception;        // 启动 HTTP Server
    void stop() throws Exception;         // 关闭
}
```

**CommandHandler 注册机制**：每个 REST API 端点对应一个 `CommandHandler` 实现。Dashboard 的请求 URL 中 `commandName` 参数对应 `CommandHandlerProvider` 注册的 handler 名——SPI 加载 + 反射匹配。

---

## 二、HeartbeatSender：客户端心跳上报

HeartbeatSender 定时（默认 10 秒）向 Dashboard 发送心跳包：

```java
public interface HeartbeatSender {
    boolean sendHeartbeat() throws Exception;  // 返回 false 时 Dashboard 标记实例为离线
}

// 心跳包内容
{
    "app": "sentinel-lab",          // spring.application.name
    "ip": "21.214.97.212",          // 实例 IP
    "port": 28011,                  // 应用端口
    "version": "1.8.6",             // Sentinel 版本
    "hostname": "vm-xxx",           // 主机名
    "timestamp": 1680000000000      // 时间戳
}
```

---

## 三、生产陷阱

1. **8719 端口未放开**：如果防火墙/安全组阻止了 Dashboard → 客户端的 8719 端口，Dashboard 上看到的永远是"离线"。解决：确保 Sentinel 客户端所在网段能接受来自 Dashboard IP 的 8719/TCP 入站流量。

2. **心跳丢失的假死判定**：Dashboard 如果收到心跳超时（默认 30 秒无心跳），会标记实例为"离线"并从规则推送列表中移除。网络抖动可能导致假死——实例实际在运行但被 Dashboard 遗忘。
