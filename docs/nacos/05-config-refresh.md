---
title: "05-配置中心与动态刷新"
series: "Nacos 协调服务系列"
previous: "04-consistency-push.md"
next: "06-安全灰度与集群.md（待写）"
lab_refs:
  - "nacos-lab/docs/A-02-配置中心/02-spring-config-import详解.md"
  - "nacos-lab/docs/A-03-动态刷新/01-刷新链路详解.md + 02-刷新方式对比.md"
  - "nacos-lab/docs/A-10-Bootstrap废弃/01-*.md"
---

# 05-配置中心与动态刷新

## §0 它干了什么（例子先行）

改一个 Nacos 上的连接池大小，10 个服务在几秒内生效：

```
控制台改配置 → Nacos 落库+更缓存 → gRPC 推给订阅的 10 个客户端
  → 客户端 MD5 比对确认 → 拉新内容 → Spring 刷新 Environment → @RefreshScope Bean 销毁重建
  → 下次请求用新值——没重启任何服务
```

xhs 实景：`spring.config.import: optional:nacos:my-xhs-common.yaml?group=DEFAULT_GROUP&refreshEnabled=true`——共享配置+可刷新。

- **它干了什么**：配置的发布、推送、热生效。
- **输入/输出**：输入=配置变更；输出=各服务内存里换上新值。
- **本篇回答**：import 怎么写？刷新链路长什么样？5s 兜底真的 5s 吗？

## §1 掌握卡（4 条）

1. **Bootstrap 已死，import 当立**：旧两步（bootstrap.yml 加载→application.yml 覆盖）→ 新一步（`spring.config.import` 直接声明源）；SC 2025.0 正式移除 bootstrap。
2. **优先级=声明顺序**：import 列表**后面的覆盖前面的**，也覆盖 application.yml 同名 key——想让谁赢放后面。
3. **刷新链路**：服务端变更事件→gRPC 推→客户端 MD5 **双确认**→拉新内容→`RefreshEvent`→Environment 更新+`@RefreshScope` 销毁重建。
4. **兜底真相**：推送丢失**最坏 3 分钟**不是 5s——5s 轮询只对"推送已到、待确认"的生效；推送彻底丢要等 3min 全量同步（`ALL_SYNC_INTERNAL`）。

## §2 import 写法与生产注意

```yaml
spring:
  config:
    import:
      - optional:nacos:my-xhs-common.yaml?group=DEFAULT_GROUP&refreshEnabled=true  # xhs 实景
      - optional:nacos:shared-datasource.yaml?group=DATASOURCE_GROUP
```

三条注意（A-02/02 原文）：**顺序即优先级**（后覆盖前）；**`optional` 别滥用**（生产应显式处理缺失，别静默忽略）；多个 import 增加启动时间（每个源一次网络请求）。

## §3 刷新链路（2.x 全链）

```
服务端：ConfigPublishRequest → 落库 → ConfigDataChangeEvent → 缓存更新
      → RpcConfigChangeNotifier → ConfigChangeNotifyRequest（gRPC 推）
客户端：收到 → 标记 isConsistentWithServer=false → 唤醒 executeConfigListen
      → 发 ConfigBatchListenRequest → 服务端比对各 dataId 的 MD5（内容指纹，改动一字就不同）→ 返回变更集
      → 变更的拉完整内容 → **双确认**（setContent 后还要下一轮 MD5 确认才标一致）
      → Listener.receiveConfigInfo → NacosContextRefresher → RefreshEvent
      → ContextRefresher：更新 Environment（Spring 的属性总集）+ RefreshScope.refreshAll()（销毁全部 @RefreshScope Bean）
下次请求：Bean 重建 → @Value/@ConfigurationProperties 注入新值
```

## §4 5s 兔底的真相

```
客户端轮询循环：最多等 5s，或被推送唤醒 → executeConfigListen（配置监听执行）
推送丢失时：isConsistentWithServer 仍为 true → 5s 轮询跳过它
          → 直到 3 分钟全量同步（needAllSync=true）才比对补上
```

**一句话**：5s 兔底兔的是"推送到了但没确认完"；推送完全丢失的最坏延迟是 **3 分钟**——面试追问点。

## §5 @RefreshScope vs @ConfigurationProperties

| | @Value+@RefreshScope | @ConfigurationProperties |
|---|---|---|
| 刷新方式 | **销毁重建整个 Bean** | 绑定对象原地更新（不重建） |
| 推荐场景 | 需要 @PostConstruct 重初始化时 | **5 项以上配置**（重建代价大） |

两者都能拿到新值；差别在代价——RefreshScope 重建会重跑构造与初始化。

## §6 边界（一句话翻篇）

- ConfigService SDK/ClientWorker 源码级 → `lab D-02/D-04` 用时查。
- 配置发布/查询/同步服务端细节 → `lab D-11/D-12`。
- 共享配置三维回顾 → 01 §2。

## §7 Self-Test（4 题）

- Q1：import 的优先级规则？optional 滥用有什么坑？
- Q2：刷新链路里"双确认"确认的是什么？（MD5）
- Q3：推送丢了，最坏多久生效？为什么不是 5s？
- Q4：5 个以上配置项选哪个注解？为什么？

## §8 衔接

- 配置线收口；下篇 06 生产专题：命名空间安全、灰度回滚、集群部署。
