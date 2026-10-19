---
title: "08-排障与面试收口"
series: "Tracing 链路追踪系列"
previous: "07-observability-sampling.md"
next: "（本系列完）"
lab_refs:
  - "tracing-lab/docs/Z-面试题速答.md（七组 A~G + 陷阱 15 连）"
---

# 08-排障与面试收口

## §0 它干了什么（例子先行）

本篇零新知识。给你两张清单：

```
线上断链 → §1 排障清单（5 场景，按顺序查）
面试被问 → §2 七组题 + §3 陷阱 15 连（覆盖常见追问）
```

- **输入**：01~07 的全部内容；**输出**：排障直觉 + 面试答案。
- **本篇定位**：收口，不是新课。

## §1 排障清单（5 场景）

| # | 现象 | 查什么 | 出处 |
|---|---|---|---|
| 1 | 下游 traceId 对不上（断链） | 头发出去了吗（拦截器）→ 头格式对吗（四段齐？）→ 下游 consume 全吗 | 02 §3.1/§4.1 |
| 2 | 日志串号（A 的号打在 B 的日志） | scope 关清了吗（withSpan(null)/afterCompletion/finally） | 05 §3 |
| 3 | 异步任务丢号 | 池子包了吗（TaskDecorator/MdcAware/wrap 三选一） | 06 §5、03 §3.1 |
| 4 | 请求照跑但无 span | Bean 在吗（顺序反→NOOP）→ reporter 配了吗 | 04 §2/§6 |
| 5 | SW 号和自研号对不上 | **正常**——correlation 查，不是对 bug | 05 §4、06 §6 |

口诀：**先查传没传，再查清没清，三查包没包，四查装没装，双号对不上不算账。**

## §2 面试七组（回链 01~07）

**A. Tracer/Span**：9 方法四类是？（04 §3）`spanBuilder` 何时用？（04 §3）`isNoop` 还能拿 traceId 吗？（03 §6：能，传播需要）

**B. 上下文**：`newScope` vs `maybeScope`？（03 §3）scope 为什么 LIFO？（03 §3）线程池标准解？（03 §3.1：wrap，捕获与重建两个时刻）

**C. 传播**：traceparent 四段？（02 §3.1）produce/consume 默认及为什么？（02 §4.1）extract 为什么返回 Builder？（02 §5）

**D. Baggage**：三类区别？（03 §4.1）Boot 3.2.5 能配哪个？（03 §4.1：仅 remote）为什么放 Baggage 不放 tag？（03 §4）

**E. 接入**：Bean 装配链 + 顺序反了会怎样？（04 §2：静默 NOOP）3 个 Handler 分工与条件？（04 §5）12 桥接类为什么存在？（04 §4：final 只能包）

**F. 采样/导出**：两种采样器 + 默认 hash 版？（03 §5、07 §3）end() 之后去哪？（04 §6）10% 采样成本？（07 §5：3.6GB/天）

**G. 自研对照**：6 头是？（06 §3）Feign 为什么读 Holder？（06 §5：MQ 触发无 Request）自研缺哪两样？（06 §7：parentId/采样位）A2 故事？（06 §6：热路径反射，静态缓存）

## §3 陷阱 15 连（13 条转述 `lab Z §三` + 2 条自研；Z 另两条 startTimestamp/伪造头见 §4）

| # | 反直觉点 | 正解 |
|---|---|---|
| 1 | `abandon()` 也会上报 | **不上报**（结束即弃） |
| 2 | Noop Span 没有 traceId | 保留 traceId，只丢 span 数据 |
| 3 | `wrap()` 执行时动态取号 | **提交时刻的快照** |
| 4 | `maybeScope` 也压栈 | 相同则 NOOP |
| 5 | `extract()` 返回 Span | 返回 **Builder** |
| 6 | traceparent 错抛异常 | 不抛，丢弃重起（不断业务） |
| 7 | Boot 只收 W3C | consume=**全部** |
| 8 | 3.2.5 有 local/tag-fields | **仅 remote-fields** |
| 9 | inject 在 onStop | 在 **onStart** |
| 10 | Receiver 后于 Sender | Receiver(**1000**) 先行 |
| 11 | 采样在 span 创建时 | 在 **trace 创建时** |
| 12 | 无 Reporter 内存泄漏 | end 即释放，只丢数据 |
| 13 | Baggage 自动跨线程 | 不，需 wrap |
| 14 | 自研有调用树 | 无 parentId，拼不起 |
| 15 | 双号对不上是 bug | 正常，correlation 查 |

xhs 专属 3 坑：O1（userId 声明了但恒空——MDC 漏写）、A2（热路径反射压垮 92/99 线程）、读 Request 不读 Holder（MQ 触发无 Request）。

## §4 超纲预告（指路，不展开）

- `@Observed` 同时出 metrics+tracing（`lab D4 §七`）——metrics 系列再讲。
- exemplar 拿 traceId（`lab D4 §六`）——metrics 对接时查。
- 伪造 baggage/染色头怎么办——网关覆盖（过滤器注释原话：X-Gray-Tag 允许客户端覆盖方便测试，生产可改服务端决策），安全评审时细看。
- `startTimestamp` 回填未来时间后端拒收（`lab Z A4`）——异步埋点时查。

## §5 全系列一页纸

```
01 是什么：trace=树，span=节点；三路线；xhs 三件叠加
02 跨进程：inject 写头，extract 读头；W3C 四段；发 W3C 收全部
03 进程内：ThreadLocal 栈 + LIFO；Baggage 三类；采样定生死；noop 零代价
04 接入：3 配置类顺序；Tracer 9 方法；12 桥接；3 Handler
05 日志：scope 开写关清；%X{:-}；xhs 两套号
06 自研：6 头；存三同步；传四路；缺两样；A2 教训
07 可观测：发现→定位→定因；双采样；成本账；脚本
08 收口：5 场景口诀；七组题；15 陷阱
```

## §6 终检（8 题，过 6 算过关）

1. traceparent 四段 + flags 含义（02）
2. produce/consume 默认及为什么（02）
3. sampled null vs false（03）
4. newScope/maybeScope + wrap 两个时刻（03）
5. 顺序反了会怎样（04）
6. 串号/丢号/无数据各查什么（§1 口诀）
7. 自研 6 头 + 缺两样（06）
8. 双采样开关 + 10% 成本（07）

## §7 衔接

- Tracing 系列 8 篇完。README 总表已全挂链接。
- 下一专题由你定。
