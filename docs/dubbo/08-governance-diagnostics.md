# 08 生产治理与诊断——RpcContext / 泛化 / 缓存 / QoS / 线程池

> 定位：Dubbo 系列第 08 篇——**问题驱动**；覆盖 D-8（治理与诊断）+ 支撑域。
> lab：`scripts/09-governance-qos.sh` + `tools/qos-cmd.py`（QoS telnet 客户端）；证据：`dubbo-lab/evidence/09-governance-qos.log`。
> 课程对照：《Dubbo源码剖析与实战》03-10 / 27 + 《RPC实战与核心原理》19。

---

## 一、先看实验：五个观测

**① RpcContext 隐式传参**（请求附件 + 响应附件 + 调用后清理）：

```
[lab-consumer] sayHello = hello timeout-lab from lab-provider
[lab-consumer] 响应附件 = {output=96, lab-provider-ack=ack-tr-123, dubbo=2.0.2}
[lab-consumer] sayHello 后 client attachments = {}
provider 侧: sayHello 收到隐式参数 lab-trace-id=tr-123
```

**② 泛化调用**（不依赖接口类）：

```
[lab-consumer] generic $invoke = hello generic-lab from lab-provider
```

**③ 缓存**（`cache=lru`，同一入参调 5 次）：

```
provider 侧 sayHello(cache-lab) 次数 = 2
provider 侧 sayHello(cache-lab) 次数（累计）= 7
```

（第一行：开了缓存，5 次调用只打 2 次 provider；第二行：对照不开缓存，第二次 5 次全部直达 provider。）

**④ QoS 单机运维命令**：

```
ls      → com.myxhs.dubbo.lab.api.DemoService |nacos-A(Y)/nacos-I(Y)|
count   → sayHello | 2 | 0 | 0 | 1ms | 3ms
offline → OK → consumer: FAIL ... Directory(invokers: 0[], validInvokers: 0[])
online  → OK → consumer: sayHello = hello timeout-lab from lab-provider
```

**⑤ 线程池耗尽**（threads=2，queues=0，5 个并发 `slow(2000)`）：

```
[lab-consumer] concurrent#1 = slept 2000ms
[lab-consumer] concurrent#2 = slept 2000ms
[lab-consumer] concurrent#3 -> FAIL: RemotingException | Server side(172.18.0.1,20880) thread pool is exhausted, detail msg:Thread pool is EXHAUSTED! Thread Name: DubboServerHan
...
provider 侧 EXHAUSTED 次数 = 3
[lab-consumer] 5 个并发 slow(2000) 总耗时 = 2144ms
```

这一篇把五个观测落到源码：**附件往哪走、泛化怎么还原、缓存为什么 5 次只打 2 次、QoS 背后是什么、线程池耗尽怎么查**。

---

## 二、朴素答案"加日志"死在哪

上线后"怎么不出事、出了事怎么查"，最直观的答案是"多加日志"。三个死点：

| 死点 | 后果 |
|---|---|
| 日志是**单机局部**的 | 一次跨进程调用断在哪，靠两边日志拼不出来 |
| 横切逻辑（缓存/校验/认证/事件）散落在业务代码 | 每个接口重复写一遍，改一处漏一处 |
| 出事时没有统一入口 | 线程池满、流量异常只能重启"试试" |

所以治理 = **上下文**（跨进程透传的 RpcContext）+ **扩展**（Filter 体系挂横切能力）+ **运维通道**（QoS/metrics 单机诊断）。下面逐个看。

---

## 三、RpcContext：隐式传参的生命周期

### 3.1 两个方向

- **消费方 → 提供方**：消费方 `RpcContext.getClientAttachment().setAttachment("lab-trace-id", traceId)`；提供方读 `RpcContext.getServerAttachment().getObjectAttachment("lab-trace-id")`（实验① provider 日志收到 `tr-123`）。
- **提供方 → 消费方**：提供方 `RpcContext.getServerContext().setAttachment("lab-provider-ack", "ack-" + traceId)`——`RpcServerContextAttachment.setObjectAttachment` 把它写进 **serverResponseContext**（`RpcServerContextAttachment.java:37`），响应时被合并回传；消费方读 `RpcContext.getClientResponseContext()`（实验① 响应附件里的 `lab-provider-ack=ack-tr-123`）。

### 3.2 生命周期：附件是"调用级"的

消费方过滤器 `ConsumerContextFilter`（在 `dubbo-cluster`）干了三件事：

1. invoke 时把 client attachments 合并进 invocation（`:94`）——注意是 `addObjectAttachments`（**覆盖式**），所以过滤器里改附件对本次调用立即生效；
2. 调用前清掉上一轮的响应上下文（`:118`）；
3. `onResponse` / `onError` 后 `removeContext` → `RpcContext.removeClientAttachment()`（`:123-136`）。

所以**每次调用都要重新设置附件**——实验① 的 `sayHello 后 client attachments = {}` 就是这个清理的结果（第 07 篇标签路由"每次调用都要设"也是同一机制）。

提供方对应的是 `ContextFilter`（`dubbo-rpc-api`，PROVIDER 组）：把 serverResponseContext 的附件合并进响应（`ContextFilter.java:201-216`）；invoke 期间 `clearAfterEachInvoke(false)` 防止响应附件被提前清掉（`:190`、`:193`）。

框架自己也用这套机制：tracing 的 traceId 就是 `RpcContext.getServerContext().setAttachment(...)` 写进响应附件的（`DubboServerTracingObservationHandler.java:44`）。

### 3.3 使用注意

- 附件会**跟着请求序列化到对端**：别塞大对象、别塞敏感信息；
- 异步/回调场景注意上下文恢复（第 06 篇的 Future 模型里，`RpcContext` 是按调用存取/恢复的）；
- 跨版本调用时，附件名与类型要兼容。

---

## 四、泛化调用：没有接口类怎么调

实验②：消费方拿到的 `GenericService` 直接按"接口名 + 方法 + 参数类型"调用（lab 代码）：

```java
            GenericService genericService = genericReference.get();
            System.out.println("[lab-consumer] generic $invoke = " + genericService.$invoke(
                    "sayHello", new String[]{"java.lang.String"}, new Object[]{"generic-lab"}));
```

`$invoke` 的签名在 `GenericService.java:38`（`dubbo-common`）。消费方的 `GenericImplFilter`（`@Activate(CONSUMER, value = generic)`，`GenericImplFilter.java:57`）把这次 `$invoke` **还原成真实的方法调用**（`:74`、`:113`）——所以 provider 侧完全无感，收到的就是普通的 `sayHello(String)`。

适用场景：网关、测试平台、跨语言/无 SDK 调用。代价：没有编译期检查（接口名、参数类型写错运行期才炸）、参数要手工构造、性能略低于直接调用。

---

## 五、横切能力：都挂在 Filter 体系上

| 能力 | 实现 | 开关 / 坐标 |
|---|---|---|
| 缓存 | `CacheFilter`（CONSUMER + PROVIDER） | `cache=lru`（`:67-69`）；实验③ |
| 参数校验 | `ValidationFilter` | `validation=true` + JSR303 注解（`:62-64`、`:88`） |
| 事件通知 | `MethodConfig` 的 `oninvoke` / `onreturn` / `onthrow` | `MethodConfig.java:94-119`（调用前/返回/异常回调） |
| 服务认证 | `TokenFilter`（PROVIDER） | `token=xxx`；不匹配抛 `Invalid token`（`:38`、`:43-48`） |

**缓存为什么 5 次只打 2 次？** 两层原因：

1. `AbstractCacheFactory.getCache` 按"URL + 方法"缓存 Cache 实例（`AbstractCacheFactory.java:36-45`），所以同一方法的缓存跨调用复用；
2. `LruCache` 的底层是 **`LRU2Cache`**（两级 LRU，`LruCache.java:57`）：`put` 第一次只进"历史队列"，**第二次同 key 才真正入缓存**（`LRU2Cache.java:81-93`）。

所以同一入参的调用序列是：第 1 次直达 provider（准入）、第 2 次直达 provider（入缓存）、第 3 次起命中缓存——5 次调用只打 2 次 provider（实验③）。这是**防一次性 key 冲刷缓存**的设计。

---

## 六、QoS：单机运维入口

打开方式：`application.setQosEnable(true)`，默认端口 **22222**（`QosConstants.java:21`）。它是 telnet 协议（`QosProcessHandler` 用 `LineBasedFrameDecoder`，`:93`），所以用 `nc`/python 都能连——lab 里是 `tools/qos-cmd.py`。

实验④ 用到的命令：

| 命令 | 作用 | 实验输出 |
|---|---|---|
| `ls` | 列出服务/方法（Provider/Consumer 两侧） | `DemoService` + `nacos-A(Y)/nacos-I(Y)`（注册状态） |
| `count` | 调用统计（total/failed/active/平均/最大） | `sayHello \| 2 \| 0 \| 0 \| 1ms \| 3ms` |
| `offline` / `online` | 摘流量 / 恢复（改注册中心动态配置） | offline 后消费方 `invokers: 0[]` → FAIL；online 后恢复 OK |
| `help` | 命令清单 | 全部可用命令 |

两个关键源码点：

- `count` 的数据源是 `RpcStatus`（`CountTelnet.java:156-181`）。**provider 侧计数由过滤器维护**：`ExecuteLimitFilter` / `ActiveLimitFilter`（配置了 `executes` / `actives` 才启用）——lab 里给 provider 加了 `executes=200`，`count` 才有数。所以"`count` 全 0"通常不是故障，而是没开计数源。
- `offline` 把 provider URL 从注册中心**注销**（`BaseOffline.doUnexport:106-115`：`registry.unregister(providerUrl)`；`online` 则重新注册，`BaseOnline.java:90`），消费方目录刷新后变空。**变更流程**：先 `offline` 摘流量 → 再优雅下线（第 07 篇）→ 比直接 SIGTERM 更平滑。

---

## 七、线程池耗尽：复现与诊断

### 7.1 复现

provider 配置 `threads=2, queues=0`：`FixedThreadPool` 在 `queues=0` 时用 **`SynchronousQueue`**（`FixedThreadPool.java:48-71`）——线程都在忙时**直接拒绝**，不排队。5 个并发 `slow(2000)` → 2 个成功、3 个失败。

### 7.2 失败长什么样（三层可见）

- provider 日志：`AbortPolicyWithReport` 打出 `Thread pool is EXHAUSTED! Thread Name: ... Pool Size: 2 (active: 2, core: 2, ...)`（`:103`）——**线程池快照直接给了**；
- 服务端回包：状态码 `SERVER_THREADPOOL_EXHAUSTED_ERROR`，消息 `Server side(ip,port) thread pool is exhausted, detail msg:...`（`WrappedChannelHandler.java:88-92`）；
- 消费方：`RemotingException | Server side(172.18.0.1,20880) thread pool is exhausted, detail msg:Thread pool is EXHAUSTED!...`——**错误信息已经把根因带过来了**。

### 7.3 诊断路径与参数

1. 先看消费方错误（根因通常已透出）→ 2. QoS `count`/`ls` 看单机状态 → 3. provider 日志 EXHAUSTED 行的快照（active/core/max/queue）→ 4. 调参：`threads`（固定大小）、`queues`（0=不排队、负数=内存安全无界队列）、`threadpool`（fixed/cached/eager 等）→ 5. 扩展：`ThreadPoolExhaustedListener` SPI（`:22-30`）可以在耗尽时接告警。

设计取舍：CPU 型任务用"固定线程 + 小队列"快速失败；IO 型任务可以更大线程/队列；**队列越大，"假死"越隐蔽**（请求都在排队，日志上看不出）。

---

## 八、实验解读

| 观测 | 结论 |
|---|---|
| 请求/响应附件往返 + 调用后 `{}` | 附件是调用级上下文；`ConsumerContextFilter` 每次调用后清理 |
| `$invoke` 成功 | 泛化调用 = 消费方 `GenericImplFilter` 还原 + 服务端无感 |
| 5 次调用 provider 只打 2 次 | `LRU2Cache` 两级准入：同 key 第二次才入缓存 |
| `count` 有数 / offline→FAIL / online→OK | QoS = 单机运维入口；`RpcStatus` 依赖 `executes` 等过滤器维护 |
| 5 并发 2 成功 3 EXHAUSTED | `threads=2` + `SynchronousQueue` 直接拒绝；错误链路完整可见 |

---

## 九、生产陷阱

1. **附件是调用级、会被清理**：跨调用传递要放业务参数或每次显式重设；异步/线程池场景注意上下文恢复；
2. **泛化调用无编译期检查**：接口名/参数类型写错运行期才炸；网关场景建议叠加参数校验；
3. **缓存语义要对**：`lru` 是"两级准入 + LRU"，前两次不命中；且数据更新后缓存**不会自动失效**——只适合只读数据；
4. **`count` 全 0 先查计数源**：provider 计数依赖 `executes`/`actives` 之一，不是 QoS 坏了；
5. **线程池耗尽先看日志快照**：`threads`/`queues` 决定"排队还是拒绝"；队列越大故障越隐蔽；耗尽事件有 SPI 可接告警。

---

## 附录 A：面试速答（附带）

> RpcContext 隐式传参：消费方 `getClientAttachment()` → 提供方 `getServerAttachment()`；反向是提供方 `getServerContext().setAttachment()` → 消费方 `getClientResponseContext()`。附件是调用级：消费方 `ConsumerContextFilter` 在 onResponse/onError 后 `removeClientAttachment()`，所以每次调用都要设。泛化调用用 `GenericService.$invoke(方法, 参数类型, 参数)`，消费方 `GenericImplFilter` 还原成普通调用。横切能力挂在 Filter 上：cache（CacheFilter，LRU2 两级准入：第二次同 key 才入缓存）、validation（ValidationFilter）、oninvoke/onreturn/onthrow（MethodConfig）、token（TokenFilter）。QoS 默认端口 22222：ls/count/offline/online/help 等；count 数据源是 RpcStatus，provider 侧靠 executes/actives 维护。线程池耗尽：AbortPolicyWithReport 打 `Thread pool is EXHAUSTED!` 快照，服务端回 `SERVER_THREADPOOL_EXHAUSTED_ERROR`，消费方看到 `Server side(...) thread pool is exhausted`；threads/queues 决定排队还是拒绝，ThreadPoolExhaustedListener SPI 可接告警。

## 附录 B：实验与复现

```bash
cd dubbo-lab
export JAVA_HOME=/env/javaEnv/jdk17
mvn -DskipTests install
bash scripts/09-governance-qos.sh   # 输出见 evidence/09-governance-qos.log
# QoS/offline 段依赖 Nacos（docker compose up -d 或复用已有实例）
```

| 观测点 | 结果 |
|---|---|
| 隐式传参 | provider 收到 `lab-trace-id=tr-123`；消费方响应附件 `lab-provider-ack=ack-tr-123`；调用后 client attachments `{}` |
| 泛化调用 | `$invoke = hello generic-lab from lab-provider` |
| 缓存 | 5 次同参调用 → provider 2 次（准入）；对照 5 次 → 累计 7 |
| QoS | `ls`/`count`(sayHello total=2)/`offline`→FAIL/`online`→OK |
| 线程池耗尽 | 2 成功 3 FAIL（`thread pool is exhausted`）；provider EXHAUSTED×3；总耗时 2144ms |

## 附录 C：源码坐标（Dubbo 3.3.3 + lab）

| 内容 | 坐标 |
|---|---|
| 消费方附件生命周期 | `dubbo-cluster/.../filter/support/ConsumerContextFilter.java:94`（合并）、`:118`（清响应上下文）、`:123-136`（响应/异常清理） |
| 提供方响应附件 | `dubbo-rpc/dubbo-rpc-api/.../rpc/RpcServerContextAttachment.java:37`；`.../rpc/filter/ContextFilter.java:201-216`（合并进响应）、`:190/:193`（clearAfterEachInvoke） |
| tracing 同款用法 | `dubbo-metrics/dubbo-tracing/.../DubboServerTracingObservationHandler.java:44` |
| 泛化调用 | `dubbo-common/.../rpc/service/GenericService.java:38`（`$invoke`）；`dubbo-rpc-api/.../filter/GenericImplFilter.java:57`（@Activate）、`:74/:113`（还原调用） |
| 缓存 | `dubbo-plugin/dubbo-filter-cache/.../CacheFilter.java:67-69`、`:95-121`；`.../support/AbstractCacheFactory.java:36-45`；`.../support/lru/LruCache.java:57`；`dubbo-common/.../utils/LRU2Cache.java:81-93`（两级准入） |
| 校验/事件/认证 | `dubbo-plugin/dubbo-filter-validation/.../ValidationFilter.java:62-64/:88`；`dubbo-common/.../config/MethodConfig.java:94-119`；`dubbo-rpc-api/.../filter/TokenFilter.java:38/:43-48` |
| QoS | `dubbo-plugin/dubbo-qos/.../qos/common/QosConstants.java:21`（22222）；`.../server/handler/QosProcessHandler.java:93`；`.../command/impl/CountTelnet.java:156-181`；`.../command/impl/BaseOffline.java:106-115`（unregister）、`BaseOnline.java:90`（register）；lab `tools/qos-cmd.py` |
| 线程池耗尽 | `dubbo-common/.../threadpool/support/fixed/FixedThreadPool.java:48-71`；`.../threadpool/support/AbortPolicyWithReport.java:103`；`dubbo-remoting-api/.../dispatcher/WrappedChannelHandler.java:88-92`；`dubbo-common/.../threadpool/event/ThreadPoolExhaustedListener.java:22-30` |
| lab | `scripts/09-governance-qos.sh`；`ConsumerApp -Dlab.trace.id/-Dlab.cache/-Dlab.fixed.name/-Dlab.concurrent/-Dlab.generic/-Dlab.check`；`ProviderApp -Dlab.qos/-Dlab.threads/-Dlab.queues/-Dlab.executes` |
