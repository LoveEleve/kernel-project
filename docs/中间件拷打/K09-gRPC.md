# K09 — 组件深度拷打：gRPC

> 难度：★★★★★｜频率：★★★★☆｜区分度：高
> 关键词：HTTP/2 + protobuf + 代码生成、三种 Stub、ClientCall 链、retry/hedging、StreamObserver 流控、Context/Deadline、帧映射
> 依据：docs/grpc 9 篇（design-derivation + grpc-lab 证据）**gRPC-Java 1.83.1** + geektime《系统性能调优必知必会》18 章 + xhs-project 选型与 SkyWalking 实证

---

## 问题

为什么还要第三种 RPC？一次 `stub.method()` 长什么样？重试默认开着吗？流控 `request(n)` 是什么？超时和上下文怎么跨进程传？

---

## 面试可讲版（五段式）

### ① 三支柱与协议（为什么是 gRPC）

**从两种 RPC 的死点推出 gRPC**：
| 死点 | Feign（HTTP/1.1+JSON） | Dubbo（自有协议） |
|---|---|---|
| 传输效率 | 头开销大、同连接**队头阻塞** | 高效，但跨语言要重写协议栈 |
| 契约 | HTTP 语义松散 | 接口 jar 是 Java 契约，跨语言难 |
| 生态互通 | 浏览器友好 | 需要 SDK/泛化调用 |

**gRPC 答案 = 三支柱**：**HTTP/2**（多路复用/流控/头压缩）+ **protobuf**（二进制 + schema）+ **代码生成**（.proto → 强类型 Stub）。
**帧实测**（wire 抓包）：`PREFACE → SETTINGS → WINDOW_UPDATE → HEADERS(HPACK, 方法名) → DATA(grpc_msg: compressed+len+body) → PING → GOAWAY`——**消息走 DATA 帧，metadata 走 HEADERS**。

**protobuf 编码（边界题）**：tag = `(field_number << 3) | wire_type`；整数 varint（负数 zigzag）、string/bytes/嵌套 length-delimited——TLV + 变长整数。

### ② 调用链与重试（源码核心）

```
stub.method() → ClientCall → 拦截器链 → ClientCallImpl → ClientStream → Transport(Netty) → 帧
（Blocking/Future/Async 三种 Stub 只是门面——底层同一条链；首次调用 2027ms vs 后续 13ms 是初始化开销）
```

**重试三连坑（高频）**：
1. **`retryEnabled` 在 1.83.1 默认就是 true**——但"开子系统"≠"会重试"：真正开关是 service config 的 `retryPolicy`（没配只有 transparent retry）
2. **`maxAttempts` 含首次**：配 3 = 最多 3 次调用（1 原始 + 2 重试）；被通道级 `maxRetryAttempts`（默认 5）钳制
3. **retry 串行 vs hedging 并发**：hedging 超时未回就发第二份取先回——**只用于严格只读**

**可重试条件**：失败可安全重放 + 状态码在 `retryableStatusCodes` 内——有副作用的调用必须幂等。

### ③ 服务端、拦截器与错误模型

- **多服务分发**：`ServerBuilder.addService` → 按 **method full name**（`包.服务/方法`）路由；未注册 → `UNIMPLEMENTED`
- **拦截器顺序**：**最后 add 的最外层、最先执行**；client 回调方向相反
- **改响应头必须包装 `ServerCall`**（`sendHeaders` 只能调一次——业务已调过，直接改会报错）
- **Metadata**：HTTP/2 headers 键值对；**key 以 `-bin` 结尾自动 base64**（二进制安全）；默认上限 **8KB**（别塞大对象）
- **错误模型**：`Status`（code + description）+ trailers；常用码 OK/INVALID_ARGUMENT/NOT_FOUND/DEADLINE_EXCEEDED/UNAVAILABLE/UNIMPLEMENTED

### ④ 流式、Context 与 Deadline

**四种模式**：unary / server-streaming / client-streaming / bidi——统一 `StreamObserver`（异步）。

**`request(n)` 的两面性（高频陷阱）**：
```
自动流控：5 条消息 409~411ms 全部到达（窗口足够）
手动 request(1)：382ms → 790 → 1190 → 1596 → 1997（每条等 400ms——收一条才要一条）
→ request(n) = 交付已缓冲消息 + 回补流控窗口（既是"要数据"也是"给信用"）
```

**Context**：跨线程必须包装（实验：裸线程 `null`，`Contexts.wrap` 后有值）；服务端拦截器用 `Contexts.interceptCall` 注入。
**Deadline**：`effectiveDeadline = min(CallOptions, Context)`；**出站子调用自动继承**（超时预算随调用链传导）；取消 = `RST_STREAM(CANCEL)`。

### ⑤ 传输与生产（Netty + 生态）

- **HTTP/2 帧映射**：`grpc-netty` 把 gRPC 帧映射到 Netty `codec-http2`（SETTINGS/WINDOW_UPDATE/HEADERS/DATA/RST_STREAM/GOAWAY）；`WriteQueue` 按 128 条分块 flush
- **LB**：默认 **pick_first**；`round_robin` 由 **grpc-util** 注册——依赖树缺它时 RPC 以 **INTERNAL 失败**（不是静默退化，实验复现）
- **Health / Reflection**：Health 是**应用层 gRPC 服务**（≠ 传输层 PING）；Reflection 让客户端**无 proto** 发现服务（grpcurl 调试）
- **优雅下线**：先 Health 翻 `NOT_SERVING` 摘流 → `shutdown + awaitTermination` → 超时 `shutdownNow`

### ⑥ 拷打追问（面试官视角）

1. **"gRPC 和 Dubbo 什么关系？"** 定位不同：Dubbo=治理框架（协议可插拔）；gRPC=协议+框架（跨语言/云原生）；交汇点是 **Dubbo 3 Triple 与 gRPC 互通**。
2. **"为什么选 HTTP/2？"** 多路复用（一条连接多调用、无队头阻塞）+ 二进制帧 + HPACK 头压缩 + 流级/连接级流控。
3. **"重试默认开吗？"** 陷阱题：`retryEnabled` 默认 true，但没配 `retryPolicy` 只有 transparent retry（不可重放的安全重试）；`maxAttempts` 含首次。
4. **"流控怎么工作？"** HTTP/2 窗口 + gRPC `request(n)` 双层；`request(n)` 回补信用——不 request 就停在缓冲里。
5. **"超时怎么传到服务端？"** `grpc-timeout` 头 + Context；出站自动继承 min deadline；服务端可读剩余时间主动放弃。
6. **"拦截器里怎么加响应头？"** 包装 ServerCall 覆写 sendHeaders（只能调一次）。
7. **"怎么调试没有 proto 的服务？"** Reflection + grpcurl；或 Server 端注册 health/reflection 服务。
8. **"你们用 gRPC 吗？"**（xhs 口径）见下。

### ⑦ 话术（xhs 口径）

> "gRPC 是 HTTP/2 + protobuf + 代码生成的三件套：多路复用解决队头阻塞、二进制契约跨语言。我们业务 RPC 没有选它——选型评估里 gRPC 因'多语言非需求'被否（Feign 保留、Dubbo 试点后回滚，见 RPC 升级评估）。但 gRPC 在基础设施里真实存在：SkyWalking OAP 的 agent 上报走 gRPC（11800 端口，容器端口映射实证）。原理上高频考的是：三种 Stub 同一条 ClientCall 链、retryEnabled 默认 true 但真正开关是 retryPolicy、maxAttempts 含首次、request(n) 的两面性、deadline 出站继承。"

---

## 生产陷阱（gRPC 7 条）

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **以为重试没开** | retryEnabled 默认 true——没配 retryPolicy 只有 transparent retry；有副作用调用配前先想幂等 |
| 2 | **maxAttempts 当重试次数** | 含首次（3=总共 3 次）——超时预算按 maxAttempts×单次算 |
| 3 | **拦截器里直接改响应头** | sendHeaders 只能调一次——包装 ServerCall |
| 4 | **metadata 塞大对象** | 默认 8KB 上限——大对象走消息体 |
| 5 | **request(n) 不调用** | 流式消费卡死（缓冲满）——异步消费要显式回补信用 |
| 6 | **round_robin 不生效** | 缺 grpc-util 依赖 → INTERNAL 失败——检查依赖树（实验复现） |
| 7 | **裸线程读 Context 为 null** | Context 不自动跨线程——Contexts.wrap/拦截器注入 |

---

## 发散追问地图（横向）

- **对比**：Feign（HTTP/1.1+JSON）/ Dubbo（治理+Triple）/ Thrift——协议/契约/生态；**Triple 互通**是桥梁
- **HTTP/2 深入**：HPACK 动态表、流优先级、连接级 vs 流级窗口、GOAWAY 优雅关闭
- **protobuf**：编码细节（varint/zigzag/packed）、版本兼容（字段编号）、Any/Oneof
- **生态**：grpc-web（浏览器）、xDS/RLS（服务发现/路由）、grpc-gateway（REST 转接）、拦截器生态（OpenTelemetry）
- **流式场景**：实时推送、大文件分块、双向流（聊天）；背压设计
- **生产**：健康探针（K8s grpc-health-probe）、反射调试（grpcurl）、负载均衡（客户端/代理）

---

## 面试官评分点

**高级开发级**：三支柱讲得清；三种 Stub 同链；重试三个坑（默认 true/maxAttempts 含首次/幂等）；request(n) 两面性；metadata/Status 模型。
**架构师加分**：从 Feign/Dubbo 死点推出 HTTP/2 选择；deadline 预算随调用链传导（出站继承）；流控双层模型（窗口+request）；LB 依赖缺省时 INTERNAL 的设计（不静默）；健康检查分层（应用层 Health vs 传输层 PING）；选型用数据（xhs 评估）。
**危险信号**：以为 gRPC 重试默认关；maxAttempts 当重试次数；拦截器直接改响应头；流式消费不懂背压；把 Health 当 PING 心跳。

---

## 本项目真实证据

**xhs-project（业务不用 gRPC，基础设施在用）**：
- 选型评估：`docs/design/rpc-upgrade.md` 候选对比表——gRPC 结论"**不选（多语言非需求）**"（Feign 保留 / Dubbo 试点后回滚）
- SkyWalking 生态：OAP `gRPCPort=11800`（agent 上报通道）+ REST 12800；容器端口映射 `11800 -> my-xhs-skywalking-oap`（实证）
- DCS 配置通道也用 gRPC（`configuration.grpc`）

**本 lab / 系列（gRPC-Java 1.83.1 + Java 17）**：
- `docs/grpc` 9 篇（01 三 Stub/抓包 → 02 ClientCall/retry → 03 服务端生命周期 → 04 拦截器/Metadata/Status → 05 流式/request(n) → 06 Context/Deadline → 07 HTTP/2 帧 → 08 发现/Health/Reflection → 09 面试收束）
- `grpc-lab`：10 个 evidence 日志（含 sendHeaders 冲突复现、grpc-util 缺失 INTERNAL 复现）
- 课程对照：《系统性能调优必知必会》18（gRPC 编码与流模式）+《RPC实战与核心原理》06/24

---

## 版本与来源

gRPC-Java **1.83.1**（tag `v1.83.1`，源码语料 `code/spring/grpc-java`）+ protobuf-java 3.25.9 / Java 17；geektime 专章；xhs-project 选型文档与 SkyWalking 部署配置。

## 真实性说明

gRPC 机制为 1.83.1 源码+lab 实测结论（帧序列/流控时序/重试配置/依赖缺失均有 evidence）；xhs 用法（选型"不选"结论/SkyWalking 11800）为文档与部署配置事实。

---

## K 系列完成度

```
docs/中间件拷打/
├── K01-RocketMQ ✅  K02-Redis ✅  K03-ES ✅
├── K04-Nacos ✅  K05-Sentinel ✅  K06-Seata ✅
├── K07-Feign-LB ✅  K08-Dubbo ✅  K09-gRPC ✅
```
