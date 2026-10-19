# OpenFeign 专题 · 10 篇

> 状态：✅ 全系列 10 篇完成（2026-10-08）
> 定位：**声明式 HTTP 客户端**（Spring Cloud OpenFeign 4.1.3）的 **原理 + 使用 + 生产** 课程（规格对齐 `docs/redis`、`docs/loadbalancer`）
> 口径：Spring Cloud OpenFeign **4.1.3**（Cloud 2023.0.3）/ feign-core **13.3** / SCA 2023.0.1.2（Sentinel 集成）/ Boot 3.2.5；对齐 xhs-project
> 素材：[`openfeign-lab`](../../openfeign-lab/HANDOVER.md)（A-01~A-09 / B-02~B-05 / D1~D7 / Z——**做引用与证据，不重复写**）· 网关课程（L37 重试熔断、LB 系列 04/07）· xhs-project（拦截器 / 31 个降级工厂 / `NEVER_RETRY`）

---

## 一、系列总表（10 篇）

| # | 文章 | 类型 | 核心内容 | 素材 / 证据 |
|---|---|---|---|---|
| 01 | [声明式调用全景](01-what-is-feign.md) | 理论 | HTTP 客户端演进；Feign 四组件；与网关/LB/注册中心的关系 | A-09（RestClient 对照）；xhs 真实接口 |
| 02 | [声明式背后的机制](02-mechanics-registrar-and-proxy.md) | 理论 | 注册（Registrar）→ 构建（FactoryBean）→ JDK 代理 → MethodHandler；Contract；**Feign 子上下文** | lab D1/D2、B-02/B-03 |
| 03 | [基础使用与配置](03-usage-and-config.md) | 使用 | 注解/参数绑定；配置优先级三层；日志级别；`configuration` 用法 | lab A-01、D7 |
| 04 | [拦截器与请求改造](04-interceptors.md) | 使用 | `RequestInterceptor`：认证头 / traceId / **内部令牌** / 灰度标签 | lab A-02；xhs 两个拦截器 |
| 05 | [HTTP 客户端与超时](05-http-client-and-timeout.md) | 使用 | 默认 vs OkHttp/HttpClient5；连接池；**超时分层**；压缩与序列化 | lab A-03/04/05 |
| 06 | [容错：降级与熔断](06-fallback-and-circuitbreaker.md) | 使用 | `Fallback`/`FallbackFactory`；Sentinel 集成；xhs 31 个降级工厂 | lab A-06、D6 |
| 07 | [LB 与重试](07-lb-and-retry.md) | 使用 | `FeignBlockingLoadBalancerClient`（承接 LB 课）；重试语义 + `NEVER_RETRY` 立场 | lab D3、A-07；LB 系列 04/07 |
| 08 | [进阶特性](08-advanced.md) | 进阶 | 多版本 API；`ErrorDecoder`；压缩；RestClient 对比 | lab A-08/09、B-04/B-05 |
| 09 | [源码主干](09-source-mainline.md) | 进阶 | 注册→构建→调用→装配 四条链（能画出来） | lab D1-D7 |
| 10 | [生产与收口](10-production-and-wrapup.md) | 生产 | xhs 用法全景、排障、面试收口（Z 七组） | Z、xhs 生产 |

---

## 二、学习路径

```text
理论：01 → 02
使用：03 → 04 → 05 → 06 → 07
进阶：08 → 09
生产：10
```

- 只想"会用"：03~07；
- 想"讲得清"：01/02 + 09；
- 面试冲刺：10 收口 + `openfeign-lab/docs/Z-面试题速答.md`。

---

## 三、掌握分级（摘要）

- 🔴 **必掌握**：声明式思想与四组件；代理创建链（Registrar→FactoryBean→代理）；配置优先级；拦截器（认证/trace/内部令牌）；超时分层；降级工厂；LB 包装与 `NEVER_RETRY` 立场；
- 🟡 **了解（用到回查）**：多版本 API；`ErrorDecoder` 细节；压缩；RestClient 对比；
- ⚪ **翻篇（源码深挖）**：feign-core 内部（`ReflectiveFeign`/`SynchronousMethodHandler` 逐行）→ lab D 类。

---

## 四、边界（与相邻专题）

- **不重复**：LB 细节（`docs/loadbalancer` 04/07 已讲阻塞路径）；Sentinel 机制（L45 / `sentinel-lab`）；网关（`docs/gateway`）；
- **交叉引用**：链路追踪（traceId 透传在 Feign 拦截器里）→ `tracing-lab`；服务侧契约/内部令牌 → xhs `FeignInternalCallInterceptor`。

---

## 五、实验与证据

- `openfeign-lab`：A/B 类代码 + **28/28 测试**（4.1.3 重校），D 类引文审计 2032/2012；
- 本系列以**素材引用**为主；需要现场实测时用 lab 或 xhs-project 代码对照，不搭新环境。
