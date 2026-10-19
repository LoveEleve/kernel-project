# 04 拦截器 + Metadata/Status：认证与错误模型

> 定位：gRPC 系列第 04 篇——覆盖 🔴 G-3（Interceptor 拦截链）+ 🔴 G-5（Metadata + Status）。
> lab：`scripts/04-interceptor-metadata.sh`；证据：`grpc-lab/evidence/04-interceptor-metadata.log`。
> 课程对照：《RPC实战与核心原理》06 gRPC 源码实战 / 19 分布式环境下如何快速定位问题（traceId 注入）。

---

## 一、先看实验：三个观测

**① 拦截器顺序**（客户端 A/B + 服务端 A/B，添加顺序 A→B）：

```
client 侧：
[lab-client] interceptor B interceptCall
[lab-client] interceptor A interceptCall
[lab-client] interceptor A onClose
[lab-client] interceptor B onClose
server 侧：
[lab-server] interceptor B interceptCall
[lab-server] interceptor A interceptCall
[lab-server] interceptor B onHalfClose
[lab-server] interceptor A onHalfClose
```

**② Metadata 透传**（ASCII + binary 请求附件；响应 header/trailer）：

```
server 侧收到：
[lab-server] received metadata x-lab-token=lab-token-123, x-lab-bin-bin=[1, 2, 3]
client 侧读到：
[lab-client] response headers = Metadata(content-type=application/grpc,x-lab-server-header=from-server,grpc-encoding=identity,grpc-accept-encoding=gzip)
[lab-client] response trailers = Metadata(x-lab-server-trailer=trailer-from-server)
```

**③ Status 错误模型**（服务端 INVALID_ARGUMENT + description + trailer）：

```
[lab-client] single -> FAIL in 386ms: StatusRuntimeException | INVALID_ARGUMENT: lab injected failure #1
[lab-client] status = INVALID_ARGUMENT | lab injected failure #1
[lab-client] error trailers = Metadata(content-type=application/grpc,x-lab-error-trailer=injected-1,x-lab-server-trailer=trailer-from-server)
```

三个观测回答：**拦截器怎么编排（顺序为什么反直觉）、metadata 怎么双向传、错误怎么带信息**。

---

## 二、朴素答案"拦截器就是改头 / 像 Spring 拦截器"死在哪

三个误解，逐一拆：

| 误解 | 事实 |
|---|---|
| "拦截器像 Spring AOP，在方法前后加逻辑" | 它是**装饰器**：包装 `ClientCall`/`ServerCall` 与 `Listener`；顺序不由自己控制，由编排器折叠决定 |
| "拦截器只能改 header" | 它可以包装**整个调用**：拦截消息、结果、错误、完成——实验②的"直接调 sendHeaders 会炸"就是证据（后面讲） |
| "顺序按添加顺序执行" | **最后添加的最外层、interceptCall 最先执行**（javadoc 明文），回调方向两侧还不一样 |

---

## 三、拦截器链：装饰器栈（实验①解读）

### 3.1 编排规则（javadoc 明文）

`ClientInterceptors.intercept`（`api/src/main/java/io/grpc/ClientInterceptors.java:78-80`，节选）：

```java
   * Create a new {@link Channel} that will call {@code interceptors} before starting a call on the
   * given channel. The last interceptor will have its {@link ClientInterceptor#interceptCall}
   * called first.
```

`ServerInterceptors.intercept` 同样："The last interceptor will have its `interceptCall` called first"（`:98-99`）。lab 里添加顺序是 A→B，所以 **B 是最外层**——这就是实验①里 `B interceptCall` 在 `A` 前面的原因。

### 3.2 回调方向：客户端反向、服务端同向

| 侧 | interceptCall（请求穿透） | 回调（响应回穿） | 方向关系 |
|---|---|---|---|
| 客户端 | B → A | A → B（onClose） | **反向** |
| 服务端 | B → A | B → A（onHalfClose） | **同向** |

不对称的原因：**客户端拦截器包装"出站调用"（`ClientCall`），服务端拦截器包装"入站监听器"（`ServerCall.Listener`）**——洋葱模型在两侧的穿入方向一致（外→内），但回调在客户端是"内→外"、服务端仍是"外→内"（因为监听器本身就是从外层开始调用的）。

一句话记法：**请求永远先过最外层；响应在客户端反向回穿、在服务端同向传递**。

---

## 四、Metadata：gRPC 的 header/trailer 容器（实验②解读）

### 4.1 不是 HTTP headers：key 有类型规则

`Metadata`（`api/src/main/java/io/grpc/Metadata.java:58`）的 key 分两类：

- **ASCII key**（普通名字）：值必须是 ASCII 字符串，用 `ASCII_STRING_MARSHALLER`（`:100`）；
- **binary key**：**名字必须以 `-bin` 结尾**——这是**强制校验**，不是约定（`Metadata.java:862-868`：`checkArgument(name.endsWith(BINARY_HEADER_SUFFIX), "Binary header is named %s. It must end with %s", ...)`；后缀常量 `:66`）；值放任意字节，用 `BINARY_BYTE_MARSHALLER`（`:79`）。上线时 base64 编码：入站解码在 `GrpcHttp2HeadersUtils.java:124`（`BaseEncoding.base64().decode`），容量估算按 4/3 膨胀（`Utils.java:224-228`）——这就是"二进制安全"的实现。

实验②：`x-lab-token`（ASCII）与 `x-lab-bin-bin`（binary）都被服务端原样读到：

```
[lab-server] received metadata x-lab-token=lab-token-123, x-lab-bin-bin=[1, 2, 3]
```

### 4.2 双向传递：请求 headers / 响应 headers + trailers

| 方向 | 载体 | 客户端写入 | 服务端写入 | 对端读取 |
|---|---|---|---|---|
| C → S | request headers | `interceptCall` 里 merge 到 headers | `interceptCall` 参数 | `headers.get(key)` |
| S → C（头） | response headers | —— | 包装 `ServerCall.sendHeaders` | `Listener.onHeaders`（`ClientCall.java:124`） |
| S → C（尾） | trailers | —— | 包装 `ServerCall.close` | `Listener.onClose` 的 trailers |

### 4.3 一个真实翻车：不能直接调 `sendHeaders`

lab 第一次实现时，服务端拦截器在 `interceptCall` 里直接 `call.sendHeaders(...)`，结果调用炸了（修复前运行日志节选，完整堆栈见 `evidence/04-sendHeaders-conflict.log`）：

```
java.lang.IllegalStateException: sendHeaders has already been called
    at io.grpc.internal.ServerCallImpl.sendHeadersInternal(ServerCallImpl.java:109)
    at io.grpc.stub.ServerCalls$ServerCallStreamObserverImpl.onNext(ServerCalls.java:377)
    ...
```

原因链：unary handler 的 `onNext` 首次发消息时会**自动**补一次 `sendHeaders`（`ServerCalls.java:376-379`）：

```java
      if (!sentHeaders) {
        call.sendHeaders(new Metadata());
        sentHeaders = true;
      }
```

而 `ServerCallImpl.sendHeadersInternal` 有 `checkState(!sendHeadersCalled, "sendHeaders has already been called")`（`ServerCallImpl.java:109`）。

**正确姿势**：包装 `ServerCall` 覆写 `sendHeaders`（合并而非直接发送）：

```java
            ServerCall<ReqT, RespT> wrapped = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                @Override
                public void sendHeaders(Metadata headers) {
                    headers.put(SERVER_HEADER, "from-server");
                    super.sendHeaders(headers);
                }
```

——这正是 G-3 认知风险点说的"拦截器包装整个 Call"，不是"改头工具"。修复后响应 header 与 trailer 都正常到达（实验②）。

---

## 五、Status：错误模型（实验③解读）

### 5.1 Status 是什么

`Status`（`api/src/main/java/io/grpc/Status.java:59`）= **Code + Description + Cause + trailers**，是 RPC 的完整结果语义（不是"错误码"三个字能概括的）。常用 Code：

| Code | 语义 | 生产场景 |
|---|---|---|
| `OK` | 成功 | —— |
| `INVALID_ARGUMENT` | 参数非法 | 校验失败（**不可重试**） |
| `UNAVAILABLE` | 服务不可用 | 宕机/网络（**可重试**，第 02 篇） |
| `DEADLINE_EXCEEDED` | 超时 | 第 06 篇 |
| `UNIMPLEMENTED` | 方法未实现 | 方法名不匹配（第 03 篇） |
| `PERMISSION_DENIED` / `UNAUTHENTICATED` | 鉴权失败 | 认证拦截器 |
| `RESOURCE_EXHAUSTED` | 资源耗尽 | 限流 |

### 5.2 服务端抛、客户端读

- 服务端：`responseObserver.onError(status.withDescription(...).asRuntimeException(trailers))`（`asRuntimeException`，`Status.java:523/:531`）；
- 客户端：`Status.fromThrowable(e)`（`:396`）取 Code/Description，`Status.trailersFromThrowable(e)`（`:416`）取 trailers。

实验③：`INVALID_ARGUMENT | lab injected failure #1` + `x-lab-error-trailer=injected-1` 完整往返。

### 5.3 与 Dubbo 对照

Dubbo 的错误模型是 `RpcException` + `isBiz` 标记（第 07 篇：业务异常不重试）；gRPC 统一成 `Status`：**Code 就是重试/降级策略的判断依据**（第 02 篇的 `retryableStatusCodes` 匹配的就是它）。

---

## 六、实验解读

| 观测 | 结论 |
|---|---|
| `B interceptCall` 先于 `A` | 最后添加的拦截器最外层（javadoc 明文） |
| 客户端回调 A→B、服务端 B→A | 客户端包装 Call（回调反向）、服务端包装 Listener（回调同向） |
| ASCII/binary metadata 双向到达 | `-bin` 后缀 = 二进制安全；响应分 header（sendHeaders）与 trailer（close） |
| 直接 `sendHeaders` → IllegalStateException | 必须包装 ServerCall 覆写（与 handler 自动补头冲突） |
| Status + description + trailer 往返 | Code 驱动重试/降级；trailer 带结构化错误细节 |

---

## 七、生产陷阱

1. **拦截器顺序反直觉**：最后添加的最外层；客户端回调反向、服务端同向——排障时先打印 `interceptCall` 确认实际顺序；
2. **改响应头要覆写 `sendHeaders`**：直接调用会与 handler 的自动补头冲突（本实验翻车现场）；
3. **binary metadata 必须 `-bin` 后缀** + `BINARY_BYTE_MARSHALLER`，普通 key 放二进制会报错；
4. **trailer 在 `onClose` 读**：客户端不要只看 `onHeaders`；错误路径用 `Status.trailersFromThrowable`；
5. **Status Code 是契约**：网关/重试/告警都按 Code 判断——`UNAVAILABLE` 可重试、`INVALID_ARGUMENT` 不可；错误必须带 description 便于定位（课程 19 的 traceId 就是塞进 metadata/trailer 的）。

---

## 附录 A：面试速答（附带）

> 拦截器是装饰器，不是 AOP：ClientInterceptor 包装 ClientCall，ServerInterceptor 包装 ServerCall/Listener。编排由 ClientInterceptors/ServerInterceptors 折叠完成，**最后添加的最外层、interceptCall 最先执行**（javadoc 明文）；回调方向：客户端反向（A→B）、服务端同向（B→A）。Metadata 分 ASCII key 与 binary key（名字必须 `-bin` 结尾，base64 上线）；请求 headers → 服务端；响应 header 经 ServerCall.sendHeaders、trailer 经 close，客户端分别在 onHeaders/onClose 读；改响应头必须包装 ServerCall 覆写 sendHeaders（直接调会与 handler 自动补头冲突：IllegalStateException）。Status = Code + Description + Cause + trailers；服务端 asRuntimeException(trailers) 抛出，客户端 Status.fromThrowable / trailersFromThrowable 读取；Code 是重试/降级的判断依据。

## 附录 B：实验与复现

```bash
cd grpc-lab
export JAVA_HOME=/env/javaEnv/jdk17
mvn -q compile
bash scripts/04-interceptor-metadata.sh   # 输出见 evidence/04-interceptor-metadata.log
```

| 观测点 | 结果 |
|---|---|
| 拦截器顺序 | client: B→A interceptCall、A→B onClose；server: B→A 双向 |
| Metadata | 请求 `x-lab-token`/`x-lab-bin-bin` 到达；响应 header+trailer 到达 |
| Status | `INVALID_ARGUMENT \| lab injected failure #1` + `x-lab-error-trailer=injected-1` |

## 附录 C：源码坐标（gRPC-Java 1.83.1 + lab）

| 内容 | 坐标 |
|---|---|
| 编排顺序 | `api/src/main/java/io/grpc/ClientInterceptors.java:78-92`（"The last interceptor … called first"）；`ServerInterceptors.java:96-108` |
| Metadata | `api/src/main/java/io/grpc/Metadata.java:58`（类）、`:79`（BINARY_BYTE_MARSHALLER）、`:100`（ASCII_STRING_MARSHALLER）、`:682/:703`（Key.of）、`:66`（`-bin` 常量）、`:862-868`（`-bin` 强制校验） |
| base64 上线 | `netty/src/main/java/io/grpc/netty/GrpcHttp2HeadersUtils.java:124`（入站解码）；`Utils.java:224-228`（4/3 膨胀估算） |
| ServerCall 写头/尾 | `api/src/main/java/io/grpc/ServerCall.java:130`（sendHeaders）、`:177`（close） |
| 自动补头冲突 | `core/src/main/java/io/grpc/internal/ServerCallImpl.java:109`（checkState）；`stub/src/main/java/io/grpc/stub/ServerCalls.java:376-379`（首次 onNext 补 sendHeaders） |
| Status | `api/src/main/java/io/grpc/Status.java:59`（类）、`:396`（fromThrowable）、`:416`（trailersFromThrowable）、`:523/:531`（asRuntimeException） |
| lab | `scripts/04-interceptor-metadata.sh`；`ClientApp -Dlab.interceptors/-Dlab.meta`；`ServerApp -Dlab.interceptors/-Dlab.meta/-Dlab.fail.code` |
