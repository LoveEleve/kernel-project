# 02 从用到懂：Dubbo 怎么用？为什么你的超时不生效？

> 定位：Dubbo 系列第 02 篇——**问题驱动**（从一场真实事故出发，先能用、再懂配置优先级、最后看源码）；源码域 D-1（启动、配置与服务生命周期）作为覆盖清单。
> lab：`lab-provider`/`lab-consumer`（API 风格）；证据：`dubbo-lab/evidence/02-direct-invoke.log`、`03-timeout-precedence.log`。
> 课程对照：《Dubbo源码剖析与实战》11 配置加载顺序 / 12 分层 / 05 点点直连。

---

## 一、一场升级引发的事故

某敏感系统把 Dubbo 从 2 升级到 3。提供方在暴露接口时**用 API 方式**设了超时：接口级 5000ms，`decrypt` 方法级 3000ms。消费方什么都没改，只升了 pom 版本号。

预发环境一发布，消费方开始超时：

```
Caused by: org.apache.dubbo.remoting.TimeoutException: Waiting server-side response timeout ...
client elapsed: 2 ms, server elapsed: 1016 ms, timeout: 1000 ms,
request: ... attachments={path=...CryptoFacade, ..., timeout=1000}
```

异常里的 `timeout: 1000` 说明：**消费方按 1000ms 在等**——提供方设的 5000/3000 完全没起作用。

为什么？这篇文章要回答两个问题：**Dubbo 怎么用**，以及**一个配置项到底谁说了算**。

---

## 二、朴素答案为什么错

朴素答案："配置就是给字段赋值，谁设了就用谁设的。"

这个假设隐含了四个错误前提：

| 维度 | 事实 |
|---|---|
| **来源** | 同一个配置项可以来自 JVM `-D`、外部化配置（Nacos 等）、API、XML、注解——多条来源要先合并 |
| **粒度** | 可以配在方法级、接口级、全局级——细粒度覆盖粗粒度 |
| **侧** | 消费方和提供方都可以配——同一个参数要决定谁优先 |
| **时机** | 配置不是"设了就一直有效"，而是**在启动/引用时被合并进一个 URL**，之后调用只认这个 URL |

所以"配置不生效"的正确问法不是"我设了吗"，而是：**我的值有没有进最终那个 URL？**

---

## 三、先把"用"跑起来：三种风格

Dubbo 支持三种配置风格，能力等价，写法不同：

**① API 风格**（lab 采用——显式装配，最适合看清链路）：

```java
ServiceConfig<DemoService> service = new ServiceConfig<>();
service.setInterface(DemoService.class);
service.setRef(new DemoServiceImpl());
if (providerTimeout > 0) {
    service.setTimeout(providerTimeout);
    ...
}

DubboBootstrap.getInstance()
        .application(application)
        .registry(new RegistryConfig(registry))
        .protocol(new ProtocolConfig("dubbo", 20880))
        .service(service)
        .start()
        .await();
```

```java
ReferenceConfig<DemoService> reference = new ReferenceConfig<>();
reference.setInterface(DemoService.class);
if (direct) {
    reference.setUrl(directUrl);
}
if (refTimeout > 0) {
    reference.setTimeout(refTimeout);
}
if (methodTimeout > 0) {
    MethodConfig method = new MethodConfig();
    method.setName("slow");
    method.setTimeout(methodTimeout);
    reference.setMethods(List.of(method));
}
reference.setRetries(0);

DubboBootstrap.getInstance()
        .application(application)
        .registry(new RegistryConfig(registry))
        .reference(reference)
        .start();
DemoService demo = reference.get();
```

**② 注解风格**（Spring Boot 项目常用）：

```java
@DubboService(timeout = 5000)               // 提供方
public class DemoServiceImpl implements DemoService { ... }

@DubboReference(timeout = 3000)             // 消费方
private DemoService demoService;
```

**③ XML 风格**（传统项目）：

```xml
<dubbo:service interface="com.myxhs.dubbo.lab.api.DemoService"
               ref="demoService" timeout="5000"/>
<dubbo:reference id="demoService"
                 interface="com.myxhs.dubbo.lab.api.DemoService" timeout="3000"/>
```

lab 刻意用 API 风格：**Spring 集成只是把配置喂给同一套 `DubboBootstrap`**，先用 API 看清主链；扩展体系见第 04 篇，Spring/配置中心等集成不在本系列展开（见 `00-系列规划.md` 的"系列边界"）。（上面前两块取自 lab 源码；注解/XML 块为示意写法，非源码引用。）

跑通直连调用（证据 `02-direct-invoke.log`）：

```
[lab-consumer] sayHello = hello dubbo-lab from lab-provider
[lab-consumer] getUser  = User{id=1, name='user-1'}
```

---

## 四、四条优先级规则

把"谁说了算"拆成四条可验证的规则：

1. **粒度越细越优先**：方法级 > 接口级 > 全局级；
2. **消费方 > 提供方**（同一个参数）：消费方的配置会覆盖提供方 URL 里的同名参数（线程池等少数"提供方专属"参数除外）；
3. **双方都不配 → 默认值**：如 `timeout` 默认 1000ms（`CommonConstants.java:147` `DEFAULT_TIMEOUT = 1000`）；
4. **提供方参数要"进 URL"才能被消费方感知**：走注册中心时提供方参数会随注册 URL 传过来；**直连/API 方式下不会**——这正是第一节事故的根因。

规则 4 值得展开：`timeout` 本质是**消费方参数**（决定消费方等多久），提供方把它配进自己的 URL 只是"建议默认值"。API 方式在提供方本地设的 timeout，若没有进入注册 URL（或直连 URL），消费方根本看不到，于是回落到自己的默认 1000ms。

---

## 五、实验：超时到底谁说了算

脚本 `scripts/03-timeout-precedence.sh`；提供方统一设接口级 `timeout=5000ms`；四个场景（证据 `03-timeout-precedence.log`）：

| 场景 | 消费方配置 | 调用 | 结果 | 结论 |
|---|---|---|---|---|
| A | 接口级 3000ms | `slow(4000)` | **FAIL in 3004ms** | 消费方 3000 覆盖提供方 5000（规则 2） |
| B | 接口级 3000 + 方法级 1000ms | `slow(2000)` | **FAIL in 1004ms** | 方法级 1000 覆盖接口级 3000（规则 1） |
| C | 都不设 | `slow(2000)` | **FAIL in 1004ms** | 默认 1000ms；提供方 API 配置不感知（规则 3/4） |
| D | 都不设，直连 URL 带 `?timeout=5000` | `slow(2000)` | **OK in 2006ms** | 提供方参数"进了 URL"才生效（规则 4 反证） |

场景 C 就是第一节事故的最小复现。场景 D 说明修复思路：要么走注册中心让提供方参数随 URL 传播，要么在消费方显式配置。

实验还抓到一个细节（引向第 06 篇）：

```
[lab-consumer] slow(4000) -> FAIL in 3004ms: RpcException
    provider 侧: [provider] slow 开始, millis=4000 ...
                [provider] slow 结束, millis=4000
```

**消费方已经超时了，提供方还在把 slow 执行完**——timeout 只是"消费方等待上限"，不是"停止提供方执行"的指令。

---

## 六、源码：配置是在什么时候合并成 URL 的

### 6.1 消费方：启动时把配置合并成 URL

`ReferenceConfig.init()`（`ReferenceConfig.java:332`，节选）：

```java
    protected void init(boolean check) {
        lock.lock();
        try {
            if (initialized && ref != null) {
                return;
            }
            ...
                Map<String, String> referenceParameters = appendConfig();
                ...
                ref = createProxy(referenceParameters);
```

`createProxy`（`ReferenceConfig.java:490`，节选）区分两种寻址：

```java
    private T createProxy(Map<String, String> referenceParameters) {
        urls.clear();
        ...
        if (StringUtils.isNotEmpty(url)) {
            // user specified URL, could be peer-to-peer address, or register center's address.
            parseUrl(referenceParameters);
        } else {
            aggregateUrlFromRegistry(referenceParameters);
        }
        ...
        URL consumerUrl = new ServiceConfigURL(
                ...
                referenceParameters);
        ...
        return (T) proxyFactory.getProxy(invoker, ProtocolUtils.isGeneric(generic));
```

注意 `appendConfig()`：方法级配置就是在这里变成 URL 参数（如 `slow.timeout=1000`）的。之后调用只认这个 URL。

### 6.2 调用时：超时值如何被解析

每次调用最终都会走到 `DubboInvoker.doInvoke`（`DubboInvoker.java:107`）：

```java
            int timeout = RpcUtils.calculateTimeout(getUrl(), invocation, methodName, DEFAULT_TIMEOUT);
```

`RpcUtils.calculateTimeout`（`RpcUtils.java:285`）→ `getTimeout`（`RpcUtils.java:269`，节选）：

```java
        if (timeoutFromContext != null) {
            timeout = convertToNumber(timeoutFromContext, defaultTimeout);
        } else if (timeoutFromInvocation != null) {
            timeout = convertToNumber(timeoutFromInvocation, defaultTimeout);
        } else if (url != null) {
            timeout = url.getMethodPositiveParameter(methodName, TIMEOUT_KEY, defaultTimeout);
        }
```

优先级：**上下文附件 > 调用附件 > URL 方法参数 > 默认值**。而"URL 方法参数"内部的"方法级 → 接口级回退"在 `URLParam`（`URLParam.java:198`）：

```java
    public String getMethodParameter(String method, String key) {
        String strictResult = getMethodParameterStrict(method, key);
        return StringUtils.isNotEmpty(strictResult) ? strictResult : getParameter(key);
    }
```

先查 `slow.timeout`（严格方法级），没有再回退到接口级 `timeout`——规则 1 的源码形态。

### 6.3 消费方覆盖提供方：mergeUrl 的 putAll

消费方 URL 与提供方 URL 合并发生在 `DefaultProviderURLMergeProcessor.mergeUrl`（`DefaultProviderURLMergeProcessor.java:49`，节选）：

```java
    public URL mergeUrl(URL remoteUrl, Map<String, String> localParametersMap) {
        Map<String, String> map = new HashMap<>();
        Map<String, String> remoteMap = remoteUrl.getParameters();
        if (remoteMap != null && remoteMap.size() > 0) {
            map.putAll(remoteMap);

            // Remove configurations from provider, some items should be affected by provider.
            map.remove(THREADPOOL_KEY);
            map.remove(CORE_THREADS_KEY);
            ...
            map.putAll(copyOfLocalMap);
```

`map.putAll(copyOfLocalMap)`（`:98`）：先放提供方参数，再放消费方参数——同键覆盖，这就是规则 2。被 `remove` 的是线程池等"应该由提供方决定"的参数（第 06/08 篇展开）。

### 6.4 提供方：export 的幂等

提供方侧 `ServiceConfig` 用 `exported` 标志保证**重复 export 无副作用**（`ServiceConfig.java:149`、`unexport()` `:199`）；`DubboBootstrap.start()`（`DubboBootstrap.java:218`）负责把注册的 `service()/reference()` 统一启动。

---

## 七、生产陷阱

1. **课程同款**：升级后提供方 API 配置的 timeout"消失"——排查时看**最终 URL 里的参数**（注册中心 URL / QoS 命令 / 启动日志），不要看提供方源码里设了什么；
2. **超时预算 = timeout × (retries+1)**：本篇实验固定 `retries=0`；默认 `retries=2` 会让最坏等待变成 3 倍（第 07 篇实测放大）；
3. **重复 export / 提前 get 引用 / 销毁后重建**是 D-1 的三大生命周期坑（幂等标志只保护 export，不保护"拿到坏引用"）；
4. **配置要成对想**：消费方超时、提供方耗时、重试次数三者必须一起算，否则不是超时就是雪崩。

---

## 附录 A：面试速答（附带）

> Dubbo 配置有来源（-D/外部化/API/XML/注解）、粒度（方法/接口/全局）、侧（消费方/提供方）三个维度。优先级：粒度越细越优先（方法 > 接口）、消费方 > 提供方（mergeUrl 时消费方参数 putAll 覆盖，线程池等提供方专属参数除外）、都不配就用默认（timeout 1000ms）。关键认知：配置在启动时被合并进 URL，之后调用只认 URL——所以"提供方 API 方式配的 timeout"如果没进 URL（直连或没注册），消费方根本感知不到，会回落到默认 1000ms。timeout 只是消费方等待上限，超时不会停止提供方执行。

## 附录 B：实验与复现

```bash
cd dubbo-lab
export JAVA_HOME=/env/javaEnv/jdk17
mvn -DskipTests install
bash scripts/02-direct-invoke.sh          # 直连调用（evidence/02-direct-invoke.log）
bash scripts/03-timeout-precedence.sh     # 超时优先级四场景（evidence/03-timeout-precedence.log）
```

| 场景 | 配置 | 结果 |
|---|---|---|
| A | 消费方 3000 vs 提供方 5000 | FAIL 3004ms（消费方优先） |
| B | + 方法级 1000 | FAIL 1004ms（方法级优先） |
| C | 都不设 | FAIL 1004ms（默认 1000） |
| D | 直连 URL `?timeout=5000` | OK 2006ms（参数进 URL 才生效） |

## 附录 C：源码坐标（Dubbo 3.3.3 + lab）

| 内容 | 坐标 |
|---|---|
| 消费方启动合并配置 | `dubbo-config/dubbo-config-api/.../ReferenceConfig.java:332`（`init`）、`:490`（`createProxy`）、`:431`（`appendConfig`） |
| 配置→URL 参数转换 | `dubbo-common/.../config/AbstractConfig.java:163/167`（`appendParameters`）、`:182`（`appendParameters0`） |
| 默认超时 1000ms | `dubbo-common/.../constants/CommonConstants.java:147`（`DEFAULT_TIMEOUT = 1000`） |
| 提供方 export 幂等 | `.../ServiceConfig.java:149`（`exported`）、`:199`（`unexport`） |
| 启动入口 | `.../bootstrap/DubboBootstrap.java:218`（`start`）、`:544`（`service`）、`:580`（`reference`） |
| 超时解析 | `dubbo-rpc/dubbo-rpc-dubbo/.../DubboInvoker.java:107`；`dubbo-rpc/dubbo-rpc-api/.../RpcUtils.java:285`（`calculateTimeout`）、`:269`（`getTimeout`） |
| 方法级→接口级回退 | `dubbo-common/.../url/component/URLParam.java:198`（`getMethodParameter`）、`:211`（`getMethodParameterStrict`） |
| 消费方覆盖提供方 | `dubbo-cluster/.../merger/DefaultProviderURLMergeProcessor.java:49`（`mergeUrl`）、`:98`（`map.putAll(copyOfLocalMap)`） |
| lab 配置入口 | `dubbo-lab/lab-provider/.../ProviderApp.java`、`lab-consumer/.../ConsumerApp.java`（`-Dlab.timeout.*`） |
| 课程对照 | 《Dubbo源码剖析与实战》11 配置加载顺序 / 12 分层 / 05 点点直连 |
