# P1-01 — 网络专项：TIME_WAIT 与连接治理

> **定位**：P1 基础设施第 1 篇（19-TIME_WAIT）。五段式：**原理 → 项目配置 → 治理 → 拷打 → 话术**。
> **依据**：xhs-project 连接配置（Tomcat/Lettuce/Hikari/Feign HC5/网关池）+ 19-TIME_WAIT + 48-Feign。
> **诚实说明**：本项目**无 TIME_WAIT 事故**——本文讲原理 + 配置口径 + 治理方法，不编造故障案例。

---

## 一、原理层

**TIME_WAIT 是什么**：TCP **主动关闭方**的正常状态，等待 **2MSL**（Linux 约 60s），两个目的：
1. 保证最后一个 ACK 能重传（对端没收到 FIN 会重传，我还能响应）；
2. 让本次连接的四元组在网络中彻底消散（防旧连接延迟包被**新连接**误收）。

**什么时候危险**：客户端海量**短连接** → 每次都要一个临时端口（`ip_local_port_range` 默认约 32768–60999，**约 2.8 万个**）→ 耗尽报 `Cannot assign requested address`。**TIME_WAIT 本身不是 bug**，端口耗尽才是。

**易混概念**：
| 状态 | 含义 | 说明 |
|---|---|---|
| TIME_WAIT | 主动关闭方等待（谁先 FIN 谁进） | 正常；多了看客户端侧短连接 |
| CLOSE_WAIT | 被动方收到 FIN 却没 close | **应用 bug**（漏关连接），和 TIME_WAIT 两码事 |
| `tcp_tw_reuse` | 只对出向连接有效，需时间戳 | 可用但受限；**`tcp_tw_recycle` 已废弃**（NAT 下丢包，Linux 4.12 移除） |
| `SO_REUSEADDR` | 只解决 bind，不解决占用 | 服务端重启能 bind，TIME_WAIT 还在 |

---

## 二、项目配置（连接治理全景）

```
【服务端接入】
Tomcat：keep-alive-timeout=60000 + max-keep-alive-requests=200（单连接复用 200 次再关，长连接为主）
MyXhsTomcatCustomizer：maxThreads=150 / minSpare=15 / maxConn=8192（连接上限 + 周期轮换）

【客户端池化】
Redis Lettuce：max-active/min-idle/max-idle + max-wait（池化复用，不裸连）
Hikari（DB）：固定池（master 20/slave 10，K17）；MQ：长连接
Feign：HC5 连接池（48 章：默认 HttpURLConnection 无池→每请求新建连接=TIME_WAIT 风暴；HC5 池化+可观测）

【网关池（T-020/T-048 实战）】
fixed 池 max-connections=500 + max-idle-time=30s + max-life-time=45s
T-048：max-life-time 必须 < 下游 keep-alive-timeout(60s)——否则复用"已被对端关闭"的连接发 body → PrematureClose(500)
```

**治理优先级**：**连接复用（keep-alive/池）> 缩短 MSL（fin_timeout，谨慎）> tw_reuse（仅出向）**——本项目以复用为主，未依赖内核硬调。

---

## 三、排查与治理

**临时端口排查四板斧**：
```
ss -s                                  # 总览（TIME_WAIT 总量/分布）
ss -tan state time-wait | wc -l        # 数量
ss -tan state time-wait dst :端口      # 对端是否集中（定位热点下游）
cat /proc/sys/net/ipv4/ip_local_port_range  # 可用量（默认 2.8 万）
```

**观测纪律**：看**池指标**（active/idle/pending）比看 TIME_WAIT 更直接——连接泄漏（异常路径没还池）伪装成"连接不够"；Grafana node 面板有 TIME_WAIT 指标。

---

## 四、拷打追问

1. **"只有主动关闭方进 TIME_WAIT 吗？"** 是（谁先 FIN 谁进）；"同时关闭"双方都进。
2. **"CLOSE_WAIT 多说明什么？"** 被动方没 close——代码漏关，应用 bug。
3. **"tw_reuse 能随便开吗？"** 仅出向有效 + 依赖时间戳；NAT 谨慎；服务端用 SO_REUSEADDR 解决 bind。
4. **"长连接会打满服务端吗？"** 会——max-connections + max-keep-alive-requests 轮换（本项目 8192/200）。
5. **"怎么证明是端口耗尽不是 CPU 瓶颈？"** 报错（Cannot assign）+ 客户端 TIME_WAIT 计数 + port_range 消耗 + QPS×连接时长估算。
6. **"网关 500 连接池怎么定的？"** 下游并发 × 平均耗时 + T-048 生命期约束（<下游 60s 保活）——池大小与生命期都要算。

---

## 五、话术

> "TIME_WAIT 是主动关闭方的正常状态，等 2MSL 保证最后 ACK 和旧包消散，不是越大越坏。真正危险的是客户端海量短连接把 2.8 万临时端口打满。治理优先级是连接复用——我们 Tomcat keep-alive 60 秒 200 次、Redis/DB/MQ 全走池化，Feign 用 HC5 池；网关 fixed 池 500 还踩过生命期坑：复用已被下游关闭的连接会 PrematureClose，所以 max-life-time 必须小于下游 60 秒保活。内核参数是最后手段，tw_recycle 已经废弃不用。"

---

## 生产陷阱（网络 6 条）

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **短连接打爆端口** | 无复用 → keep-alive/池化 |
| 2 | **复用过期连接** | 生命期 > 对端保活 → max-life-time < 下游 keep-alive（T-048） |
| 3 | **tw_recycle** | NAT 丢包，已移除——用 tw_reuse（出向）或复用 |
| 4 | **连接泄漏伪装成不够** | 异常路径未还池 → 看池指标 active/pending |
| 5 | **CLOSE_WAIT 堆积** | 代码漏 close → 修应用，不是调内核 |
| 6 | **长连接无上限** | maxConn + 轮换（200 次）防单连接无限占用 |

---

## 面试官评分点

**高级开发级**：2MSL 双目的；TIME_WAIT/CLOSE_WAIT 区分；端口耗尽量化（2.8 万）。
**架构师加分**：治理优先级（复用>调参）；池指标优先于 TIME_WAIT；T-048 生命期约束；主动关闭方视角；"无事故讲口径"的诚实。
**危险信号**：说 TIME_WAIT 是 bug；推荐 tw_recycle；只会调内核参数；CLOSE_WAIT/TIME_WAIT 不分。

---

## 本项目真实证据

- Tomcat：`keep-alive-timeout: 60000` / `max-keep-alive-requests: 200`（content yml:15-16）；`MyXhsTomcatCustomizer`（maxThreads/minSpare/maxConn/acceptCount）
- Lettuce 池（application.yml）；Hikari 固定池（K17）；Feign HC5（48 章 + K07）
- 网关：fixed 500 + max-idle 30s + max-life-time 45s（T-020/T-048 注释）；Grafana node TIME_WAIT 指标

## 真实性说明

原理为标准知识（RFC 793/1122 + Linux 文档）；配置为代码事实；**无项目事故**（19 章同口径，不编造案例）。
