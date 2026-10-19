# FEIGN-09 源码主干

> 用途：**四条链一张图**——注册链 / 构建链 / 调用链（请求生命线）/ 装配链；能默画，能定位源码
> 对应系列：**OpenFeign 专题 · 09**（进阶篇第二篇）
> 口径：Spring Cloud OpenFeign 4.1.3 / feign-core 13.3
> 素材：`openfeign-lab`（[D1 创建机制](../../openfeign-lab/docs/D1-FeignClientFactoryBean源码分析.md) / [D2 注册机制](../../openfeign-lab/docs/D2-FeignClientsRegistrar源码分析.md) / [D3 LB](../../openfeign-lab/docs/D3-FeignBlockingLoadBalancerClient源码分析.md) / [D5 装配](../../openfeign-lab/docs/D5-FeignAutoConfiguration源码分析.md) / [D6 Targeter-SentinelFeign](../../openfeign-lab/docs/D6-Targeter-SentinelFeign源码分析.md)）· 本系列 01-08 已核实的源码锚点
> 前置：01-08（本篇是"串起来"，不引入新机制）；后续：10 收口

---

## 0. 例子先行：四张图还是一张图？

一个 Feign 方法调用，从注解到返回，到底经过哪些"门口"：

```text
@FeignClient 接口
  │ ① 注册链：谁把"接口"变成 Bean？          （注解扫描 → BeanDefinition）
  │ ② 构建链：Bean 怎么变成"可调用的代理"？  （取组件 → Contract 解析 → JDK 代理 + 方法↔处理器映射）
  │ ③ 调用链：方法调用怎么变成 HTTP？        （模板 → 编码 → 拦截器 → 选实例 → 发送 → 解码 → 降级）
  │ ④ 装配链：上面用的组件从哪来？           （子上下文 + 默认配置 + 属性）
  ▼
R<T> / T / 异常
```

**本篇只做一件事：把这四条链画在一张图里**，每个节点都带源码锚点（前面 8 课已逐个核实过，这里只串）。

---

## 1. 掌握卡（3 条）

1. **四条链各回答一个问题**：注册（**哪些**接口变成 Bean）/ 构建（Bean 怎么变成**代理**）/ 调用（方法怎么变成 **HTTP**）/ 装配（组件**从哪来**）。
2. **调用链是主干**：一次请求按固定顺序经过——代理 → 模板 → **编码** → **拦截器** → **选实例（LB）** → 发送 → **重试** → **解码 / 错误解码** → **降级**；每个节点都可替换（对应一个扩展点）。
3. **装配是"三层配置 + 一个子上下文"**：`FeignClientsConfiguration` 默认件 → per-client `configuration` 类 → `spring.cloud.openfeign.client.config.*` 属性；全部活在 **Feign 子上下文**里（与主上下文隔离）。

---

## 2. 注册链：`@FeignClient` → Bean

```text
@EnableFeignClients ──@Import──▶ FeignClientsRegistrar           (EnableFeignClients.java:41)
  ├─ 扫包：所有 @FeignClient 接口
  ├─ 每个接口注册 BeanDefinition：
  │     bean = FeignClientFactoryBean                    (FeignClientsRegistrar.java:225)
  │     beanName = 接口全限定类名 FQN
  │     + FeignClientSpecification（configuration 类收集） (FeignClientsRegistrar.java:465)
  └─ 坑位：扫描范围 / name-contextId 冲突 / configuration 被主扫描（02 已讲）
```

---

## 3. 构建链：Bean → 可调用的代理

```text
FeignClientFactoryBean.getObject()          (FeignClientFactoryBean.java:455)
  └─ getTarget()                            (…:465)
       ├─ 从【子上下文】取组件：
       │    Contract      ← SpringMvcContract（注解→模板）
       │    Encoder/Decoder ← SpringEncoder / SpringDecoder 栈（FeignClientsConfiguration:104）
       │    Client        ← 默认 Client.Default；LB 场景换 FeignBlockingLoadBalancerClient（07）
       │    RequestInterceptor*（排序后）/ Logger / Retryer（NEVER_RETRY）/ ErrorDecoder
       ├─ Feign.Builder().target(HardCodedTarget(接口, name, url))     ← 经 loadBalance 包装（07 已核）
       └─ ReflectiveFeign.newInstance()
            ├─ parseHandlersByName：Contract 解析 → 每个方法一个 MethodHandler
            │   └── 同步场景 = SynchronousMethodHandler
            └─ JDK 动态代理（ReflectiveFeign.FeignInvocationHandler）
```

---

## 4. 调用链：一次请求的生命线（最长的部分）

```text
代理方法被调用
  │
  ├─① FeignInvocationHandler：按 method 查 handler（映射表来自构建链）
  │
  ├─② SynchronousMethodHandler.invoke：
  │     ❶ buildTemplateFromArgs.create(argv)
  │        └─ resolve()：Encoder 先把 @RequestBody 【编码进 body】(04 已核)
  │     ❷ 重试循环 → executeAndDecode(template)：
  │        ├─ targetRequest()：★ 按序 apply 所有拦截器（此时 body 已编码，可读可改）
  │        ├─ ★ client.execute（LB 版：先选实例 → 无实例 503 Response → 重建 URI → 真发）
  │        │     └─ 【重试在这里】RetryTemplate 包住（Retryable* Client；只针对可重试异常）
  │        └─ ★ 解码分叉：
  │              2xx → Decoder（默认链）
  │              非 2xx → ErrorDecoder（默认 FeignException）
  │
  └─③ 【外层】SentinelInvocationHandler.catch(Throwable) → fallback（若配；只触发一次，06/07）
```

**五个决策点**（面试能背出来的那种）：

| 序号 | 决策 | 依据 | 出处 |
|---|---|---|---|
| ① | 用哪个 Decoder | 子上下文 `getBean(Decoder.class)`（@Primary/唯一性） | 08 |
| ② | 拦截器顺序与次数 | `AnnotationAwareOrderComparator`；重试会再跑一遍 | 04 |
| ③ | 选哪个实例 | SCLB（supplier 链 + 算法） | LB-04/07 |
| ④ | 重不重试 | Retryer（Feign 层，默认关）/ `Retryable*`（LB 层，需 spring-retry） | LB-08 / 07 |
| ⑤ | 兜不兜底 | `fallbackFactory` + catch(Throwable) | 06 |

---

## 5. 装配链：组件从哪来

```text
Feign 子上下文（FeignClientFactory extends NamedContextFactory）
  ├─ 默认配置 FeignClientsConfiguration（构造器确认）提供：
  │    Decoder feignDecoder（@ConditionalOnMissingBean 的 SpringDecoder 栈）  (:102-105)
  │    Encoder（feignEncoder + feignEncoderPageable）                         (:111-135)
  │    Contract feignContract（SpringMvcContract）                             (:147)
  │    Retryer feignRetryer = NEVER_RETRY                                      (:162-164)
  │    Feign.Builder feignBuilder(retryer)                                     (:210)
  │    Client feignClient（按 HTTP 客户端：OkHttp/HC5/Http2 三个 FeignAutoConfiguration 变体）
  ├─ per-client configuration 类追加/覆盖（@FeignClient(configuration=…)）
  └─ 属性层：spring.cloud.openfeign.client.config.<key|default>.*（D7；键=contextId，03）
```

**装配三问的答案**：

- "默认 Decoder 是谁？"——`feignDecoder`（SpringDecoder 栈，可被覆盖，覆盖规则见 08）；
- "xhs 的拦截器/超时怎么进去的？"——子上下文 Bean（拦截器）/ 属性（超时）；
- "Sentinel 怎么换掉 Builder？"——`SentinelFeignAutoConfiguration` 提供 `Feign.Builder`（`SentinelFeign.builder()`），条件 `feign.sentinel.enabled`（06 已核）。

---

## 6. 边界与翻篇

- 本篇是**串讲**：所有节点已在 01-08 逐个核实过源码/字节码，不再重复贴；
- 更深的源码走查（类内逐行）→ lab D1-D7；更深的 feign-core 内部（`RequestTemplateFactoryResolver` 的编码细节）→ §4 的三个锚点已够用；
- 下一篇 10 收生产与面试。

---

## 7. Self-Test

1. 不看文档画出四条链（每条的起点/终点/关键类）。
2. 一次调用里，Encoder / 拦截器 / 选实例 / 重试 / Decoder / ErrorDecoder / fallback 的先后顺序？（说出"谁在谁外层"）
3. `getBean(Decoder.class)` 在子上下文里怎么决策？xhs 的 RUnpackDecoder 为什么可能被跳过？
4. `url=` 直连时，哪几个环节会被跳过？

---

## 8. 相关与衔接

- **10 生产与收口**：异常体系与排障、面试题；
- [`openfeign-lab`](../../openfeign-lab/HANDOVER.md)：[D1](../../openfeign-lab/docs/D1-FeignClientFactoryBean源码分析.md) / [D2](../../openfeign-lab/docs/D2-FeignClientsRegistrar源码分析.md) / [D3](../../openfeign-lab/docs/D3-FeignBlockingLoadBalancerClient源码分析.md) / [D5](../../openfeign-lab/docs/D5-FeignAutoConfiguration源码分析.md) / [D6](../../openfeign-lab/docs/D6-Targeter-SentinelFeign源码分析.md)；
- `docs/loadbalancer`：[04 阻塞路径](../loadbalancer/04-sclb-architecture.md) / [08 重试两口味](../loadbalancer/08-retry-sticky-hash.md)。
