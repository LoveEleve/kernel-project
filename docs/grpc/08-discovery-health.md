# 08 服务发现与生产服务：NameResolver/LB + Health/Reflection

> 定位：gRPC 系列第 08 篇（🟡 压缩篇）——覆盖 🟡 G-6（NameResolver + LoadBalancer）+ 🟡 G-9（Health/Reflection/Channelz），G-10（InProcess）一句带过。
> lab：`scripts/08-discovery-health.sh`；证据：`grpc-lab/evidence/08-discovery-health.log`。
> 课程对照：《RPC实战与核心原理》08 服务发现 / 11 负载均衡 / 09 健康检测 + 06 gRPC 源码实战。

---

## 一、先看实验：三个观测

**① NameResolver + LB**（自定义 `lab://` resolver 解析出 A=50051 / B=50052）：

```
pick_first（默认）：
[lab-client] call#1 -> OK: hello lab from grpc-lab-A
[lab-client] call#2 -> OK: hello lab from grpc-lab-A
[lab-client] call#3 -> OK: hello lab from grpc-lab-A
[lab-client] call#4 -> OK: hello lab from grpc-lab-A
round_robin：
[lab-client] call#1 -> OK: hello lab from grpc-lab-B
[lab-client] call#2 -> OK: hello lab from grpc-lab-A
[lab-client] call#3 -> OK: hello lab from grpc-lab-B
[lab-client] call#4 -> OK: hello lab from grpc-lab-A
```

**② Health**（服务端启动 7s 后把 `myxhs.lab.Greeter` 翻成 NOT_SERVING）：

```
[lab-client] health = SERVING
[lab-client] health(4s 后) = NOT_SERVING
server 侧：
[lab-server] health 状态切换为 NOT_SERVING
```

**③ Reflection**（客户端不依赖任何 Stub）：

```
[lab-client] reflection service = myxhs.lab.Echo
[lab-client] reflection service = myxhs.lab.Greeter
[lab-client] reflection service = myxhs.lab.Streamer
[lab-client] reflection service = grpc.reflection.v1.ServerReflection
```

三个观测回答：**地址从哪来（NameResolver）、请求发给谁（LB）、上线后怎么探活和自描述（Health/Reflection）**。

---

## 二、朴素答案"服务发现就是 DNS / LB 就是轮询"死在哪

| 误解 | 事实 |
|---|---|
| "服务发现 = DNS" | `NameResolver` 是**抽象接口**，DNS 只是默认实现（dns scheme）；注册中心（Nacos 等）只是换一个 scheme（本实验用自定义 `lab://`） |
| "LB 默认就是轮询" | gRPC 默认是 **pick_first**（`grpc-core` 的 SPI 只注册了它）；`round_robin` 在 **`grpc-util`** 里——依赖树缺它时策略找不到，RPC 以 INTERNAL 失败（不是退化） |
| "连接就是连接" | `Subchannel` 是**逻辑连接**（有状态机），`Transport` 是**物理连接**；一个 channel 可持有多个 subchannel |

---

## 三、NameResolver：目标字符串 → 地址列表

`NameResolverProvider`（`api/src/main/java/io/grpc/NameResolverProvider.java:39`）的四个钩子：`isAvailable()`（`:46`）、`priority()`（`:56`）、`getDefaultScheme()`，以及继承自 `NameResolver.Factory` 的 `newNameResolver(URI, Args)`（`NameResolver.java:169`）。

lab 的自定义实现（`LabNameResolverProvider`）：把 `lab:///127.0.0.1:50051,127.0.0.1:50052` 解析成两个 `EquivalentAddressGroup`，通过 `NameResolver.Listener2.onResult(ResolutionResult)`（`NameResolver.java:289`、`ResolutionResult:768`）回调给 channel：

```java
        return new NameResolver() {
            @Override
            public String getServiceAuthority() {
                return "lab";
            }

            @Override
            public void start(Listener2 listener) {
                listener.onResult(ResolutionResult.newBuilder().setAddresses(addresses).build());
            }
```

**认知**：生产接注册中心时，就是提供一个新的 `NameResolverProvider`（或使用 Nacos 等提供的 scheme），让 `forTarget("xxx://...")` 解析出最新地址列表；地址变化通过**再次 onResult** 通知（动态更新）。

---

## 四、LoadBalancer：默认 pick_first（实验①解读）

**关键事实**：`grpc-core` 的 SPI 文件只注册了 `io.grpc.internal.PickFirstLoadBalancerProvider`（`core/src/main/resources/META-INF/services/io.grpc.LoadBalancerProvider`）；`round_robin` 由 `grpc-util` 提供（`util/src/main/resources/META-INF/services/io.grpc.LoadBalancerProvider` → `SecretRoundRobinLoadBalancerProvider$Provider`，`getPolicyName()` 返回 `"round_robin"`，`SecretRoundRobinLoadBalancerProvider.java:49-50`）。

**策略名找不到时不会静默退化**：`AutoConfiguredLoadBalancerFactory` 查注册表，找不到就构造一个"固定失败"的 Provider——所有 RPC 以 `INTERNAL: Could not find policy 'round_robin'. ...` 失败（`AutoConfiguredLoadBalancerFactory.java:56-63`）。所以配置 `round_robin` 前先确认 `grpc-util` 在依赖树里（本 lab 中它由 `grpc-services` 传递引入，`mvn dependency:tree | grep grpc-util` 可查；深审对照实验见 `evidence/08-grpc-util-transitive.log`）。

- 客户端配置：`ManagedChannelBuilder.defaultLoadBalancingPolicy("round_robin")`；
- `Subchannel` 四态（`api/src/main/java/io/grpc/ConnectivityState.java`）：`CONNECTING`（`:32`）/ `READY`（`:39`）/ `TRANSIENT_FAILURE`（`:50`）/ `IDLE`（`:61`）（另有 `SHUTDOWN`）——LB 的 Picker 只从 `READY` 的子通道里选；
- pick_first 在首选地址连接失败时会顺序尝试下一个地址（failover，`PickFirstLeafLoadBalancer.java:356-365`：`addressIndex.increment()` + `requestConnection()`），round_robin 则均匀轮转。

---

## 五、Health：探活是一种 gRPC 服务（实验②解读）

服务端用 `HealthStatusManager`（`services/src/main/java/io/grpc/services/HealthStatusManager.java`）注册标准健康服务：`getHealthService()`（`:55`）返回可注册的 `BindableService`，`setStatus(service, status)`（`:67`）更新状态；客户端用生成的 `HealthGrpc` 调 `Check`（unary）或 `Watch`（流式订阅）。

实验②：`SERVING` →（服务端 7s 后翻）→ `NOT_SERVING`——这正是**滚动发布摘流**的协议基础：先翻 NOT_SERVING 让探活/网关停止导流，再走优雅关闭（第 03 篇）。

**认知**：Health 不是"心跳"（心跳是传输层 PING，第 07 篇）；它是应用层可编程的服务状态，可精确到 service 粒度。

---

## 六、Reflection：运行时服务目录（实验③解读）

服务端注册 `ProtoReflectionServiceV1.newInstance()`（`services/src/main/java/io/grpc/protobuf/services/ProtoReflectionServiceV1.java:65/:77`）；客户端用 `ServerReflectionGrpc` 发 `list_services` 请求，流式拿回服务列表（实验③）——`grpcurl` 等工具就靠它"无 proto 也能调"。

**认知**：Reflection 让"契约自描述"（与第 04 篇的 Metadata 互补）；生产默认应关闭（暴露接口清单有安全风险），要用就加鉴权。

---

## 七、Channelz / InProcess（一句带过）

- **Channelz**：运行时诊断服务（连接/流/流量统计，`services` 里的 `ChannelzService`）——本 lab 未展开，排障时与 QoS 类工具同定位；
- **InProcess**：进程内传输（`inprocess/src/main/java/io/grpc/inprocess/InProcessTransport.java`），**走真实调用链、只换传输层**——单元测试首选（第 03 篇的"服务端容器"在测试里也一样跑）。

---

## 八、实验解读

| 观测 | 结论 |
|---|---|
| pick_first 全 A | 默认策略是 pick_first（grpc-core SPI 只有它） |
| round_robin 交替（B/A/B/A） | 策略由 `grpc-util` 注册；依赖树缺它时不是退化，而是 INTERNAL 报错 |
| SERVING → NOT_SERVING | Health 是应用层服务；发布摘流的协议基础 |
| Reflection 列出 4 个服务 | 无需 Stub 的动态契约发现（grpcurl 同款） |

---

## 九、生产陷阱

1. **`round_robin` 的提供者在 `grpc-util`**：配置前先 `mvn dependency:tree | grep grpc-util` 确认（本 lab 由 grpc-services 传递引入）；依赖树缺它时 RPC 直接以 `INTERNAL: Could not find policy` 失败（`AutoConfiguredLoadBalancerFactory.java:56-63`），不会静默退化；
2. **NameResolver 的地址更新**：自定义 resolver 要在地址变化时再次 `onResult`；authority 不一致会导致连接失败；
3. **Health 用于摘流而不是"保活"**：先 `setStatus(NOT_SERVING)` → 等探活失败/连接排空 → 再 shutdown（与第 03 篇配合）；`Watch` 是流式订阅，别用轮询 Check 硬扛；
4. **Reflection 默认关**：暴露服务清单/描述符有安全风险，生产开启要鉴权；
5. **Subchannel ≠ Transport**：排障看 channelz/状态机（IDLE→CONNECTING→READY/TRANSIENT_FAILURE），别把"逻辑子通道"当"物理连接"。

---

## 附录 A：面试速答（附带）

> NameResolver 是抽象接口（NameResolverProvider：isAvailable/priority/getDefaultScheme/newNameResolver），DNS 只是默认实现，注册中心换 scheme；解析结果通过 Listener2.onResult(ResolutionResult) 给 channel，地址变化再次 onResult。LB 默认 pick_first（grpc-core SPI 只注册它）；round_robin 在 grpc-util（policyName="round_robin"），依赖树缺它时策略找不到、RPC 以 INTERNAL 失败（AutoConfiguredLoadBalancerFactory:56-63）；Subchannel 是逻辑连接（CONNECTING/READY/TRANSIENT_FAILURE/IDLE），Picker 只选 READY。Health 是应用层 gRPC 服务（HealthStatusManager.setStatus，Check/Watch），用于发布摘流；Reflection（ProtoReflectionServiceV1）让客户端无 proto 发现服务（grpcurl 原理）；InProcess 走真实调用链只换传输，用于单测。

## 附录 B：实验与复现

```bash
cd grpc-lab
export JAVA_HOME=/env/javaEnv/jdk17
mvn -q compile
bash scripts/08-discovery-health.sh   # 输出见 evidence/08-discovery-health.log
```

| 观测点 | 结果 |
|---|---|
| pick_first | 4/4 全 A |
| round_robin | B/A/B/A 交替 |
| Health | SERVING → NOT_SERVING（7s 翻转） |
| Reflection | 列出 Echo/Greeter/Streamer/ServerReflection |

## 附录 C：源码坐标（gRPC-Java 1.83.1 + lab）

| 内容 | 坐标 |
|---|---|
| NameResolver SPI | `api/src/main/java/io/grpc/NameResolverProvider.java:39/:46/:56`；`NameResolver.java:169`（newNameResolver）、`:289`（Listener2.onResult）、`:768`（ResolutionResult） |
| LB 策略注册 | `core/src/main/resources/META-INF/services/io.grpc.LoadBalancerProvider`（仅 pick_first）；`util/src/main/resources/META-INF/services/io.grpc.LoadBalancerProvider`（SecretRoundRobin 等）；`SecretRoundRobinLoadBalancerProvider.java:49-50`（policyName） |
| LB 策略解析/换址 | `AutoConfiguredLoadBalancerFactory.java:56-63`（策略缺失 → INTERNAL）；`PickFirstLoadBalancerProvider.java:65`（默认返回 PickFirstLeafLoadBalancer）；`PickFirstLeafLoadBalancer.java:356-365`（失败换址） |
| Subchannel 状态 | `api/src/main/java/io/grpc/ConnectivityState.java:32/:39/:50/:61` |
| Health | `services/src/main/java/io/grpc/services/HealthStatusManager.java:55/:67/:87`；生成物 `io.grpc.health.v1.HealthGrpc` |
| Reflection | `services/src/main/java/io/grpc/protobuf/services/ProtoReflectionServiceV1.java:65/:77`；生成物 `io.grpc.reflection.v1.ServerReflectionGrpc` |
| InProcess | `inprocess/src/main/java/io/grpc/inprocess/InProcessTransport.java` |
| lab | `scripts/08-discovery-health.sh`；`LabNameResolverProvider.java`；`ClientApp -Dlab.resolver/-Dlab.lb/-Dlab.discovery/-Dlab.health/-Dlab.reflection`；`ServerApp -Dlab.health/-Dlab.health.flip.ms/-Dlab.reflection/-Dlab.provider.tag` |
