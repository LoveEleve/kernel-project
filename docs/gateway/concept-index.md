# Concept Index — 概念索引（全局）

> 用途：**遇到看不懂的概念，先来这里查它属于哪一类**，再决定是继续往下还是回头补。
> 维护：每讲完一课，把该课出现的概念追加进来。

## 三类标记

| 标记 | 类型 | 含义 | 处理方式 |
|---|---|---|---|
| ✅ | 本课已讲 | 在当前课已给出定义 | 正常，应能懂 |
| ⏳ | 前向依赖 | 后面的课会专门讲 | **标记后继续往下**，到那课自然懂 |
| 🔙 | 后向依赖 | 前置基础，本课假设你已会 | **必须回头补**，继续走补不上 |
| 🌐 | 外部依赖 | 属于别的知识域 | **不在 Gateway 里解决**，知道"网关集成了它"即可 |

---

## Lesson 01 · What Is Gateway

### 来自 1.1.1

| 概念 | 类型 | 说明 / 在哪讲 |
|---|---|---|
| API Gateway（架构角色） | ✅ | 1.1.1 |
| 边界组件 / 边缘服务（edge service） | ✅ | 1.1.1 |
| 北向入口 / 南北向流量 | ✅ | 1.1.1 |
| 路由分发（routing） | ✅ | 1.1.1；深入 Lesson 03/04 |
| 横切关注点（cross-cutting concern） | ✅ | 1.1.1 |
| 形态（flavor） | ✅ | 1.1.1 |
| Server / Proxy Exchange | ✅ | 1.1.1 + 1.3（含主流程度对比） |
| Spring WebFlux | ✅ | 已在 **Lesson 00 · 0.6** 补（含 MVC ↔ WebFlux 对照表）；**Reactor 分 4 课**（见 **Lesson 09~12**）；WebFlux 请求管线见 **Lesson 18** |
| Spring MVC | ✅ | 已在 **Lesson 00 · 0.6** 以对照表形式覆盖 |
| Project Reactor | ⏳ | **Lesson 09~12**（契约/算子/背压调度/Context·Sinks） |
| 服务发现（service discovery） | ⏳ | **Lesson 16**（自定义 LB）+ **L45**（Nacos 打通） |
| 灰度 / 版本路由 | ⏳ | **Lesson 16**（Gray/Zone）+ **L43**（SCLB） |
| JWT | 🌐 | 安全知识域；本课只需知道"网关做 JWT 鉴权" |
| HMAC / nonce | 🌐 | 安全知识域 |
| Sentinel | 🌐 | 独立框架（Sentinel 卷） |
| MeterRegistry | 🌐 | 可观测知识域 |
| TraceId | 🌐 | 可观测知识域 |

### 来自 1.1.2

| 概念 | 类型 | 说明 / 在哪讲 |
|---|---|---|
| 线程模型（threading model） | ✅ | 1.1.2 |
| thread-per-request（一请求一线程） | ✅ | 1.1.2 |
| **事件循环（EventLoop）** | ✅ | 已在 **Lesson 00 · 0.4** 补（含 Netty README 原文） |
| **「阻塞」的准确含义** | ✅ | 已在 **Lesson 00 · 0.4.1** 补（Tomcat 官方：请求整个周期占用一个线程） |
| **`Mono` 的底层机制** | ✅ | 已在 **Lesson 00 · 0.4.6** 补（`ChannelFuture` + `addListener`） |
| **非阻塞 IO** | ✅ | 已在 **Lesson 00 · 0.3** 补（含背压） |
| **Reactive Streams** | ✅ | 已在 **Lesson 00 · 0.3.1** 补（4 接口 / 7 方法 / Java 9 `Flow`） |
| **Servlet / Servlet 容器** | ✅ | 已在 **Lesson 00 · 0.2** 补（Jakarta Javadoc + Tomcat 架构） |
| **命令式 vs 响应式** | ✅ | 已在 **Lesson 00 · 0.5** 补（WebFlux《Concurrency Model》） |
| WAR | ✅ | 1.1.2（一句话定义） |
| 响应式编程 | ✅ | 已在 **Lesson 00 · 0.5** 补；类型见 **0.5.1** |
| Mono / Flux | ✅ | 已在 **Lesson 00 · 0.5.1** 补（0..1 / 0..N、惰性、`Mono<Void>`）；**契约/算子见 Lesson 09~10** |
| 自动配置（auto-configuration） | ⏳ | **Lesson 17**（Spring Boot 基础） |
| 属性绑定（Binder） | ⏳ | **Lesson 17 + 20**（装配 + 工厂机制） |

### 来自 1.1.3

| 概念 | 类型 | 说明 / 在哪讲 |
|---|---|---|
| 责任链模式（Chain of Responsibility） | ✅ | 1.1.3（设计模式细节可后补） |
| **AOP / 环绕通知（@Around）** | ✅ | 无需补（用户已掌握；1.1.3 用它对照责任链） |
| 洋葱模型（onion model） | ✅ | 1.1.3 |
| HandlerMapping | ✅ | 1.1.3（一句话）；**源码见 Lesson 17 + 23** |
| 谓词（Predicate） | ⏳ | **Lesson 04** |
| 过滤器链 / GlobalFilter / GatewayFilter | ⏳ | 概念 **L05**；源码装配 **L21**；跑链 **L22** |
| order（过滤器顺序） | ⏳ | 概念 **L05**；查询 **L08**；源码 **L21** |

---

## 🔙 后向依赖汇总（Lesson 01 暴露出来的）

| # | 概念 | 被哪一课假设 | 状态 |
|---|---|---|---|
| 1 | 反向代理（reverse proxy） | 1.1.1 定位 | ✅ 已在 Lesson 00 补（0.1） |
| 2 | Servlet / Servlet 容器 | 1.1.2 | ✅ 已在 Lesson 00 补（0.2） |
| 3 | 同步阻塞 IO vs 非阻塞 IO | 1.1.2 | ✅ 已在 Lesson 00 补（0.3） |
| 4 | 事件循环（EventLoop） | 1.1.2 | ✅ 已在 Lesson 00 补（0.4） |
| 5 | 命令式 vs 响应式 | 1.1.2 | ✅ 已在 Lesson 00 补（0.5） |
| 6 | AOP / 环绕通知 | 1.1.3 | ✅ 无需补（用户已掌握，Lesson 01 1.1.3 用它对照责任链） |

---

## 待办

- [x] ~~Lesson 00: Prerequisites（前置最小包）~~ → 已完成：`L00-prerequisites.md`
