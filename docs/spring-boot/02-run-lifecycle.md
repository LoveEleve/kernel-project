---
title: "02-原理：run全流程分步"
series: "Spring Boot 深化系列"
previous: "01-springapplication.md"
next: "03-ConfigurationProperties绑定.md（待写）"
source_refs:
  - "boot-3.2.5 SpringApplication.run():317-350 体内逐步已核：getRunListeners/starting/prepareEnvironment/printBanner/createApplicationContext/prepareContext/refreshContext:452/callRunners/ready/handleRunFailure"
---

# 02-原理：run全流程分步

## §0 它干了什么（例子先行）

run() 的完整剧本（体内逐步，锚点已核）：

```
1  configureHeadlessProperty + createBootstrapContext     ← 无头模式/引导上下文
2  getRunListeners → listeners.starting()                 ← 第 1 波事件（早于环境）
3  prepareEnvironment                                     ← yml/环境变量就位（内核 07 的主场）
   → listeners.environmentPrepared                        ← 第 2 波（环境好了）
4  printBanner → createApplicationContext                 ← Banner + 建上下文壳
5  prepareContext（填 BeanDefinition/跑 ApplicationInitializer）← 图纸入场
6  refreshContext → refresh()（★内核 05 十三步）           ← 真正造 Bean
7  listeners.started + callRunners（CommandLineRunner…）    ← 第 3 波 + 开机自检
8  listeners.ready                                        ← 第 4 波——完全就绪
失败任意步 → handleRunFailure → listeners.failed → FailureAnalyzer 排版
```

- **它干了什么**：**四波事件 + 一个 ★**——★ 是内核，前后全是 Boot 的开场白与收尾。
- **本篇回答**：每波事件能挂什么？Runner 和事件谁先谁后？自定义启动逻辑有几条路？

## §1 概念铺垫（What：四波事件 + 两条扩展路）

| 波/路 | 时机 | 挂什么 |
|---|---|---|
| `starting` | 最早（环境都没有） | 极早期日志/监控埋点 |
| `environmentPrepared` | 环境就位 | 动态改 PropertySource（启动级） |
| `started`/`ready` | refresh 后/Runner 后 | 健康探测开始接、上游注册（**ready=对外宣称能服务**） |
| **ApplicationRunner/CommandLineRunner** | started 后、ready 前 | **开机自检**（预热缓存/校验配置——出错会拖住 ready） |
| ApplicationInitializer | prepareContext 阶段 | 改上下文（少用——与 refresh 重叠，优先事件/Runner） |

**选型口诀**：**改配置找 environmentPrepared，跑逻辑找 Runner，分阶段观测挂 started/ready**。

## §2 工程使用

```java
@Bean CommandLineRunner warmup() {                       // 最常用：Runner
    return args -> cacheService.preload();               // 启动完跑一次，异常=启动失败
}

@Component
public class ReadyProbe implements ApplicationListener<ApplicationReadyEvent> {
    public void onApplicationEvent(ApplicationReadyEvent e) { healthClient.report("up"); }  // ready 后
}
```

| 工程坑 | 正解 |
|---|---|
| Runner 里做重网络调用卡启动 | 超时意识——Runner 阻塞 ready；重活考虑异步/重试 |
| 在 starting/早波改 yml 能读到的配置 | 迟了——environmentPrepared 之后读的才是全集 |
| ready 之前就接上游流量 | K8s 探针默认等 ready——**别在 started 就暴露**（语义：started=能跑，ready=能服务） |

## §3 掌握卡（2 条）

1. **四波事件序**：starting → environmentPrepared → started → ready（+失败 failed）——与内核 07 的生命周期事件（Refreshed/Ready）对齐：**ContextRefreshed 发生在 ★6 内部，Ready 是 run 的第 8 拍**。
2. **Runner 在 started 与 ready 之间**——所以 Runner 失败=ready 不到=部署探针挡你（这是特性不是 bug：自检失败不接流量）。

## §4 边界（一句话翻篇）

- prepareContext 内部逐行/BootstrapContext → 源码 :317 方法体外层用时查。
- SpringApplicationRunListeners 的合成多播器 → 知道即可。
- 失败分析器家族（NoSuchMethodError 等排版）→ 04 收口提一句。

## §5 Self-Test（3 题）

- Q1：四波事件按顺序；ready 和 started 语义差？
- Q2：Runner 失败会怎样？为什么这算保护？
- Q3：想在"环境就位但容器未建"时改配置，挂哪？（environmentPrepared）

## §6 衔接

- 启动剧本收口；下篇 03 @ConfigurationProperties：yml 是怎么"绑"进一个注解类的（Binder 机制）。
