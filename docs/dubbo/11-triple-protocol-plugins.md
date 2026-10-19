# 11 协议扩展与 Triple：多协议共存与治理插件

> 定位：Dubbo 系列第 11 篇（补全篇）——D-6 支撑域（Triple/gRPC/HTTP 协议）+ D-8 治理插件插入点。
> lab：`scripts/12-triple-protocol.sh`；证据：`dubbo-lab/evidence/12-triple-protocol.log`。
> 课程对照：《Dubbo源码剖析与实战》27 协议扩展 / 24 多协议 + 《RPC实战与核心原理》24 多协议兼容 / 06 gRPC。

---

## 一、先看实验：三组观测

**① 同一服务，双协议导出**（provider 配 `dubbo,tri` 两个协议）：

```
Export dubbo service com.myxhs.dubbo.lab.api.DemoService to url dubbo://172.18.0.1:20880/com.myxhs.dubbo.lab.api.DemoService
Export dubbo service com.myxhs.dubbo.lab.api.DemoService to url tri://172.18.0.1:50051/com.myxhs.dubbo.lab.api.DemoService
```

**② consumer 走 `dubbo://`**：

```
[lab-consumer] sayHello = hello timeout-lab from lab-provider
[lab-consumer] slow(100) -> OK in 109ms: slept 100ms
```

**③ consumer 走 `tri://`（经抓包代理）**：

```
[lab-consumer] sayHello = hello timeout-lab from lab-provider
[lab-consumer] slow(100) -> OK in 152ms: slept 100ms
[C->P] 50 52 49 20 2a 20 48 54 54 50 2f 32 2e 30 0d 0a 0d 0a 53 4d 0d 0a 0d 0a 00 00 24 04 00 00 00 00 00 00 01 00 00 10 00 00 02 00 00 00 00 00 03 7f ff ff ff 00 04 00 80 00 00 00 05 00 80 00 00 00
[P->C] 00 00 1e 04 00 00 00 00 00 00 01 00 00 10 00 00 03 7f ff ff ff 00 04 00 80 00 00 00 05 00 80 00 00 00 06 00 00 80 00 00 00 04 08 00 00 00 00 00 00 fe 00 02
```

**同一个接口、同一份实现、同一套消费方代码**——只把 URL 的 scheme 从 `dubbo://` 换成 `tri://`，调用照常。而抓包里的 `50 52 49 20 2a 20 48 54 54 50 2f 32 2e 30` 正是 HTTP/2 的连接前言 **"PRI * HTTP/2.0"**。

---

## 二、朴素答案"换协议 = 重写服务"死在哪

如果协议写死在调用链里，会有三个后果：

| 场景 | 后果 |
|---|---|
| 想升级到 Triple/gRPC（HTTP/2、跨语言） | 服务端和所有调用方一起重写 |
| 想同时支持老客户端（dubbo 协议）和新客户端 | 维护两套服务代码 |
| 网关/异构系统接入 | 必须带 Dubbo SDK，无法用 HTTP 生态 |

所以协议必须是**扩展点**：实现换协议 = 换一个 `Protocol` 扩展 + 换 URL 的 scheme，业务代码零改动。

---

## 三、协议扩展点：Protocol SPI + @Adaptive

第 04 篇讲过：`Protocol` 是 SPI 接口，启动时被六层 wrapper 包住，调用时由 `Protocol$Adaptive` 按 **URL 的 protocol 参数**选实现。Triple 只是 SPI 名单里的又一个实现（`dubbo-rpc-triple/src/main/resources/META-INF/dubbo/internal/org.apache.dubbo.rpc.Protocol`）：

```
tri=org.apache.dubbo.rpc.protocol.tri.TripleProtocol
grpc=org.apache.dubbo.rpc.protocol.tri.GrpcProtocol
rest2=org.apache.dubbo.rpc.protocol.tri.RestProtocol
```

**多协议共存**就是给同一个服务挂多个 `ProtocolConfig`（`DubboBootstrap.protocols(List)`，`DubboBootstrap.java:508`）；每个协议独立端口——dubbo 默认 20880，Triple 默认 **50051**（`TripleProtocol.getDefaultPort`，`TripleProtocol.java:97-98`）。消费方选协议同样只看 URL：直连用 scheme（`dubbo://` / `tri://`），注册中心模式看 `protocol` 参数。

---

## 四、Triple：HTTP/2 上的 RPC

**它是什么**：HTTP/2（多路复用、流控、头压缩）+ 数据编码。编码有两种形态：

- **protobuf 模式**：与 gRPC 服务互通（`grpc=GrpcProtocol` 同族）；跨语言调用的标准形态；
- **非 protobuf 模式**：普通 Java 接口直接跑——本实验就是这种（同一个 `DemoService` 接口，没有任何 protobuf 生成物），实现走反射打包（`ReflectionPackableMethod.java:46`）。

**与第 06 篇的 dubbo 协议对照**（同样是抓包证据）：

| 维度 | dubbo 协议（第 06 篇） | Triple（本篇） |
|---|---|---|
| 开头字节 | `da bb c2 00 <request id> 00 00 00 ef`（16 字节自有头） | `PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n` + SETTINGS 帧 |
| 帧结构 | 魔数 + flag + id + 长度 + body | HTTP/2 帧（SETTINGS/WINDOW_UPDATE/HEADERS/DATA） |
| 请求配对 | 头部 request id | HTTP/2 stream id（协议原生） |
| 跨语言/网关 | 需要 SDK | HTTP/2 语义，gRPC 兼容 |

**升级路径**：同构 Java 服务可以先上 tri 非 protobuf（零改造拿到 HTTP/2 的多路复用与网关友好）；需要跨语言/标准 gRPC 时再切 protobuf——协议换的是 URL，不是业务。

---

## 五、治理插件插在哪（源码口径）

第 08 篇讲过"横切能力都挂在 Filter 上"；metrics/tracing 也一样，只是它们是框架自带的治理插件：

| 插件 | 插入点 | 坐标 |
|---|---|---|
| 指标（provider） | `MetricsProviderFilter`（PROVIDER，order=MIN_VALUE+100） | `MetricsProviderFilter.java:29-32` |
| 指标（consumer/cluster） | `MetricsClusterFilter`（CONSUMER，`onClass=DefaultMetricsCollector`） | `MetricsClusterFilter.java:37-38` |
| 链路追踪 | `DubboServerTracingObservationHandler` 把 traceId 写进响应附件 | `DubboServerTracingObservationHandler.java:44`（第 08 篇） |

两个原则（对应 D-8 的风险点）：

1. **治理插件的异常策略必须与业务失败隔离**——指标/追踪挂了不能把调用打挂；
2. **包装器链上的 URL 可能是配置 URL / 注册 URL / provider URL**——第 10 篇条件路由"匹配最终 invoker URL"的坑就是这条原则的实例。

> 边界诚实：metrics/tracing 的**实跑**需要额外 exporter/bridge（Prometheus/micrometer 等），本 lab 未展开——这里给插入点坐标，不假装跑过。

---

## 六、实验解读

| 观测 | 结论 |
|---|---|
| 同服务导出两条 URL（dubbo/tri） | 多协议 = 多个 ProtocolConfig；协议由 URL 决定 |
| `dubbo://` 与 `tri://` 调用都 OK、业务代码不变 | 换协议 = 换扩展 + 换 scheme，契约（接口 jar）不变 |
| tri 抓包是 HTTP/2 前言 + SETTINGS 帧 | Triple = HTTP/2 上的 RPC；与 dubbo 自有帧协议完全不同 |
| 非 protobuf 模式零生成物可用 | 同构 Java 的平滑升级路径；跨语言再上 protobuf/gRPC |

---

## 七、生产陷阱

1. **多协议要管多套参数**：端口、线程池、序列化白名单每个协议一套；`tri` 的线程池/端口别和 `dubbo` 混用一套配置；
2. **注册中心里同一服务会有多条 URL**（不同 protocol）：目录/路由/LB 看到的是混合列表，消费方按 `protocol` 选择——排障时先确认"走的是哪条"；
3. **tri 非 protobuf ≠ gRPC 互通**：跨语言/标准 gRPC 客户端需要 protobuf 模式；
4. **抓包工具要换**：dubbo 协议看 16 字节头（第 06 篇），Triple 看 HTTP/2 帧类型；别拿旧经验套新协议；
5. **治理插件异常隔离**：metrics/tracing 是插件，异常策略要与业务失败分开设计。

---

## 附录 A：面试速答（附带）

> 协议是 Protocol SPI 扩展点：`Protocol$Adaptive` 按 URL 的 protocol 参数选实现（dubbo/tri/grpc/rest2/injvm…），启动时被 wrapper 链包住。多协议共存 = 多个 ProtocolConfig（`DubboBootstrap.protocols`），dubbo 默认 20880、tri 默认 50051（`TripleProtocol.getDefaultPort`）。Triple = HTTP/2 上的 RPC：protobuf 模式与 gRPC 互通，非 protobuf 模式普通 Java 接口直接跑（反射打包 `ReflectionPackableMethod`）。抓包对照：dubbo 是 `da bb` 16 字节头 + request id 配对；tri 是 HTTP/2 前言 + SETTINGS/stream id。治理插件：MetricsProviderFilter/MetricsClusterFilter（DefaultMetricsCollector）、tracing handler 写 RpcContext 附件；原则是异常隔离 + URL 语义（配置/注册/provider URL 不同）。

## 附录 B：实验与复现

```bash
cd dubbo-lab
export JAVA_HOME=/env/javaEnv/jdk17
mvn -DskipTests install
bash scripts/12-triple-protocol.sh   # 输出见 evidence/12-triple-protocol.log
# 无需 Nacos；wire-proxy 抓 50051 端口
```

| 观测点 | 结果 |
|---|---|
| 双协议导出 | `dubbo://172.18.0.1:20880/...` + `tri://172.18.0.1:50051/...` |
| dubbo:// 调用 | sayHello OK；slow(100) 109ms |
| tri:// 调用 | sayHello OK；slow(100) 152ms |
| tri 抓包 | `50 52 49 20 2a 20 48 54 54 50 2f 32 2e 30`（"PRI * HTTP/2.0"）+ SETTINGS 帧 |

## 附录 C：源码坐标（Dubbo 3.3.3 + lab）

| 内容 | 坐标 |
|---|---|
| Protocol SPI 名单（tri/grpc/rest2） | `dubbo-rpc/dubbo-rpc-triple/src/main/resources/META-INF/dubbo/internal/org.apache.dubbo.rpc.Protocol` |
| 多协议装配 | `dubbo-config/.../bootstrap/DubboBootstrap.java:508`（protocols） |
| Triple 协议 | `dubbo-rpc-triple/.../TripleProtocol.java:59`（类）、`:97-98`（默认端口）、`:102`（export）、`:185`（refer） |
| 非 protobuf 反射打包 | `dubbo-rpc-triple/.../ReflectionPackableMethod.java:46` |
| 指标插件 | `dubbo-metrics/dubbo-metrics-default/.../filter/MetricsProviderFilter.java:29-32`、`MetricsClusterFilter.java:37-38` |
| 追踪插件 | `dubbo-metrics/dubbo-tracing/.../DubboServerTracingObservationHandler.java:44` |
| lab | `scripts/12-triple-protocol.sh`；`ProviderApp -Dlab.protocols/-Dlab.tri.port`；`tools/wire-proxy.py` |
