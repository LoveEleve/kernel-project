# 07 服务不可用怎么办？——重试放大 / 业务异常 / 优雅下线 / 标签路由

> 定位：Dubbo 系列第 07 篇——**问题驱动**；源码域 D-5（集群容错与治理）作为覆盖清单。
> lab：`scripts/08-failover-graceful.sh`；证据：`dubbo-lab/evidence/08-failover-graceful.log`。
> 课程对照：《Dubbo源码剖析与实战》23 集群 + 《RPC实战与核心原理》09-15。

---

## 一、先看实验：五个观测

**① 重试放大**（consumer timeout=1000，retries=2，provider `slow(2000)`）：

```
[lab-consumer] slow(2000) -> FAIL in 3006ms: RpcException
provider 侧 slow 开始次数 = 3
```

对照 `retries=0`：`FAIL in 1005ms`，provider 侧只收到 **1** 次。

**② 业务异常不重试**（retries=2，provider 对 `sayHello("biz-error")` 抛 `IllegalStateException`）：

```
[lab-consumer] sayHello(biz-error) -> FAIL: java.lang.IllegalStateException | biz error: biz-error
provider 侧 biz-error 收到次数 = 1
```

**③ 优雅下线（默认 `dubbo.service.shutdown.wait=10000`）**——在 `slow(3000)` 执行中给 provider 发 SIGTERM：

```
[lab-consumer] slow(3000) -> OK in 3011ms: slept 3000ms
provider SIGTERM 到进程退出 = 5100ms
provider 侧: slow 开始 11:41:45.940 → slow 结束 11:41:48.941
```

**④ 对照 `wait=0`**——同样在 `slow(3000)` 执行中 SIGTERM：

```
[lab-consumer] slow(3000) -> OK in 244ms: slept 3000ms
provider SIGTERM 到进程退出 = 427ms
provider 侧: slow 开始 11:41:59.166 → slow 结束 11:41:59.396
```

consumer 拿到了 "OK"，但 provider 侧这次 `slow` 只执行了 **230ms**（被中断）——结果字符串却说 "slept 3000ms"。

**⑤ 标签路由**（A/B 双 provider 各带 `dubbo.tag`）：

```
[lab-consumer] call#1 -> OK: hello loop-1 from lab-provider-A
[lab-consumer] call#2 -> OK: hello loop-2 from lab-provider-A
[lab-consumer] call#3 -> OK: hello loop-3 from lab-provider-A
[lab-consumer] call#4 -> OK: hello loop-4 from lab-provider-A
[lab-consumer] call#1 -> FAIL: RpcException | Failed to invoke the method sayHello in the service com.myxhs.dubbo.lab.api.DemoService. No provider available for the service com.myxhs.dub
```

（上：consumer `tag=A` 时 4/4 全落 A；下：consumer 无标签时 2/2 失败，日志按 140 字符截断。）

这一篇把五个观测落到源码：**重试从哪来、业务异常为什么不重试、SIGTERM 后谁在等谁、wait=0 打断了什么、tag 怎么过滤**。

---

## 二、朴素答案"报错给用户"死在哪

一个 provider 挂了或变慢时，最直观的答案是"把错误抛给用户"。三个死点：

| 死点 | 后果 |
|---|---|
| 变慢的 provider 可能只是"这一次"慢 | 直接报错 = 可用性白白损失 |
| 挂了可以换一台，但换的动作谁来做 | 调用方手写重试 = 业务代码被治理逻辑污染 |
| 盲目重试、盲目等待 | 非幂等操作被重复执行；慢调用拖满线程池 |

所以调用方需要四件事：**重试策略**（换谁、换几次）、**路由**（该去哪个机房/分组/版本）、**限流熔断**（保护自己与下游）、**优雅上下线**（变更窗口不抖动）。本篇讲前三者的主线 + 下线机制；限流熔断给源码口径。

---

## 三、Failover：重试不是免费的

### 3.1 源码：一次调用最多发几次

`FailoverClusterInvoker.doInvoke`（`FailoverClusterInvoker.java:59-127`，节选）：

```java
    public Result doInvoke(Invocation invocation, final List<Invoker<T>> invokers, LoadBalance loadbalance)
            throws RpcException {
        List<Invoker<T>> copyInvokers = invokers;
        String methodName = RpcUtils.getMethodName(invocation);
        int len = calculateInvokeTimes(methodName);
        // retry loop.
        RpcException le = null; // last exception.
        List<Invoker<T>> invoked = new ArrayList<>(copyInvokers.size()); // invoked invokers.
        Set<String> providers = new HashSet<>(len);
        for (int i = 0; i < len; i++) {
            // Reselect before retry to avoid a change of candidate `invokers`.
            // NOTE: if `invokers` changed, then `invoked` also lose accuracy.
            if (i > 0) {
                checkWhetherDestroyed();
                copyInvokers = list(invocation);
                // check again
                checkInvokers(copyInvokers, invocation);
            }
            Invoker<T> invoker = select(loadbalance, invocation, copyInvokers, invoked);
            invoked.add(invoker);
            ...
```

次数从哪来（`FailoverClusterInvoker.java:129-142`）：

```java
    private int calculateInvokeTimes(String methodName) {
        int len = getUrl().getMethodParameter(methodName, RETRIES_KEY, DEFAULT_RETRIES) + 1;
        RpcContext rpcContext = RpcContext.getClientAttachment();
        Object retry = rpcContext.getObjectAttachment(RETRIES_KEY);
        if (retry instanceof Number) {
            len = ((Number) retry).intValue() + 1;
            rpcContext.removeAttachment(RETRIES_KEY);
        }
        if (len <= 0) {
            len = 1;
        }

        return len;
    }
```

`retries` 默认 **2**（`CommonConstants.java:417`），所以默认最多发 **3** 次；第 2 次起会**重新拉目录并重选** provider（`i > 0` 分支），且 `select` 会避开已经试过的（`invoked`）。

### 3.2 实验：1 次用户请求 = 3 次 provider 执行

| 配置 | consumer 结果 | provider 侧 `slow 开始` |
|---|---|---|
| timeout=1000, retries=2 | FAIL in **3006ms** | **3** 次 |
| timeout=1000, retries=0 | FAIL in **1005ms** | **1** 次 |

时间线：每次尝试都在 1s 超时 → 3 × 1000ms ≈ 3006ms；而 provider 的 `slow(2000)` **三次都在执行**（每次超时只是消费方不再等，见第 06 篇"timeout ≠ 取消"）。用户的一次点击，变成了下游的三次执行——这就是**重试放大**。

### 3.3 重试预算

- 用户最长等待 ≈ `(retries+1) × 单次超时`：实验里 3×1s=3s，必须小于上游（网关/浏览器）的超时；
- 只有**幂等**操作才能安全重试；写操作要么 `retries=0`，要么用幂等键把"重试"变成"可重复的同一操作"。

---

## 四、业务异常为什么不重试

实验 ②：retries=2，provider 抛 `IllegalStateException`，provider 只收到 **1** 次——业务失败没有被重试。源码里有两道防线：

**防线一：业务异常走"正常响应"回来**。提供方的业务异常被放进 `AppResponse`，消费方直到代理层 `recreate()` 才把异常抛给业务代码（`AppResponse.java:79-104`，节选）：

```java
    public Object recreate() throws Throwable {
        if (exception != null) {
            ...
            throw exception;
        }
        return result;
    }
```

也就是说：业务异常到达消费方时，是一个**带异常的响应**（传输层面成功）——`DefaultFuture.doReceived` 对 `Response.OK` 直接 `complete(res.getResult())`（`DefaultFuture.java:253-263`），Future 正常完成、异常只是 Result 的内容；只有超时/序列化/其他状态才 `completeExceptionally`。所以 `FailoverClusterInvoker` 的重试循环根本看不到它——它的 `catch (RpcException e)` 只接传输/超时类异常。实验里 consumer 打印的是原始 `java.lang.IllegalStateException`，正是这条路径（`InvocationUtil.java:64` 的 `.recreate()`）。

**防线二：即使以 `RpcException` 形式出现**，Failover 里还有一道检查（`FailoverClusterInvoker.java:103-109`）：

```java
            } catch (RpcException e) {
                if (e.isBiz()) { // biz exception.
                    throw e;
                }
                le = e;
            } catch (Throwable e) {
                le = new RpcException(e.getMessage(), e);
            }
```

`isBiz()` 为真 → 直接上抛，不重试。

**结论**：重试只解决"传输层面可能瞬时失败"的问题（超时/网络）；业务层面的失败（参数校验、余额不足、状态非法）重试没有意义。这也提醒：**别把业务失败伪装成正常返回**（比如在业务代码里捕获异常后返回 null），否则重试与降级语义会错乱。

---

## 五、优雅下线：SIGTERM 之后发生了什么

### 5.1 源码链：hook → destroy → close(timeout)

SIGTERM 触发 JVM 停机钩子 `DubboShutdownHook.run`（`DubboShutdownHook.java:75-84`）→ `doDestroy`（`:86-143`，节选）：

```java
    private void doDestroy() {
        int timeout = ConfigurationUtils.getServerShutdownTimeout(applicationModel);
        ConfigurationUtils.setExpectedShutdownTime(System.currentTimeMillis() + timeout);

        // send readonly for shutdown hook
        List<GracefulShutdown> gracefulShutdowns =
                GracefulShutdown.getGracefulShutdowns(applicationModel.getFrameworkModel());
        for (GracefulShutdown gracefulShutdown : gracefulShutdowns) {
            gracefulShutdown.readonly();
        }
        ...
        if (!applicationModel.isDestroyed()) {
            logger.info("Dubbo shutdown hooks execute now. " + applicationModel.getDesc());
            applicationModel.destroy();
        }
    }
```

- `timeout` 来自 `dubbo.service.shutdown.wait`（毫秒；`CommonConstants.java:310`），默认 **10000**（`DEFAULT_SERVER_SHUTDOWN_TIMEOUT`，`CommonConstants.java:184`）；`setExpectedShutdownTime` 把它变成全局"关停截止时间"，多处关停共享这个预算（`ConfigurationUtils.java:121`、`:146-153`）；
- 先发 **readonly**：消费方收到后把这条 channel 标记为不可用（`HeaderExchangeHandler.java:78-79`；`DubboInvoker.isAvailable` 要 `!client.hasAttribute(CHANNEL_ATTRIBUTE_READONLY_KEY)` 才可用，`:163-174`）——**先让调用方别选我**；
- `applicationModel.destroy()` → 协议销毁 → 关服务器（`DubboProtocol.java:610`）：

```java
                server.close(ConfigurationUtils.reCalShutdownTime(getServerShutdownTimeout(protocolServer)));
```

`HeaderExchangeServer.close(timeout)`（`HeaderExchangeServer.java:103-122`，节选）做三件事：`startClose()`（不再接新连接）→ 发 readonly → **等在途排空**：

```java
        startClose();
        if (timeout > 0) {
            final long start = System.currentTimeMillis();
            if (getUrl().getParameter(Constants.CHANNEL_SEND_READONLYEVENT_KEY, true)) {
                sendChannelReadOnlyEvent();
            }
            while (HeaderExchangeServer.this.isRunning() && System.currentTimeMillis() - start < (long) timeout) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    logger.warn(TRANSPORT_FAILED_CLOSE, "", "", e.getMessage(), e);
                }
            }
        }
        doClose();
        server.close(timeout);
```

`isRunning()` 的语义（`:87-91`）：

```java
    private boolean isRunning() {
        // If there are any client connections,
        // our server should be running.
        return getChannels().stream().anyMatch(Channel::isConnected);
    }
```

即：**只要还有消费方连着，就继续等**（最多 timeout）。最后 `server.close(timeout)` → `AbstractServer.close`（`AbstractServer.java:149-154`）→ `ExecutorUtil.gracefulShutdown(executor, timeout)`（`ExecutorUtil.java:64-80`，节选）：

```java
        try {
            // Disable new tasks from being submitted
            es.shutdown();
        } catch (SecurityException | NullPointerException ex2) {
            return;
        }
        try {
            // Wait a while for existing tasks to terminate
            if (!es.awaitTermination(timeout, TimeUnit.MILLISECONDS)) {
                es.shutdownNow();
            }
        } catch (InterruptedException ex) {
```

### 5.2 默认 10s：在途请求被排空

实验 ③：SIGTERM 到达时 `slow(3000)` 正在执行 →

- provider 侧：`slow 开始 11:41:45.940 → slow 结束 11:41:48.941`——**完整跑完 3s**，没有被中断；
- consumer 侧：`OK in 3011ms`——在途请求拿到了正常结果；
- provider 退出：SIGTERM 后 **5100ms**（在途完成 + 消费方断开后提前关，没等满 10s）。

### 5.3 wait=0：关得快，但会打断在途任务

实验 ④：`-Ddubbo.service.shutdown.wait=0` → `close(0)` 跳过 readonly 与等待循环 → `ExecutorUtil.gracefulShutdown(executor, 0)`：`awaitTermination(0)` 立即返回 false → **`shutdownNow()` 打断工作线程**。

观测到的后果很微妙：

- provider 侧 `slow` 的 `Thread.sleep(3000)` 在 **230ms** 时被中断（`11:41:59.166 → 11:41:59.396`）；
- 但 `DemoServiceImpl` 捕获 `InterruptedException` 后**吞掉了中断**、照常返回 `"slept 3000ms"`；
- consumer 拿到 `OK in 244ms: slept 3000ms`——**一个提前 2.7 秒返回的"假成功"**。

这是本篇最值得记住的坑：**优雅下线窗口内的在途任务可能被中断；如果业务代码不传播中断（甚至返回正常值），调用方会把半成品当成功**。生产上：业务代码遇到中断要么快速失败，要么保证操作原子；`wait` 的值就是"愿意为在途任务等多久"的预算。

---

## 六、路由：标签路由怎么把流量圈起来

没有动态规则时，标签路由走静态路径 `TagStateRouter.filterUsingStaticTag`（`TagStateRouter.java:202-222`，节选）：

```java
    private <T> BitList<Invoker<T>> filterUsingStaticTag(BitList<Invoker<T>> invokers, URL url, Invocation invocation) {
        BitList<Invoker<T>> result;
        // Dynamic param
        String tag = StringUtils.isEmpty(invocation.getAttachment(TAG_KEY))
                ? url.getParameter(TAG_KEY)
                : invocation.getAttachment(TAG_KEY);
        // Tag request
        if (!StringUtils.isEmpty(tag)) {
            result = filterInvoker(
                    invokers, invoker -> tag.equals(invoker.getUrl().getParameter(TAG_KEY)));
            if (CollectionUtils.isEmpty(result) && !isForceUseTag(invocation)) {
                result = filterInvoker(
                        invokers,
                        invoker -> StringUtils.isEmpty(invoker.getUrl().getParameter(TAG_KEY)));
            }
        } else {
            result = filterInvoker(
                    invokers, invoker -> StringUtils.isEmpty(invoker.getUrl().getParameter(TAG_KEY)));
        }
        return result;
    }
```

三条规则，逐条对应实验 ⑤：

| 规则 | 实验现象 |
|---|---|
| 带 tag 的请求 → 只选 `dubbo.tag` 相同的 provider | consumer `tag=A`：4/4 全部落到 `lab-provider-A` |
| 匹配不到且未强制 → 回退到**无标签** provider | （本实验 A 存在，未触发） |
| 无 tag 的请求 → 只选**无标签** provider | 两个 provider 都带标签 → `No provider available` |

`TAG_KEY` 就是 `"dubbo.tag"`（`CommonConstants.java:348`）。消费方用客户端附件设置（lab 里 `RpcContext.getClientAttachment().setAttachment("dubbo.tag", tag)`，**每次调用都要设**——消费方 `ConsumerContextFilter` 在每次调用响应/异常后都会 `removeClientAttachment()`，`ConsumerContextFilter.java:122-136`），provider 把 `dubbo.tag` 放进注册 URL。

生产用途：**灰度/蓝绿**——新版本实例打 `tag=gray`，入口按用户分流；配合配置中心的**动态标签规则**（`TagRouterRule`，`route` 方法 `:117-161`）还能做地址组 + `force`（匹配不到时是否强制不回退）。

---

## 七、限流与熔断：Dubbo 内置了什么

Dubbo 核心内置的是**单机维度的三个 Filter**（都在 `dubbo-rpc-api/.../rpc/filter/`）：

| Filter | 参数 | 语义 | 坐标 |
|---|---|---|---|
| `ExecuteLimitFilter` | `executes` | provider 侧并发超过阈值 → 抛异常 | `:40`（`@Activate(PROVIDER)`）、`:51`（throw） |
| `TpsLimitFilter` | `tps` / `tps.interval` | provider 侧按时间窗限速（`DefaultTPSLimiter`） | `:40`、`:48`（`isAllowable`） |
| `ActiveLimitFilter` | `actives` | consumer 侧对某方法的并发调用数限制 | `:45`（`@Activate(CONSUMER)`）、`:70`（throw） |

集群容错策略（`dubbo-cluster/.../support/`）：`Failover`（默认，失败换机重试）、`Failfast`（失败立即抛）、`Failsafe`（失败忽略、记日志）、`Failback`（失败记录、定时重发）、`Forking`（并行调 N 个取先返回）、`Broadcast`（广播全部）。选型一句话：**读用 Failover，写用 Failfast/Failsafe 或 retries=0，非幂等必须配幂等键**。

**熔断**：Dubbo 核心没有内置熔断器，靠生态集成（如 Sentinel 适配）——本篇不展开，划到支撑域。

---

## 八、实验解读

| 观测 | 结论 |
|---|---|
| retries=2 → 3 次执行、3006ms | `len = retries + 1`；重试放大 + 超时叠加 |
| biz-error → provider 1 次 | 业务异常随 AppResponse 回传，不在集群重试范围；另有 `isBiz` 防线 |
| SIGTERM 中 slow 跑完 3s、consumer OK | 默认 wait=10000：等在途排空（readonly + isRunning 等待） |
| wait=0 → slow 230ms 被中断、consumer 拿到"假成功" | `awaitTermination(0)` 失败 → `shutdownNow()`；业务吞中断会掩盖半成品 |
| tag=A → 全 A；无标签 → 无 provider | 静态标签路由：带 tag 精确匹配、无 tag 只看无标签 |

---

## 九、生产陷阱

1. **重试放大**：默认 `retries=2`（`CommonConstants.java:417`）——非幂等写操作 + 超时 = 重复执行 3 次；先问自己"这个操作重复执行会怎样"，再决定 retries；
2. **超时预算**：`(retries+1) × timeout` 是用户最长等待，必须小于上游超时；否则上游先超时，用户看到失败、下游还在重试；
3. **业务失败 ≠ 传输失败**：业务异常不重试（本实验）；别把业务异常包装成正常返回值，否则重试/降级语义全乱；
4. **优雅下线**：`wait` 默认 10s；`wait=0` 会 `shutdownNow()` 打断在途任务；业务代码必须正确处理中断（实验④的"假成功"就是反面教材）；变更流程 = 先 readonly/摘流量 → 排空 → 关闭；
5. **标签路由的回退**：静态 tag 匹配不到且非 force 会**回退到无标签 provider**；反过来，全部 provider 都带标签时，无标签消费者拿不到任何 provider（实验⑤）——灰度要有配套入口分流和 force 语义设计。

---

## 附录 A：面试速答（附带）

> Failover 重试次数 = `retries+1`，默认 retries=2 → 最多 3 次；第 2 次起重新拉目录、重选（避开试过的）。重试只对传输层失败（超时/网络）有意义：业务异常随 AppResponse 回传，在代理层 `recreate()` 才抛出，集群层看不到；即使以 RpcException 出现也有 `isBiz()` 直接上抛。超时预算 = (retries+1)×timeout。优雅下线：SIGTERM → DubboShutdownHook → 发 readonly（消费方把 channel 标不可用）→ `server.close(timeout)`：`isRunning()`（还有连接就等）等待排空，最多 `dubbo.service.shutdown.wait`（默认 10000ms）→ 超时后 `ExecutorUtil.gracefulShutdown` 的 `shutdownNow()` 打断在途任务。wait=0 时在途任务会被中断，业务若吞中断会返回"假成功"。标签路由静态规则：带 tag 精确匹配 `dubbo.tag`，匹配不到非 force 回退无标签；无 tag 只选无标签 provider。限流三 Filter：executes（provider 并发）、tps（provider 速率）、actives（consumer 并发）；熔断靠 Sentinel 等生态。

## 附录 B：实验与复现

```bash
cd dubbo-lab
export JAVA_HOME=/env/javaEnv/jdk17
mvn -DskipTests install
bash scripts/08-failover-graceful.sh   # 输出见 evidence/08-failover-graceful.log
# 标签路由段依赖 Nacos（docker compose up -d 或复用已有实例）
```

| 观测点 | 结果 |
|---|---|
| 重试放大 | retries=2: FAIL 3006ms / provider 3 次；retries=0: FAIL 1005ms / 1 次 |
| 业务异常 | `IllegalStateException` 原样返回；provider 1 次 |
| 优雅下线（默认） | consumer OK 3011ms；provider 退出 5100ms；slow 完整 3s |
| 优雅下线（wait=0） | slow 被中断（230ms）；consumer `OK in 244ms: slept 3000ms`；退出 427ms |
| 标签路由 | tag=A → 4/4 A；无标签 → No provider available |

## 附录 C：源码坐标（Dubbo 3.3.3 + lab）

| 内容 | 坐标 |
|---|---|
| 重试循环/次数 | `dubbo-cluster/.../support/FailoverClusterInvoker.java:59-127`、`:129-142`；重选避让 `AbstractClusterInvoker.java:155/187/254`；`retries` 默认 `CommonConstants.java:417` |
| 业务异常回传 | `dubbo-rpc/dubbo-rpc-api/.../rpc/AppResponse.java:79-104`；`InvocationUtil.java:64`；`FailoverClusterInvoker.java:103-109`；`DefaultFuture.java:253-263`（OK → `complete(res.getResult())`） |
| 停机钩子 | `dubbo-config/dubbo-config-api/.../config/DubboShutdownHook.java:75-84`、`:86-143`；readonly 实现 `DubboGracefulShutdown.java:46-47` |
| shutdown.wait 默认值 | `CommonConstants.java:310`、`:184`；`dubbo-common/.../config/ConfigurationUtils.java:121`、`:146-153` |
| 服务器关闭/排空 | `dubbo-remoting-api/.../header/HeaderExchangeServer.java:87-91`、`:103-122`；`DubboProtocol.java:610`；`AbstractServer.java:149-154`；`ExecutorUtil.java:64-80` |
| readonly 事件 | `HeaderExchangeHandler.java:78-79`；`DubboInvoker.java:163-174` |
| 标签路由 | `dubbo-cluster/.../router/tag/TagStateRouter.java:202-222`（静态）、`:117-161`（动态规则）；`TAG_KEY` `CommonConstants.java:348`；附件清理 `dubbo-cluster/.../filter/support/ConsumerContextFilter.java:122-136` |
| 限流 Filter | `dubbo-rpc-api/.../rpc/filter/ExecuteLimitFilter.java:40/51`、`TpsLimitFilter.java:40/48`、`ActiveLimitFilter.java:45/70` |
| lab | `scripts/08-failover-graceful.sh`；`ConsumerApp -Dlab.retries/-Dlab.name/-Dlab.tag`；`ProviderApp -Dlab.provider.url.tag`；`-Dlab.shutdown.wait` |
