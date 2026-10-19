# 02 Stub 只是门面：ClientCall 调用链与重试

> 定位：gRPC 系列第 02 篇——覆盖 🔴 G-1（Channel + Stub 调用链）深化 + 重试/对冲。
> lab：`scripts/02-call-chain-retry.sh`；证据：`grpc-lab/evidence/02-call-chain-retry.log`。
> 课程对照：《RPC实战与核心原理》12 异常重试 / 06 gRPC 源码实战。

---

## 一、先看实验：五个观测

**① 拦截器探针打印 `ClientCall` 实际实现类**：

```
无重试（连续三次调用）：
[lab-client] ClientCall class = io.grpc.internal.ManagedChannelImpl$RealChannel$PendingCall
[lab-client] ClientCall class = io.grpc.internal.ClientCallImpl
[lab-client] ClientCall class = io.grpc.internal.ClientCallImpl
启用重试（单次调用；重试包装在 ClientStream 层，不在 ClientCall 层）：
[lab-client] ClientCall class = io.grpc.internal.ClientCallImpl
```

**② 重试**（server 前 2 次返回 UNAVAILABLE；client `maxAttempts=3`）：

```
[lab-client] retry = hello lab from grpc-lab in 460ms
server 侧调用次数 = 3
SayHello #1
SayHello #2
SayHello #3
```

**②' 默认开关验证**（只配 service config、不调用 `enableRetry()`）：

```
[lab-client] enableRetry() called = false
[lab-client] retry = hello lab from grpc-lab in 646ms
server 侧调用次数 = 3
```

**③ 对照**（错误码换成 INTERNAL，不在 `retryableStatusCodes`）：

```
[lab-client] retry -> FAIL in 115ms: StatusRuntimeException | INTERNAL: lab injected failure #1
server 侧调用次数 = 1
```

**④ 对冲**（server 慢 800ms；`hedgingDelay=0.1s`，`maxAttempts=2`）：

```
[lab-client] hedging = hello lab from grpc-lab in 931ms
server 侧调用次数 = 2
SayHello #1
SayHello #2
```

五个观测回答五件事：**调用链有哪些对象、重试在哪一层、什么错误才重试、重试开关到底默认开还是关、对冲为什么会"重复执行"**。

---

## 二、朴素答案"Stub 就是 RPC 客户端"死在哪

第 01 篇已经拆过一个误解：BlockingStub 内部也是异步。这一篇拆第二个：**Stub 不是"客户端"，它只是本地方法外观**——`stub.sayHello()` 之后，真正干活的是三层对象：

| 层 | 对象 | 职责 | 类比 |
|---|---|---|---|
| 外观 | Stub | 把 Java 方法调用翻译成"方法描述符 + 参数" | 前台接待 |
| 调用语义 | `ClientCall` | 一次 RPC 的生命周期（发起/发消息/半关闭/收结果/关闭） | 工单 |
| 传输语义 | `ClientStream` | 字节怎么发（写消息/刷出/流控） | 快递员 |

**重试、对冲都发生在 `ClientStream` 层**（不是 ClientCall 层）——这是实验①要证明的第一件事。

---

## 三、调用链：从 Stub 到 ClientCall

### 3.1 ClientCall 的生命周期

`ClientCall` 接口（`api/src/main/java/io/grpc/ClientCall.java:188-246`，节选）：

```java
  public abstract void start(Listener<RespT> responseListener, Metadata headers);

  public abstract void request(int numMessages);

  public abstract void cancel(@Nullable String message, @Nullable Throwable cause);

  public abstract void halfClose();

  public abstract void sendMessage(ReqT message);
```

一次 unary 调用的顺序：**`start`（注册监听器）→ `sendMessage`（发请求）→ `halfClose`（请求发完）→ 服务端返回 → `onMessage` → `onClose`**。`onClose` 一定最后、且只会调用一次——所有异常/取消最终都汇聚到它。

### 3.2 实验①解读：第一次调用拿到的是 PendingCall

拦截器里 `next.newCall()` 返回的类：

- **首次调用**：`ManagedChannelImpl$RealChannel$PendingCall`——它是 `DelayedClientCall` 的子类（`ManagedChannelImpl.java:983`、`DelayedClientCall.java:50`），含义是**"连接/配置还没就绪，先给你一个延迟调用占位"**（这就是第 01 篇"首调用慢"的另一面）；
- **后续调用**：`io.grpc.internal.ClientCallImpl`（`ClientCallImpl.java:72`）——连接就绪后的真实调用对象。

源码路径：`RealChannel.newCall`（`ManagedChannelImpl.java:864`）→ 就绪后 `newClientCall`（`:1049`）→ `new ClientCallImpl<>(...)`。

### 3.3 启用重试后，ClientCall 还是 ClientCallImpl

实验①的第二组：开了重试，拦截器看到的**仍然是 `ClientCallImpl`**——说明重试不是"包一层 ClientCall"。下一节看它到底在哪。

---

## 四、重试：包在 ClientStream 层

### 4.1 源码：RetriableStream 在哪被创建

`ManagedChannelImpl.ChannelStreamProvider.newStream`（`ManagedChannelImpl.java:461-529`）做了分叉：

- **未开启重试**：`return delayedTransport.newStream(...)`（普通流）；
- **开启重试**：创建内部类 `RetryStream extends RetriableStream`（`:486`），`RetriableStream` 本身实现的是 **`ClientStream`**（`RetriableStream.java:55`：`abstract class RetriableStream<ReqT> implements ClientStream`）。

`RetriableStream` 的构造（`RetriableStream.java:138-148`，节选）持两种策略：

```java
    this.retryPolicy = retryPolicy;
    if (retryPolicy != null) {
      this.nextBackoffIntervalNanos = retryPolicy.initialBackoffNanos;
    }
    this.hedgingPolicy = hedgingPolicy;
    checkArgument(
        retryPolicy == null || hedgingPolicy == null,
        "Should not provide both retryPolicy and hedgingPolicy");
    this.isHedging = hedgingPolicy != null;
```

**重试/对冲互斥**（`checkArgument`）：一次调用要么重试策略、要么对冲策略。

### 4.2 实验②③解读：什么错误才重试

lab 配置的 `retryPolicy`（service config）为：`maxAttempts=3`、`initialBackoff=0.1s`、`backoffMultiplier=2`、`retryableStatusCodes=[UNAVAILABLE]`。

- 实验②：attempt1 `UNAVAILABLE` → 退避 0.1s → attempt2 `UNAVAILABLE` → 退避 0.2s → attempt3 成功；server 三次、client 一次成功（460ms 含退避与抖动；抖动系数 [0.8, 1.2]，`RetriableStream.java:857-862`）；
- 实验③：`INTERNAL` **不在** `retryableStatusCodes` → 立即失败（115ms、server 只 1 次）。

### 4.3 两个必知前提

1. **开关的真相（1.83.1）**：`retryEnabled` **默认就是 `true`**（`ManagedChannelImplBuilder.java:195`）——重试子系统默认开启；`enableRetry()`/`disableRetry()` 只是显式开关（后者的用途是"我自己有重试，别叠加"）。真正决定"会不会重试"的是 **service config 里有没有 `retryPolicy`**：没配策略的方法只有 transparent retry（安全、不重复副作用，`ManagedChannelBuilder.java:519-529` javadoc）。实验 ②' 验证：`enableRetry() called = false` 但重试照样发生（server 3 次）——"默认关闭、必须 enableRetry"的旧说法在 1.83.1 已过时；
2. **`maxAttempts` 含首次**：`3` 表示"最多调用 3 次"（1 次原始 + 2 次重试），不是"重试 3 次"。判定在 `RetriableStream.java:1080`（`retryPolicy.maxAttempts > substream.previousAttemptCount + 1`）；另外 channel 级 `maxRetryAttempts`（默认 5，`ManagedChannelImplBuilder.java:191`）会**钳制**策略值：`maxAttempts = Math.min(maxAttempts, maxAttemptsLimit)`（`ManagedChannelServiceConfig.java:338`）——策略里写 10、通道上限 5，实际最多 5 次。

---

## 五、对冲：并发发、谁快用谁

实验④：server 每次处理 800ms；`hedgingPolicy`（`maxAttempts=2, hedgingDelay=0.1s`）下：

- 第 1 次尝试发出；100ms 后仍无结果 → 第 2 次尝试**并发发出**；
- 谁先返回用谁，另一个取消；
- server 侧观测到 **2 次执行**（`SayHello #1` `#2`）——**同一请求被执行了两遍**。

重试 vs 对冲（面试对照）：

| 维度 | 重试（Retry） | 对冲（Hedging） |
|---|---|---|
| 触发 | 上一次**失败后**（可重试错误码） | 上一次**还没返回**（延迟超阈值） |
| 并发度 | 串行尝试 | 并发尝试 |
| 目的 | 提高成功率 | 降低长尾延迟 |
| 风险 | 重复执行（失败可能已发生副作用） | **必然可能重复执行**（无失败也会多发） |
| 适用 | 幂等写/读 | **只读**（严格幂等） |

---

## 六、实验解读

| 观测 | 结论 |
|---|---|
| 首调用 `PendingCall` → 后续 `ClientCallImpl` | 调用链三层：Stub / ClientCall / ClientStream；首调用等连接就绪 |
| 开重试后 ClientCall 仍是 `ClientCallImpl` | 重试不在 ClientCall 层，在 ClientStream 层（`RetriableStream`） |
| UNAVAILABLE → server 3 次、client 成功 | `retryableStatusCodes` 命中；退避 0.1s→0.2s |
| 不调 enableRetry 仍重试（server 3 次） | `retryEnabled` 默认 true；策略来自 service config |
| INTERNAL → 1 次即失败 | 非重试错误码不重试 |
| 对冲 → server 2 次、client 959ms | 并发重复执行；用延迟换长尾 |

---

## 七、生产陷阱

1. **重试开关的真相**：1.83.1 默认开启（`retryEnabled=true`），真正的开关是 service config 的 `retryPolicy`；别信"必须 enableRetry"的旧说法，也别以为开了就一定会重试；
2. **`maxAttempts` 含首次**：配 3 = 最多 3 次调用；
3. **重试必须幂等**：gRPC 的 commit 语义只保证"服务端未处理时可重试"，业务副作用（扣款/发消息）要幂等键；
4. **对冲必然重复执行**：只用于严格只读；`maxAttempts` 越大，放大的并发越高；
5. **退避要有上限**（`maxBackoff`）+ 抖动（gRPC 自动加），否则故障时形成重试风暴。

---

## 附录 A：面试速答（附带）

> Stub 只是门面；一次调用经历三层：Stub（外观）→ ClientCall（生命周期 start/sendMessage/halfClose/onClose）→ ClientStream（传输）。首调用拿到的是 PendingCall（DelayedClientCall 子类，等连接/配置就绪），之后是 ClientCallImpl。重试/对冲包在 **ClientStream 层**：开启重试后 `ManagedChannelImpl.ChannelStreamProvider.newStream` 创建 `RetryStream extends RetriableStream`（实现 ClientStream），持有 retryPolicy/hedgingPolicy 且两者互斥。重试条件由 `retryableStatusCodes` 决定，退避 initialBackoff×multiplier（抖动 0.8~1.2），`maxAttempts` 含首次且被 channel 级 `maxRetryAttempts`（默认 5）钳制；1.83.1 重试子系统默认开启（retryEnabled=true），真正开关是 service config 的 retryPolicy（无策略只有 transparent retry）。对冲是"并发发、谁快用谁"，会重复执行，只用于只读。

## 附录 B：实验与复现

```bash
cd grpc-lab
export JAVA_HOME=/env/javaEnv/jdk17
mvn -q compile
bash scripts/02-call-chain-retry.sh   # 输出见 evidence/02-call-chain-retry.log
```

| 观测点 | 结果 |
|---|---|
| ClientCall 类 | 首调 `PendingCall` → 后续 `ClientCallImpl`；开重试仍 `ClientCallImpl` |
| 重试（UNAVAILABLE） | server 3 次；client 成功 460ms |
| 默认开关（不调 enableRetry） | server 3 次（默认开启）；client 成功 646ms |
| 对照（INTERNAL） | server 1 次；client FAIL 115ms |
| 对冲 | server 2 次（重复执行）；client 931ms |

## 附录 C：源码坐标（gRPC-Java 1.83.1 + lab）

| 内容 | 坐标 |
|---|---|
| ClientCall 生命周期 | `api/src/main/java/io/grpc/ClientCall.java:188`（start）、`:212`（request）、`:229`（cancel）、`:237`（halfClose）、`:246`（sendMessage） |
| ClientCall 实现 | `core/src/main/java/io/grpc/internal/ClientCallImpl.java:72` |
| PendingCall / DelayedClientCall | `core/src/main/java/io/grpc/internal/ManagedChannelImpl.java:983`；`DelayedClientCall.java:50` |
| newCall 路径 | `ManagedChannelImpl.java:864`（RealChannel.newCall）、`:1049`（newClientCall） |
| RetriableStream | `core/src/main/java/io/grpc/internal/RetriableStream.java:55`（类）、`:138-148`（策略字段/互斥校验）、`:1080`（maxAttempts 判定）、`:857-862`（退避抖动）；创建点 `ManagedChannelImpl.java:461-529`（`RetryStream` `:486`） |
| 策略类型 | `core/src/main/java/io/grpc/internal/RetryPolicy.java`、`HedgingPolicy.java` |
| 开关与默认值 | `api/src/main/java/io/grpc/ManagedChannelBuilder.java:519-529`（enableRetry/transparent retry javadoc）；默认值 `core/src/main/java/io/grpc/internal/ManagedChannelImplBuilder.java:195`（retryEnabled=true）、`:191`（maxRetryAttempts=5） |
| 策略钳制 | `core/src/main/java/io/grpc/internal/ManagedChannelServiceConfig.java:333`（retryPolicy 解析）、`:338`（Math.min 钳制） |
| lab | `scripts/02-call-chain-retry.sh`；`ClientApp -Dlab.retry/-Dlab.hedging/-Dlab.debug.callclass`；`ServerApp -Dlab.fail.first/-Dlab.fail.code/-Dlab.server.slow.ms` |
