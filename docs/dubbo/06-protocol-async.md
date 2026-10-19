# 06 请求如何变成网络字节？——协议 / 编解码 / Netty / 异步 Future

> 定位：Dubbo 系列第 06 篇——**问题驱动**；源码域 D-6（协议、传输与二进制调用）+ D-7（异步请求响应与 Netty）作为覆盖清单。
> lab：`scripts/07-protocol-async.sh` + `tools/wire-proxy.py`（TCP 代理抓字节）；证据：`dubbo-lab/evidence/07-protocol-async.log`。
> 课程对照：《Dubbo源码剖析与实战》22 协议编解码 / 02 异步化实践 / 26 线程池扩展 + 《RPC实战与核心原理》02-05 / 17。

---

## 一、先看实验：三个观测

**① 一次调用在网络上的前 64 字节**（TCP 代理抓包，hex）：

```
[C->P] da bb c2 00 9e e0 cc 3c 7c 87 a7 ac 00 00 00 ef 05 32 2e 30 2e 32
       30 23 63 6f 6d 2e 6d 79 78 68 73 2e 64 75 62 62 6f 2e 6c 61 62 2e
       61 70 69 2e 44 65 6d 6f 53 65 72 76 69 63 65 05 30 2e 30 2e
[P->C] da bb 02 14 9e e0 cc 3c 7c 87 a7 ac 00 00 00 34 94 30 23 68 65 6c
       6c 6f 20 74 69 6d 65 6f 75 74 2d 6c 61 62 20 66 72 6f 6d 20 6c 61
       62 2d 70 72 6f 76 69 64 65 72 48 05 64 75 62 62 6f 05 32 2e
```

**② 异步 vs 同步**（两次 `slow(1000)`）：

```
[lab-consumer] 两次 slow(1000): async=1131ms, sync=2016ms
```

**③ timeout 真相**（consumer 1s 超时，provider 执行 3s）：

```
[lab-consumer] slow(3000) -> FAIL in 1005ms: RpcException
provider 侧: slow 开始 11:00:59.373  →  slow 结束 11:01:02.375（精确 3s 后）
```

这一篇把三段证据都落到源码上：**字节长什么样、异步为什么快、超时到底停了谁**。

---

## 二、朴素答案"对象直接发"死在哪

最直观：把 `RpcInvocation` 对象用 Java 序列化直接写进 Socket。三个死点：

| 死点 | 后果 |
|---|---|
| 对端怎么知道"这是哪个请求的响应" | 并发调用时无法配对 |
| 协议要能演进（版本、序列化、心跳、事件） | 没有自描述的头 |
| 不同序列化/协议要能共存 | 字节流里没有"选择位" |

所以需要**协议**：固定头部（自描述、能定位请求）+ 可插拔的 body 编码（序列化）。

---

## 三、Dubbo 协议：16 字节头 + body

`ExchangeCodec` 的常量（`ExchangeCodec.java:60-69`）：

```java
    protected static final int HEADER_LENGTH = 16;
    ...
    protected static final short MAGIC = (short) 0xdabb;
    protected static final byte MAGIC_HIGH = Bytes.short2bytes(MAGIC)[0];
    protected static final byte MAGIC_LOW = Bytes.short2bytes(MAGIC)[1];
    ...
    protected static final byte FLAG_REQUEST = (byte) 0x80;
    protected static final byte FLAG_TWOWAY = (byte) 0x40;
    protected static final byte FLAG_EVENT = (byte) 0x20;
    protected static final int SERIALIZATION_MASK = 0x1f;
```

头部 16 字节的布局（对照实验字节逐段解读）：

| 字节 | 实验值 | 含义 |
|---|---|---|
| 0-1 | `da bb` | 魔数 `0xdabb`（协议识别） |
| 2 | `c2` | `FLAG_REQUEST(0x80)` + `FLAG_TWOWAY(0x40)` + 序列化 id `2`（hessian2） |
| 3 | `00` | 请求状态（请求恒 0；响应里是 20=OK） |
| 4-11 | `9e e0 cc 3c 7c 87 a7 ac` | **request id**（8 字节，响应原样带回，用于配对） |
| 12-15 | `00 00 00 ef` | body 长度（239 字节） |
| 16+ | `05 32 2e 30 2e 32 …` | body：hessian2 序列化，写入顺序为协议版本 "2.0.2" → 服务名 → 服务版本 "0.0.0" → 方法名 → 参数 → 附件（`DubboCodec.encodeRequestData`） |

响应头（`P->C`）：`da bb 02 14 <同一个 request id> 00 00 00 34 …`——`02` 是序列化 id（没有请求位），`14`=20 表示 OK，**request id 与请求一致**；body 里能看到结果字符串 `hello timeout-lab from lab-provider`。

---

## 四、源码：组包、连接与 pipeline

### 4.1 组包：encodeRequest

`ExchangeCodec.encodeRequest`（`ExchangeCodec.java:257`，节选）：

```java
    protected void encodeRequest(Channel channel, ChannelBuffer buffer, Request req) throws IOException {
        Serialization serialization = getSerialization(channel, req);
        // header.
        byte[] header = new byte[HEADER_LENGTH];
        // set magic number.
        Bytes.short2bytes(MAGIC, header);

        // set request and serialization flag.
        header[2] = (byte) (FLAG_REQUEST | serialization.getContentTypeId());

        if (req.isTwoWay()) {
            header[2] |= FLAG_TWOWAY;
        }
        ...
        // set request id.
        Bytes.long2bytes(req.getId(), header, 4);
        ...
        ObjectOutput out = serialization.serialize(channel.getUrl(), bos);
        ...
        int len = bos.writtenBytes();
        checkPayload(channel, req.getPayload(), len);
        Bytes.int2bytes(len, header, 12);
        ...
```

头部先占位、body 序列化完再回填长度——这正是抓包里 `00 00 00 ef` 的由来。序列化器通过 `getSerialization(channel, req)` 按 URL 参数/请求选择（可插拔）。

### 4.2 连接：一个地址共享一条连接

`DubboProtocol.refer`（`DubboProtocol.java:435`）→ `getClients`（`:452`）→ `getSharedClient`（`:481`，节选）：

```java
    private SharedClientsProvider getSharedClient(URL url, int connectNum) {
        String key = url.getAddress();

        // connectNum must be greater than or equal to 1
        int expectedConnectNum = Math.max(connectNum, 1);
        return referenceClientMap.compute(key, (originKey, originValue) -> {
            if (originValue != null && originValue.increaseCount()) {
                return originValue;
            } else {
                return new SharedClientsProvider(
                        this, originKey, buildReferenceCountExchangeClientList(url, expectedConnectNum));
            }
        });
    }
```

**共享键是"地址"**（`url.getAddress()`），用引用计数管理（`ReferenceCountExchangeClient`）：同一个 provider 地址上的**多个服务共享一条连接**。所以第 04 篇说过"一个物理连接承载多个服务"——也解释了 service key（path/group/version）不一致时"端口通但找不到服务"（provider 侧按 service key 在 `exporterMap` 里定位，`DubboProtocol.java:308-315`）。

### 4.3 传输：Netty pipeline

`NettyClient` 打开连接时装 pipeline（`NettyClient.java:129-137`，节选）：

```java
                NettyCodecAdapter adapter = new NettyCodecAdapter(getCodec(), getUrl(), NettyClient.this);
                ch.pipeline() // .addLast("logging",new LoggingHandler(LogLevel.INFO))//for debug
                        .addLast("decoder", adapter.getDecoder())
                        .addLast("encoder", adapter.getEncoder())
                        .addLast("client-idle-handler", new IdleStateHandler(heartbeatInterval, 0, 0, MILLISECONDS))
                        .addLast("handler", nettyClientHandler);
```

解码/编码/心跳/业务处理四段——协议头在 decoder 里被解析（魔数不匹配会同步扫描找下一个魔数，`ExchangeCodec.java:96-128`），业务 handler 把响应交给 Future。

---

## 五、异步与 Future：request id 是钥匙

### 5.1 超时是怎么解析的

`DefaultFuture` 构造（`DefaultFuture.java:97`）：

```java
        this.timeout = timeout > 0 ? timeout : channel.getUrl().getPositiveParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT);
```

第 02 篇讲的 URL 超时，最终在这里变成"这个 Future 等多久"。

### 5.2 响应回来时：按 id 配对

`DefaultFuture.received`（`DefaultFuture.java:195-225`，节选）：

```java
    public static void received(Channel channel, Response response, boolean timeout) {
        try {
            DefaultFuture future = FUTURES.remove(response.getId());
            if (future != null) {
                Timeout t = future.timeoutCheckTask;
                if (!timeout) {
                    // decrease Time
                    t.cancel();
                }
                future.doReceived(response);
                shutdownExecutorIfNeeded(future);
            } else {
                logger.warn(
                        PROTOCOL_TIMEOUT_SERVER,
                        "",
                        "",
                        "The timeout response finally returned at "
                                ...
                                + ", please check provider side for detailed result.");
            }
        } finally {
            CHANNELS.remove(response.getId());
        }
    }
```

- 发请求时 `FUTURES.put(requestId, future)`，响应到达按 id `remove` 并完成；
- 超时任务（`TimeoutCheckTask`）到点后把 Future 置为超时、**从 FUTURES 移除**；
- 之后响应才到 → `FUTURES.remove` 返回 null → 打出那句著名的告警：**"The timeout response finally returned at … please check provider side"**——这是"超时≠取消"的源码形态。

### 5.3 异步为什么快

`asyncCall`（`RpcContext.java:757`）把调用提交后**立即返回 `CompletableFuture`**，调用线程不被占用；两个 `slow(1000)` 在 provider 侧由线程池并行执行 → 总耗时 ≈1.1s（实验）。同步调用则串行等待 → 2s。**Dubbo 的异步是"客户端不阻塞"，不是"服务端换线程"**；provider 侧的执行由业务线程池决定（第 08 篇）。

---

## 六、实验解读

| 观测 | 结论 |
|---|---|
| `da bb` + `c2` + id + len | 协议头自描述：魔数、方向/序列化、request id、长度 |
| 响应 id 与请求一致、`14`=OK | 并发下按 id 配对；body 是 hessian2 |
| async=1131ms vs sync=2016ms | 异步 = 提交后不阻塞，同连接多请求 id 并发 |
| consumer FAIL 1005ms；provider 3s 后结束 | **timeout 只结束消费方等待，不停止提供方执行**（`received` 的告警分支） |

---

## 七、生产陷阱

1. **timeout ≠ 取消**：超时后 provider 可能仍在执行（本实验）；有副作用的慢调用要配幂等键（第 07 篇）；
2. **一个连接多服务**：service key（path/group/version）不一致 → "端口通但找不到服务"；排查看注册 URL 与引用 URL 的 key；
3. **连接数与共享**：`getSharedClient` 按地址共享 + 引用计数；配置 `connections` 与 `share` 不当会导致连接暴涨（规划 D-6 风险点）；
4. **序列化白名单**：反序列化是信任边界，Dubbo 有 serialize allow/block list（启动日志可见）；不要放开不可信类；
5. **心跳与重连**：`client-idle-handler` 负责心跳；心跳丢失会触发重连，网络抖动期可能观察到偶发失败。

---

## 附录 A：面试速答（附带）

> Dubbo 协议是 16 字节头 + body：头里 0-1 魔数 0xdabb、第 2 字节含请求位/双向位/序列化 id、第 3 字节状态（响应 20=OK）、4-11 是 request id、12-15 是 body 长度；body 默认 hessian2 序列化。request id 是配对钥匙：DefaultFuture 用 FUTURES map 按 id 挂起/完成；超时任务到点移除 Future，之后响应到达会打 "timeout response finally returned" 告警——所以 timeout 只结束消费方等待，不停止 provider 执行。传输走 Netty pipeline（decoder/encoder/idle/handler）；DubboProtocol 按"地址"共享连接并用引用计数管理（一个连接承载多个服务）。异步调用（asyncCall）提交后立即返回 CompletableFuture，不阻塞调用线程，两个 1s 调用并发总耗时 ≈1.1s。

## 附录 B：实验与复现

```bash
cd dubbo-lab
export JAVA_HOME=/env/javaEnv/jdk17
mvn -DskipTests install
bash scripts/07-protocol-async.sh     # 输出见 evidence/07-protocol-async.log
```

| 观测点 | 结果 |
|---|---|
| 协议字节 | `da bb c2 00 <id> 00 00 00 ef …`（请求）；`da bb 02 14 <同一 id> 00 00 00 34 …`（响应） |
| 异步 vs 同步 | async=1131ms / sync=2016ms（两次 slow(1000)） |
| timeout 真相 | consumer FAIL 1005ms；provider `slow 开始` → 3s 后 `slow 结束` |
| 抓包工具 | `tools/wire-proxy.py`（TCP 代理，dump 前 64 字节） |

## 附录 C：源码坐标（Dubbo 3.3.3 + lab）

| 内容 | 坐标 |
|---|---|
| 协议常量（头长/魔数/flag） | `dubbo-remoting/dubbo-remoting-api/.../codec/ExchangeCodec.java:60-69` |
| 响应状态码 / 序列化 id | `.../exchange/Response.java:29`（`OK = 20`）；`dubbo-serialization/dubbo-serialization-api/.../serialize/Constants.java:20`（`HESSIAN2_SERIALIZATION_ID = 2`） |
| body 写入顺序 | `dubbo-rpc/dubbo-rpc-dubbo/.../DubboCodec.java:64`（`DUBBO_VERSION`）、`:269`（`encodeRequestData`） |
| 组包 encodeRequest | `ExchangeCodec.java:257`（长度回填 `:302`）；decode `:96-128` |
| 共享连接/引用计数 | `dubbo-rpc/dubbo-rpc-dubbo/.../DubboProtocol.java:435`（refer）、`:452`（getClients）、`:481`（getSharedClient） |
| service key 定位 | `DubboProtocol.java:308-315` |
| Netty pipeline | `dubbo-remoting/dubbo-remoting-netty4/.../NettyClient.java:129-137` |
| Future 超时/配对 | `dubbo-remoting/dubbo-remoting-api/.../DefaultFuture.java:97`（超时）、`:195-225`（received）、`:310`（TimeoutCheckTask） |
| 异步 API | `dubbo-rpc/dubbo-rpc-api/.../RpcContext.java:757`（asyncCall） |
| lab | `scripts/07-protocol-async.sh`、`tools/wire-proxy.py`；`ConsumerApp -Dlab.async` |
| 课程对照 | 《Dubbo源码剖析与实战》22/02/26；《RPC实战与核心原理》02-05/17 |
