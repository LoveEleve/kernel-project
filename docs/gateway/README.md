# Gateway 正文索引（`docs/gateway/`）

> 与 [`../gateway-学习清单.md`](../gateway-学习清单.md) 配套使用。
> **命名规则：`Lxx-<slug>.md`，`xx` = 课号** —— 本目录按文件名排序即为课程顺序。

## 一、已写出正文（L00 ~ L30）

| 课号 | 文件 | 内容速览 |
|---|---|---|
| L00 | `L00-prerequisites.md` | 前置最小包（0.1 ~ 0.10） |
| L01 | `L01-what-is-gateway.md` | 网关是什么（官方定位） |
| L02~L04 | ——（动手课，无独立正文） | Run It / 路由四要素 / 谓词 |
| L05 | `L05-filters.md` | 过滤器基础（8 个 + 洋葱 + WebFilter 辨析） |
| L06 | `L06-timeout-and-route-metadata.md` | 超时两层 + 路由 metadata |
| L07 | `L07-cors.md` | CORS 处理位置 / 两种配置 / 预检 |
| L08 | `L08-actuator-routes-ops.md` | Actuator 两视图 / refresh / order 表 |
| L09 | `L09-reactive-streams.md` | Reactive Streams 与 Reactor 模型 |
| L10 | `L10-reactor-operators.md` | 网关代码最小算子集 |
| L11 | `L11-backpressure-and-scheduling.md` | 背压与调度 |
| L12 | `L12-context-sinks-debug.md` | Context / Sinks / 调试 |
| L13 | ——（动手课：`DemoGlobalFilter`；无独立正文） | 请求级数据用 attributes |
| L14 | `L14-custom-filter-factory.md` | 自定义过滤器工厂（三段式） |
| L15 | `L15-custom-predicate-factory.md` | 自定义谓词工厂 |
| L16 | `L16-custom-loadbalancer.md` | 自定义负载均衡（含生产做法） |
| L17 | `L17-autoconfiguration-wiring.md` | 自动装配（四组组件） |
| L18 | `L18-webflux-request-pipeline.md` | 请求管线（8 站 / 三份名单） |
| L19 | `L19-route-definition-sources.md` | 定义来源链（四来源 / Composite） |
| L20 | `L20-factories-yml-to-object.md` | 工厂机制（yml→对象；参考级） |
| L21 | `L21-route-definition-to-route.md` | 定义→Route 编译（定义 vs 运行视图） |
| L22 | `L22-route-cache-two-levels.md` | 两级缓存（定义 vs 运行） |
| L23 | `L23-route-matching.md` | 路由匹配 `lookupRoute` |
| L24 | `L24-predicate-family-source.md` | 谓词家族（会一个就会全部） |
| L25 | `L25-filter-chain-assembly.md` | 过滤器链装配 |
| L26 | `L26-filter-chain-execution.md` | 链执行（洋葱模型） |
| L27 | `L27-forwarding-four-steps.md` | 转发四步（最核心） |
| L28 | `L28-request-body-once.md` | body 只能读一次 |
| L29 | `L29-sticky-session.md` | 粘性会话 |
| L30 | `L30-dynamic-route-refresh.md` | 动态路由刷新（Stage 5 收官） |

## 二、辅助文档（非课程，无课号）

| 文件 | 用途 |
|---|---|
| `architecture-map.md` | 架构全景（面试一页图；Stage 5 产出物） |
| `concept-index.md` | 概念索引（全局） |
| `infra-nacos.md` | 本地 Nacos 环境笔记（Docker） |
| `production-gateway-capabilities.md` | 生产级网关能力全景（面试素材） |

## 三、素材（未来课）

| 文件 | 说明 |
|---|---|
| `L43-microsphere-gateway-extension.md` | L43 素材：microsphere-gateway 端点级路由扩展 |

---

> 变更备注（2026-09-30）：本目录课程正文统一加 `Lxx-` 序号前缀（旧名如 `route-matching.md` → `L23-route-matching.md`），全仓库引用已同步更新。
