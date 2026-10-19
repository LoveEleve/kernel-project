# 05 一次调用到底经过了什么？——代理 / Invoker / Filter / 集群 / LB

> 定位：Dubbo 系列第 05 篇——**问题驱动**；源码域 D-3（代理、Invoker、过滤器链）+ D-5（集群容错、路由、负载均衡）作为覆盖清单。
> lab：`scripts/06-call-chain-lb.sh`；证据：`dubbo-lab/evidence/06-call-chain-lb.log`（另用 05 篇的自定义 Filter 观测两端）。
> 课程对照：《Dubbo源码剖析与实战》21 调用流程 / 24 拦截扩展 / 23 集群扩展 + 《RPC实战与核心原理》09-12。

---

## 一、先看实验：两个 provider，一行调用

起两个 provider（A/20880、B/20881，同一个服务注册到 Nacos），consumer 只写一行：

```java
demo.sayHello("loop-" + i);
```

**consumer 完全不知道有几个 provider**。实验结果：

| LB 策略 | 10 次调用分布 | 明细 |
|---|---|---|
| `random`（默认） | A:4 / B:6 | 无规律 |
| `roundrobin` | A:5 / B:5 | **严格交替**：A,B,A,B,A,B,A,B,A,B |

同时，两端的自定义 Filter（第 04 篇）打印出请求经过的"站点"：

```
[client-trace] 发起调用 sayHello（SPI 自定义 Filter 生效）
[trace] provider 收到 sayHello（SPI 自定义 Filter 生效）
```

这一行调用从 consumer 的方法，到某个 provider 的反射执行，中间到底经过了哪些层？这篇把它拆开。

---

## 二、朴素答案"代理直接发请求"死在哪

最直观的实现：代理拿到一个连接，序列化，发出去。

但下面每一件事都需要**插入到调用路径里**：

| 需求 | 插入点 |
|---|---|
| 多个 provider 选一个 | 负载均衡 |
| 按条件过滤实例（灰度/同机房） | 路由 |
| 失败换一个 provider 重试 | 集群容错 |
| 日志/鉴权/限流/上下文透传 | 过滤器链 |
| 超时/异步/回调 | 协议与交换层 |

如果代理直接发请求，这些能力要么写死在代理里，要么根本没处放。Dubbo 的答案是**分层责任链**：每一层都是 `Invoker`（"能执行一次调用"的抽象），层与层之间通过包装串起来——第 04 篇见过的 Wrapper 机制，在这里变成调用链本身。

---

## 三、调用链全景

**consumer 侧（自上而下）**：

```
demo.sayHello()                         ← 业务接口调用
  └─ 代理（InvokerInvocationHandler）    ← 方法 → RpcInvocation
      └─ Filter 链（consumer 过滤器）    ← 日志/鉴权/上下文…
          └─ ClusterInvoker（Failover）  ← 容错：重试循环
              ├─ Directory.list()        ← 目录：可用 Invoker 列表
              ├─ RouterChain.route()     ← 路由：过滤候选集
              ├─ LoadBalance.select()    ← 负载均衡：选一个
              └─ DubboInvoker.invoke()   ← 协议层（02 篇的 calculateTimeout 在这里）
                  └─ Exchange / Netty    ← 请求写出
```

**provider 侧**：

```
Netty 收到请求 → DubboProtocol 定位 Exporter → provider Filter 链
  → 反射执行实现类（DemoServiceImpl） → 结果回程
```

---

## 四、源码逐层看

### 4.1 代理：方法调用 → RpcInvocation

`InvokerInvocationHandler.invoke`（`InvokerInvocationHandler.java:51`，节选）：

```java
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(invoker, args);
        }
        String methodName = method.getName();
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length == 0) {
            if ("toString".equals(methodName)) {
                return invoker.toString();
            } else if ("$destroy".equals(methodName)) {
                invoker.destroy();
                return null;
            } else if ("hashCode".equals(methodName)) {
                return invoker.hashCode();
            }
        } else if (parameterTypes.length == 1 && "equals".equals(methodName)) {
            return invoker.equals(args[0]);
        }
        RpcInvocation rpcInvocation = new RpcInvocation(
                serviceModel,
                method.getName(),
                invoker.getInterface().getName(),
                protocolServiceKey,
                method.getParameterTypes(),
                args);
        ...
        return InvocationUtil.invoke(invoker, rpcInvocation);
    }
```

两个细节值得注意：`toString/hashCode/equals` 被**本地短路**（否则调试打印会触发远程调用）；真正的调用被转成 `RpcInvocation`（接口 + 方法 + 参数），交给 `invoker`——**从这里开始，一切都是 Invoker 链**。

### 4.2 Filter 链：责任链的组装

`DefaultFilterChainBuilder.buildInvokerChain`（`DefaultFilterChainBuilder.java:44`，节选）：

```java
    public <T> Invoker<T> buildInvokerChain(final Invoker<T> originalInvoker, String key, String group) {
        Invoker<T> last = originalInvoker;
        URL url = originalInvoker.getUrl();
        ...
            filters = ScopeModelUtil.getExtensionLoader(Filter.class, null).getActivateExtension(url, key, group);
        ...
        if (!CollectionUtils.isEmpty(filters)) {
            for (int i = filters.size() - 1; i >= 0; i--) {
                final Filter filter = filters.get(i);
                final Invoker<T> next = last;
                last = new CopyOfFilterChainNode<>(originalInvoker, next, filter);
            }
            return new CallbackRegistrationInvoker<>(last, filters);
        }
```

**倒序遍历**过滤器、逐个包在 `last` 外面——最终返回的是最外层 Invoker。第 04 篇实验里的 `[client-trace]`/`[trace]` 就挂在这条链上（provider 侧链在 `ProtocolFilterWrapper` 的 export 路径里组装）。

### 4.3 集群：目录 → 路由 → 负载均衡

`AbstractClusterInvoker.invoke`（`AbstractClusterInvoker.java:345`，节选）：

```java
    public Result invoke(final Invocation invocation) throws RpcException {
        checkWhetherDestroyed();
        ...
        List<Invoker<T>> invokers = list(invocation);
        ...
        checkInvokers(invokers, invocation);

        LoadBalance loadbalance = initLoadBalance(invokers, invocation);
        RpcUtils.attachInvocationIdIfAsync(getUrl(), invocation);
        ...
        try {
            return doInvoke(invocation, invokers, loadbalance);
```

`list(invocation)`（`:451`）从 Directory 拿当前可用 Invoker（第 03 篇的目录），`doInvoke` 是具体容错策略。候选集还会经过 `RouterChain.route`（`RouterChain.java:120`）过滤——**注册列表 ≠ 实际候选集**，这是排查"注册了却没流量"的关键。

### 4.4 容错：Failover 的重试循环

`FailoverClusterInvoker.doInvoke`（`FailoverClusterInvoker.java:59`，节选）：

```java
        List<Invoker<T>> copyInvokers = invokers;
        String methodName = RpcUtils.getMethodName(invocation);
        int len = calculateInvokeTimes(methodName);
        // retry loop.
        ...
        for (int i = 0; i < len; i++) {
            // Reselect before retry to avoid a change of candidate `invokers`.
            ...
            if (i > 0) {
                checkWhetherDestroyed();
                copyInvokers = list(invocation);
                checkInvokers(copyInvokers, invocation);
            }
            Invoker<T> invoker = select(loadbalance, invocation, copyInvokers, invoked);
            invoked.add(invoker);
            ...
            try {
                Result result = invokeWithContext(invoker, invocation);
                ...
                return result;
            } catch (RpcException e) {
                if (e.isBiz()) { // biz exception.
                    throw e;
                }
                le = e;
            }
```

重试次数由 `calculateInvokeTimes` 算（默认 `retries=2` → 最多 3 次）；**业务异常不重试**，只有 RPC 层失败才换实例。第 02 篇实验把 `retries` 设为 0 就是为了隔离超时行为；重试放大的实测放在第 07 篇。

### 4.5 负载均衡：单实例短路 + doSelect

`AbstractLoadBalance.select`（`AbstractLoadBalance.java:55`）：

```java
    public <T> Invoker<T> select(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        if (CollectionUtils.isEmpty(invokers)) {
            return null;
        }
        if (invokers.size() == 1) {
            return invokers.get(0);
        }
        return doSelect(invokers, url, invocation);
    }
```

只有一个实例时直接返回（省掉策略开销）；多实例才进 `doSelect`——`RandomLoadBalance` 按权重随机，`RoundRobinLoadBalance` 平滑加权轮询（等权时表现为严格交替，正是实验观察到的）。

---

## 五、实验细节（包括一个真实的插曲）

**① LB 对照**（证据 `06-call-chain-lb.log`）：

- `random`：A:4 / B:6；
- `roundrobin`：A,B 严格交替。

**② provider 侧确认**：两端的 trace filter 显示请求分别到达 A/B 两个进程，排除"只是响应里带了 tag"的错觉。

**③ 关于连发频率（证据 `06-burst-instability.log`）**：50ms 间隔连发 **3 轮 × 20 次 = 60/60 成功**；但在本会话早期机器负载较高时，同样的 50ms 连发曾出现间歇 `RpcException`（后续未能稳定复现）。结论按可复现证据记录：**lab 的正式观察用 1s 间隔**；偶发失败属于生产必须处理的失败/重试问题（第 07 篇），本课不展开。

**④ 收尾观察**：脚本里 provider 的优雅停机（SIGTERM）在 20 秒内没有释放端口，最终强制清理——因为要走注册中心反注册/连接关闭（第 03 篇的"注销延迟"、第 08 篇的"优雅下线"）。

---

## 六、生产陷阱

1. **重试放大非幂等**：Failover 默认重试 2 次，`timeout × (retries+1)` 是最坏等待；非幂等写操作要按方法关掉重试（第 07 篇）；
2. **路由后候选集**：目录里的实例数不等于会接流量的实例数（路由/覆盖会过滤）；
3. **LB 不知道"慢实例"**：random/roundrobin 只看权重，不看实时负载；`leastactive` 才考虑活跃数；provider 刚启动有 **warmup 权重**（本次实验显式 `warmup=0` 才得到严格交替）；
4. **上下文清理**：RpcContext 附件跨线程/异步会丢或串（第 08 篇）；
5. **调试方法**：这条链上每一层都是 Invoker 包装——排障时用 Filter 打印（本篇）或 QoS 的调用信息，不要猜。

---

## 附录 A：面试速答（附带）

> 一次 Dubbo 调用：代理（InvokerInvocationHandler）把方法转成 RpcInvocation；进入 Filter 责任链（consumer 侧过滤器，倒序包装）；到 ClusterInvoker（默认 Failover）——先 list() 拿目录候选集，经 RouterChain 路由过滤，LoadBalance 选一个 Invoker（单实例短路），失败则换实例重试（业务异常不重试）；最后 DubboInvoker 走协议与网络。provider 侧反向：Netty → 协议定位 Exporter → provider Filter 链 → 反射执行。关键点：一切皆 Invoker（每层都是包装）；注册列表 ≠ 候选集（路由会过滤）；重试放大 timeout；LB 有 warmup 权重。

## 附录 B：实验与复现

```bash
cd dubbo-lab
export JAVA_HOME=/env/javaEnv/jdk17
mvn -DskipTests install
bash scripts/06-call-chain-lb.sh     # 输出见 evidence/06-call-chain-lb.log
```

| 观测点 | 结果 |
|---|---|
| Nacos 实例数 | 2（A/20880、B/20881） |
| random（10 次） | A:4 / B:6 |
| roundrobin（10 次） | A,B 严格交替（5/5） |
| 两端 Filter | `[client-trace]` / `[trace]` 证明请求经过对应实例 |
| 连发稳定性 | 50ms 连发 3 轮 ×20 次全部成功（`06-burst-instability.log`）；高负载时曾观察到间歇失败（未复现，不计入证据） |

## 附录 C：源码坐标（Dubbo 3.3.3 + lab）

| 内容 | 坐标 |
|---|---|
| 代理 → RpcInvocation | `dubbo-rpc/dubbo-rpc-api/.../proxy/InvokerInvocationHandler.java:51` |
| Filter 链组装 | `dubbo-cluster/.../filter/DefaultFilterChainBuilder.java:44`（链构建 `70-74`） |
| 集群入口（list/check/LB/doInvoke） | `dubbo-cluster/.../support/AbstractClusterInvoker.java:345`（`invoke`）、`:155`（`select`）、`:451`（`list`） |
| 路由链 | `dubbo-cluster/.../RouterChain.java:120`（`route`） |
| Failover 重试 | `dubbo-cluster/.../support/FailoverClusterInvoker.java:59`（`doInvoke`） |
| 负载均衡 | `dubbo-cluster/.../loadbalance/AbstractLoadBalance.java:55`（`select`） |
| lab | `scripts/06-call-chain-lb.sh`；`ProviderApp -Dlab.port/-Dlab.provider.tag`；`ConsumerApp -Dlab.lb/-Dlab.loop` |
| 课程对照 | 《Dubbo源码剖析与实战》21/24/23；《RPC实战与核心原理》09-12 |
