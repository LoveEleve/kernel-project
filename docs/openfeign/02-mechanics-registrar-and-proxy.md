# FEIGN-02 声明式背后的机制

> 用途：接口 + 注解怎么变成"可调用对象"——**注册 → 构建 → 代理 → 调用**四条链；Contract 与 Feign 子上下文
> 对应系列：**OpenFeign 专题 · 02**（理论篇第二篇）
> 口径：Spring Cloud OpenFeign 4.1.3 / feign-core 13.3
> 素材：`openfeign-lab`（[D1 创建机制](../../openfeign-lab/docs/D1-FeignClientFactoryBean源码分析.md) / [D2 注册机制](../../openfeign-lab/docs/D2-FeignClientsRegistrar源码分析.md) / [B-02~B-05 扩展点](../../openfeign-lab/docs/B-02~B-05-自定义扩展点.md)）· 与 `docs/loadbalancer` 04（子上下文）对照
> 前置：01（全景）；后续：03 使用与配置 → 09 源码主干（本篇讲"骨架"，09 串"全链"）

---

## 0. 例子先行：`orderFeignClient.notifyPaySuccess(1L, "X")` 背后发生了什么？

先问三个问题：

```text
① 接口没有实现类——运行时调用的对象是谁？（谁"实现"了 OrderFeignClient？）
② 这行 Java 方法调用，什么时候变成了 HTTP 请求？
③ URL、参数、body 是怎么从"注解 + 实参"变出来的？
```

答案的骨架（本篇讲的就是这张图）：

```text
启动期：
  @EnableFeignClients 扫描 → 注册（FeignClientsRegistrar）
  → 构建（FeignClientFactoryBean.getObject()）：生成【JDK 动态代理】
      ↳ 期间 Contract 把每个方法的注解解析成"方法级元数据"（模板骨架）

调用期：
  orderFeignClient.notifyPaySuccess(1L, "X")
  → 代理（FeignInvocationHandler）找到该方法的 MethodHandler
  → 组装 RequestTemplate（URL/query）→ Encoder 编码 → Client 发送（★ LB 版先选实例）
  → Decoder 解码 → 返回 R<Void>
```

---

## 1. 掌握卡（3 条）

1. **启动期两条链**：**注册**（`FeignClientsRegistrar`：扫 `@FeignClient` → 注册 `FeignClientFactoryBean` 的 BeanDefinition + `FeignClientSpecification`）→ **构建**（`FeignClientFactoryBean.getObject()`：拿齐组件 → **JDK 动态代理** + 方法处理器映射表）。
2. **Feign 子上下文**：**每个 `@FeignClient` 一个子上下文**（`FeignClientFactory extends NamedContextFactory<FeignClientSpecification>`）——该客户端的 Contract/Encoder/Decoder/Client/拦截器都住在这里；与 LB 的 `LoadBalancerClientFactory` 是同款设计（`docs/loadbalancer` 04）。
3. **Contract 只在启动期工作**：注解 → `MethodMetadata`（方法级模板）在**构建时**完成；调用期不再解析注解，只做"填模板 + 编解码 + 发送"。

---

## 2. 启动期一：注册（`FeignClientsRegistrar`）

```text
@EnableFeignClients
  → @Import(FeignClientsRegistrar.class)        ← 入口在这
    → 扫描（basePackages / 默认主类所在包）里所有 @FeignClient 接口：
       ① 每个接口注册一个 FeignClientFactoryBean 的 BeanDefinition（beanName = **接口全限定类名 FQN**，源码：`BeanDefinitionHolder(…, className, …)`）
       ② 每个客户端的 configuration 类收集成 FeignClientSpecification（供子上下文使用）
```

要点与坑：

- **扫描范围**：`@EnableFeignClients(basePackages = …)` 不写就扫主启动类所在包——接口放别处会"注册不上"（调用时 Bean 不存在）；
- **name/contextId 冲突（经典坑）**：两个 `@FeignClient` 用**同一个 name 但不同 configuration** 会启动报错（"Multiple Feign Clients with the same name/configuration"）——解法：给其中一个加 `contextId`（子上下文的 key）；
- **configuration 类别被主扫描**：`@FeignClient(configuration = X.class)` 的 X 是**子上下文专属配置**；如果 X 同时被组件扫描进主上下文，它会变成"全局共享"并影响所有客户端（与 LB 的 L16 坑同款）。

---

## 3. 启动期二：构建（`FeignClientFactoryBean`）

`FactoryBean.getObject()` → 建目标对象，流程：

```text
1. 创建/获取该客户端的【Feign 子上下文】（FeignClientFactory）
2. 从子上下文拿齐组件（缺省 + 覆盖）：
     Contract     → SpringMvcContract（解析 @GetMapping/@RequestMapping…）
     Encoder      → SpringEncoder（HttpMessageConverters）
     Decoder      → SpringDecoder
     Client       → 默认 Client.Default；LB 场景被 FeignBlockingLoadBalancerClient 包装
     RequestInterceptor 集合 / Logger / Retryer / ErrorDecoder
3. Feign.Builder()（经 loadBalance 包装）.target(HardCodedTarget(接口, name, url))
     → ReflectiveFeign.newInstance() → 【JDK 动态代理】
4. parseHandlersByName：Contract 把每个方法解析成 MethodMetadata（模板）
     → 为每个方法生成 MethodHandler（同步场景 = SynchronousMethodHandler）
     → 存进"接口方法 ↔ 处理器"的映射表
```

为什么是 **JDK 动态代理**：Feign 客户端是**接口**，没有实现类——JDK 代理正是"为接口生成实现"的标准方案（feign-core 里的 `ReflectiveFeign.FeignInvocationHandler`）。

---

## 4. 调用期：一次方法调用的完整链

```text
FeignInvocationHandler.invoke(proxy, method, args)
  → 按 method 查映射表 → 找到对应的 MethodHandler
  → SynchronousMethodHandler.invoke(args)：
      ① buildTemplate + expand：方法级模板 + 实参 → 最终 URL/query
      ② Encoder：参数对象 → request body
      ③ Client.execute：发 HTTP  ★ LB 版（FeignBlockingLoadBalancerClient）在这一步先选实例
      ④ Decoder：response body → 返回类型（R<Void>）；2xx 之外走 ErrorDecoder
      ⑤ 重试：Retryer 决定"再来一次"（默认 NEVER_RETRY 的立场见 07）
```

回扣 01 的"六件样板事"：① URL 拼接 ② 参数编码 ③/④ 序列化 ⑤ 错误码→异常 ⑥ 连接/LB/重试——**①~⑤ 在 MethodHandler 里固定成型，⑥ 由 Client/Retryer 承担**（且都能替换）。

---

## 5. Feign 子上下文（与 LB 子上下文对照）

```text
FeignClientFactory extends NamedContextFactory<FeignClientSpecification>
   —— 每个 @FeignClient 一个"迷你 ApplicationContext"
```

| | LB 子上下文 | Feign 子上下文 |
|---|---|---|
| 工厂 | `LoadBalancerClientFactory` | `FeignClientFactory` |
| 隔离什么 | supplier 链 / LB 算法 / `clients.<svc>.*` | Contract/Encoder/Decoder/Client/拦截器 / `client.config.<name>.*` |
| 共同点 | **NamedContextFactory 同款**；放在子上下文里的 bean **不出现在主上下文视图** | 同左 |

**配置优先级**（03 展开，先记结论）：默认（`defaultToProperties=true`）**先装 configuration Bean，后应用属性**——`client.config.<name>.*` > `client.config.default.*`，同名设置覆盖 Bean；开关 `default-to-properties=false` 时反转。

---

## 6. 扩展点预览（B 类，进阶到 08/09 展开）

| 扩展点 | 换掉什么 | lab |
|---|---|---|
| `Contract` | 注解解析规则（支持自定义注解/老框架） | [B-03](../../openfeign-lab/docs/B-02~B-05-自定义扩展点.md) |
| `InvocationHandlerFactory` | 代理行为（AOP/埋点/自定义调度） | [B-02](../../openfeign-lab/docs/B-02~B-05-自定义扩展点.md) |
| `Encoder` / `Decoder` | 编解码格式（Protobuf/自定义） | [B-04](../../openfeign-lab/docs/B-02~B-05-自定义扩展点.md) |
| `ErrorDecoder` | 错误响应 → 异常映射 | [B-05](../../openfeign-lab/docs/B-02~B-05-自定义扩展点.md) |

---

## 7. 坑与边界

| # | 坑 | 说明 |
|---|---|---|
| 1 | name/contextId 冲突 | 同名不同 configuration 启动报错；用 `contextId` 区分 |
| 2 | 接口不在扫描范围 | `@EnableFeignClients(basePackages)` 要覆盖到 |
| 3 | configuration 类别被主扫描 | 会泄漏成"全局共享"（同 LB 的 L16 坑） |
| 4 | 子上下文 bean 不可见 | 调试时主上下文/actuator 看不到它的组件——靠日志（03） |

---

## 8. Self-Test

1. 接口没有实现类，运行时调用的是谁？它由谁、在什么阶段创建？
2. 注册与构建的两个关键类分别是什么？各产出什么？
3. Contract 在哪个阶段工作？调用期还会解析注解吗？
4. Feign 子上下文解决什么问题？默认情况下 configuration Bean 与属性谁赢？（见 03）

---

## 9. 相关与衔接

- **03 使用与配置**：注解细节 + `configuration` 写法 + 配置优先级展开 + 日志级别；
- **09 源码主干**：把本篇四条链和 D1~D7 串成一条（面试画图题）；
- [`openfeign-lab`](../../openfeign-lab/HANDOVER.md)：[D1 FeignClientFactoryBean](../../openfeign-lab/docs/D1-FeignClientFactoryBean源码分析.md) / [D2 FeignClientsRegistrar](../../openfeign-lab/docs/D2-FeignClientsRegistrar源码分析.md) / [B-02~B-05](../../openfeign-lab/docs/B-02~B-05-自定义扩展点.md)；
- `docs/loadbalancer`： [04 子上下文](../loadbalancer/04-sclb-architecture.md)（同款 NamedContextFactory 设计）。
