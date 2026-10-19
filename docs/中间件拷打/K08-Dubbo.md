# K08 — 组件深度拷打：Dubbo

> 难度：★★★★★｜频率：★★★★★｜区分度：高
> 关键词：RPC 四件事、微内核+SPI（Adaptive/Wrapper）、调用链（Invoker/Filter/Cluster）、16B 协议、重试放大、优雅下线
> 依据：docs/dubbo 11 篇（design-derivation + dubbo-lab 证据）**Dubbo 3.3.3** + geektime《Dubbo源码剖析与实战》35 篇 + xhs-project RPC 选型评估（A/B 实测）

---

## 问题

RPC 到底解决什么？Dubbo 为什么处处可扩展？一次调用经过哪些层？重试为什么会放大？优雅下线怎么不丢请求？你们为什么（不）用 Dubbo？

---

## 面试可讲版（五段式）

### ① RPC 本质与 Dubbo 全景

**RPC 四件事**（从"本地调用跨进程"的死点推出）：
```
对象引用只是进程内地址 → 必须【序列化】
网络不可靠（超时/半包/断连）→ 必须【协议 + 重试】
服务在哪（写死 IP 死）→ 必须【寻址（注册中心）】
像本地一样调用 → 必须【动态代理】
```

**手写最简 RPC 的 7 个死点**（lab-naive 跑通后逐条列出）：硬编码地址 / 裸协议 / 零扩展 / 无容错 / 无治理 / 不可观测 / 配置混乱——每个死点就是 Dubbo 一个子系统的入口。

**Dubbo 全景**：四角色（Provider/Consumer/Registry/Monitor）+ 十层架构；核心思想 = **微内核 + 插件**（统一 URL 模型 + SPI 装配）。

### ② 微内核 + SPI（源码核心）

**为什么不用 JDK SPI**：`ServiceLoader` 一次性加载全部实现、无名字寻址、无依赖注入、无包装、无自适应——Dubbo 自研 `ExtensionLoader`。

**三个关键机制（lab 实测三现象）**：
| 机制 | 作用 | 实验现象 |
|---|---|---|
| **按名加载** | `protocol=dubbo` 按 key 选实现 | `Filter supported names=[..., trace, ...]` |
| **@Adaptive（自适应）** | 运行时代码生成，从 URL 取参数选实现 | `Protocol$Adaptive`（动态生成类） |
| **Wrapper（包装）** | 自动套装饰器链（AOP 式增强） | `InvokerCountWrapper` 套在 DubboProtocol 外 |

外加 **@Activate**（自动激活：Filter 按 group/order 组装）——加两个 SPI 文件就能"框架自己长眼睛"（不改一行框架代码，两端 Filter 生效）。

### ③ 一次调用链路（源码核心）

```
consumer 方法 → 代理 InvokerInvocationHandler
  → Cluster：Router（条件/标签路由）→ LoadBalance（random/roundrobin/leastactive）
  → Filter 链（ConsumerContextFilter → ...）
  → DubboInvoker → ExchangeCodec（16B 头 + hessian2 body）→ Netty 长连接
─── 网络（request id 配对）───
  → Netty 解码 → 线程池派发 → Filter 链 → 反射调用实现
  ← 响应原路返回 → DefaultFuture 按 request id 完成挂起调用
```

**协议要点（抓包实测）**：魔数 `da bb c2 00`（16 字节头：magic+flag+status+requestId+length）+ hessian2 序列化；**一个连接承载多服务**（service key/引用计数）；timeout 只是 consumer 等待上限——**超时后 provider 可能还在执行**（实验验证）。

**Dubbo 协议 vs Triple**：Triple = HTTP/2（protobuf / 非 protobuf 反射打包），多协议可共存（同服务双协议导出）。

### ④ 容错与治理（lab 实验口径）

**重试放大（最经典的坑，实验数据）**：
```
timeout=1000 + retries=2 + provider slow(2000)：
  consumer 侧 FAIL in 3006ms，provider 侧收到 3 次调用（1+2 重试）
对照 retries=0：FAIL in 1005ms，provider 侧 1 次
→ 非幂等接口重试 = 重复执行（扣款/下单）——retries 必须按业务语义定
业务异常不重试：provider 抛 IllegalStateException → 1 次（failover 只重试网络类失败）
```

**优雅下线（实验数据）**：
```
默认 dubbo.service.shutdown.wait=10000：SIGTERM 后等在途完成（slow(3000) 完整执行，进程 5100ms 退出）
对照 wait=0：进程 427ms 退出，provider 侧 slow 只执行 230ms（被中断）——但 consumer 收到的结果字符串仍说 "slept 3000ms"（结果已不可信）
```

**超时三级覆盖（实验）**：provider 5s / consumer 3s / 方法级 1s——**就近优先**（方法级 > consumer > provider）；"配置了不生效"先查层级。

**标签路由/条件路由/分组**：tag（A/B 灰度）实验 4/4 全落 A；条件路由 `when => then` 匹配 invoker URL；leastactive 的 active 来自 `ActiveLimitFilter`（注意直连不建 RouterChain）。

### ⑤ 拷打追问（面试官视角）

1. **"Dubbo 和 Feign 本质区别？"** Feign=HTTP 客户端（文本协议/JSON/手写接口）；Dubbo=完整 RPC 框架（二进制协议/共享接口强契约/内置容错治理/异步流式）——治理模型不同层级。
2. **"SPI 怎么做到换协议不改代码？"** 按名加载 + @Adaptive 动态生成（URL 参数决定实现）+ Wrapper 增强——微内核只定义扩展点。
3. **"重试几次合适？"** 看幂等性——非幂等 retries=0（或业务幂等键）；重试×超时会叠加放大（timeout 也要算进预算）。
4. **"超时后 provider 停了吗？"** 没停——timeout 是 consumer 放弃等待；provider 线程还在跑（实验验证）——长耗时操作要有取消/幂等意识。
5. **"优雅下线怎么保证不丢请求？"** shutdown.wait（默认 10s）等在途完成 + 从注册中心摘除先于停止；wait=0 会中断在途（实验对比）。
6. **"业务异常为什么不会重试？"** failover 只对网络类异常重试；业务异常直接上抛（否则会重复业务副作用）。
7. **"你们为什么用 Feign 不用 Dubbo？"**（xhs 口径）见下——**做过 A/B 试点，数据说话**。
8. **"Triple 是什么？"** Dubbo 3 的 HTTP/2 协议（兼容 gRPC 语义/流式），与 dubbo 协议可共存——面向云原生/多语言。

### ⑥ 话术（xhs 口径：评估后不引入）

> "我们做过完整的选型评估：Feign（HTTP+JSON）vs Dubbo 3.3 vs gRPC。用双协议开关在购物车→商品的 SKU 读路径试点——结果 RPS +4.9%（1236→1296）、P50 -19%（20.2→16.4ms），P90/P99 持平。诚实结论：这条链路瓶颈在 Redis/DB，RPC 协议开销占比小，收益有限；Dubbo 的价值在高频纯 RPC、异步流式和强契约场景。试点还发现一个关键坑：Dubbo application.name 和 spring.application.name 同名时，Dubbo 实例混进 Spring Cloud 服务列表，Feign 有概率打到二进制端口（Invalid status(-1)）。最终决策：不引入并回滚，数据留档——**知道什么时候不用，比会用更重要**。"

---

## 生产陷阱（Dubbo 7 条）

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **重试放大** | retries×timeout 叠加（实验：1 次变 3 次）——非幂等 retries=0；幂等键 |
| 2 | **超时后 provider 继续执行** | timeout 是消费方语义——长操作配取消/幂等，别假设"超时=停止" |
| 3 | **优雅下线 wait=0** | 在途请求被中断（结果不可信）——保持默认 10s，K8s 配 preStop |
| 4 | **配置不生效** | 三级覆盖（method > consumer > provider）——先查层级再看值 |
| 5 | **应用名冲突（xhs 实测坑）** | Dubbo app name 与 Spring Cloud 同名 → 混入服务列表 → 打到错误端口——独立应用名 + 绑定协议 host |
| 6 | **直连调试发现"路由/leastactive 不生效"** | 直连不建 RouterChain——用注册中心模式验证 |
| 7 | **接口契约漂移（双协议并存）** | 共享接口放 common + 版本管理——灰度期契约对齐 |

---

## 发散追问地图（横向）

- **对比**：Feign（HTTP 客户端）/ gRPC（HTTP/2+Protobuf+IDL）/ Thrift / Spring Cloud 全家桶——协议、契约、生态、迁移成本四维
- **SPI 深入**：ExtensionLoader 加载流程；Compiler 字节码生成；实例注入（SPI 里注入 Spring Bean）
- **协议**：Dubbo 协议 16B 头细节；Triple/HTTP2；序列化安全（白名单）；多协议共存
- **治理**：RpcContext 隐式传参；泛化调用；参数验证/缓存/事件/认证；QoS 运维命令；线程池模型（fixed/cached/eager）
- **集群**：Failover/Failfast/Failsafe/Forking/Broadcast；条件路由/标签路由/分组；一致性哈希
- **生态**：Dubbo 3.x 云原生（Proxyless Mesh）；与 Spring Cloud Alibaba 集成

---

## 面试官评分点

**高级开发级**：RPC 四件事讲得清；SPI 三机制；调用链层级；重试/超时/优雅下线的行为边界；知道自己项目的 RPC 选型与理由。
**架构师加分**：从"最简 RPC 的 7 个死点"推出架构的能力；重试放大的实验级理解（次数×超时预算）；优雅下线 wait 的时序语义；选型用数据说话（A/B + 诚实结论 + 回滚）；"不引入"也是决策。
**危险信号**：非幂等接口开重试还振振有词；以为超时=provider 停止；优雅下线只会 kill；说不出调用链分层；选型只会背"Dubbo 性能好"没有数据。

---

## 本项目真实证据

**xhs-project（评估后不引入，数据留档）**：
- `docs/design/rpc-upgrade.md`（B1/B2/B3）：候选对比表（Feign/Dubbo 3.3/gRPC）；试点双协议开关（`myxhs.rpc.dubbo-enabled`，默认 false 可回滚）；**B3 实测：RPS +4.9%（1236→1296）、P50 -19%（20.2→16.4ms）、P90/P99 持平**；关键坑（application.name 冲突 → `Invalid status(-1)`）；最终回滚
- `docs/test-1/architecture/35-engineering-maturity.md`：Dubbo 3.3.0 BOM 移除（Stage 4）、治理成熟度 63→78；残留 5 个 Dubbo Service 接口文件（Coupon/Notification/Counter/Cart/Analytics——已不用未删）
- 现状：15 个业务服务（选型文档口径）Feign（HTTP/1.1+JSON+HC5 连接池）+ NEVER_RETRY（见 K07）

**本 lab / 系列（Dubbo 3.3.3 + Java 17）**：
- `docs/dubbo` 11 篇（design-derivation：01 手写 RPC 7 死点 → 02 超时三级覆盖 → 03 注册 → 04 SPI → 05 调用链 → 06 协议/异步 → 07 容错/优雅下线 → 08 治理/QoS → 09 收束 → 10 路由/LB 深化 → 11 Triple）
- `dubbo-lab`：lab-naive + api/provider/consumer + 12 个实验脚本 + wire-proxy 抓包 + QoS 工具 + evidence 日志
- 课程对照：《Dubbo源码剖析与实战》35 篇 + 《RPC实战与核心原理》31 篇（查漏补缺）

---

## 版本与来源

Dubbo **3.3.3**（tag `dubbo-3.3.3`，源码语料 `code/spring/dubbo`）/ Java 17；geektime 两书（35+31 篇）；xhs-project RPC 选型评估文档（A/B 实测数据）。

## 真实性说明

Dubbo 机制为 3.3.3 源码+lab 实测结论（重试放大/优雅下线/超时三级/SPI 三现象均有 evidence 日志）；xhs 用法（试点数据/坑/回滚/残留文件）为设计文档与工程成熟度文档事实。
