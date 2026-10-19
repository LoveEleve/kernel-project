# FEIGN-01 声明式调用全景

> 用途：立全景——HTTP 客户端演进、Feign 是什么/解决什么、四个核心组件、与网关/LB/注册中心的关系
> 对应系列：**OpenFeign 专题 · 01**（理论篇第一篇）
> 口径：Spring Cloud OpenFeign **4.1.3**（Cloud 2023.0.3）/ feign-core 13.3 / Boot 3.2.5；对齐 xhs-project
> 素材：`openfeign-lab`（[HANDOVER](../../openfeign-lab/HANDOVER.md)，A-09 RestClient 对照）· xhs-project（`OrderFeignClient` 等真实接口）
> 前置：无；后续：02 声明式背后的机制 → 03 使用与配置 → …（见 [README](README.md)）

---

## 0. 例子先行：同一个调用，两种写法

目标：调订单服务的 `POST /api/order/pay-success?orderId=1&tradeNo=X`。

**写法 A：RestTemplate（命令式）**

```java
// 手写：URL 拼接 + 参数编码 + 错误处理 + 反序列化……
String url = "http://my-xhs-order/api/order/pay-success?orderId={orderId}&tradeNo={tradeNo}";
Map<String, Object> vars = Map.of("orderId", orderId, "tradeNo", tradeNo);
ResponseEntity<R> resp = restTemplate.postForEntity(url, null, R.class, vars);
if (!resp.getStatusCode().is2xxSuccessful()) {
    // 错误处理：自己写
}
R body = resp.getBody();
```

**写法 B：Feign（声明式）**——xhs 生产真实接口（节选）：

```java
@FeignClient(name = "my-xhs-order",
        fallbackFactory = OrderFeignFallbackFactory.class,
        configuration = InternalCallFeignConfig.class)
public interface OrderFeignClient {

    @PostMapping("/api/order/pay-success")
    R<Void> notifyPaySuccess(@RequestParam("orderId") Long orderId,
                             @RequestParam("tradeNo") String tradeNo);
}
```

```java
// 调用处：像本地方法一样
orderFeignClient.notifyPaySuccess(orderId, tradeNo);
```

一句话：**写法 A 的"协议细节"散在每次调用里；写法 B 把它们收敛成"接口定义 + 配置"**——URL/参数/序列化/错误/**LB**/重试全交给框架代理。

---

## 1. 掌握卡（3 条）

1. **声明式 = 接口 + 注解 = 客户端**：Feign 把 HTTP 调用的样板（URL 拼接、参数编码、序列化、错误码处理、连接、LB、重试）收敛进框架。
2. **四个核心组件**（+ 横切件）：**Contract**（注解 → 请求模板）/ **Encoder**（对象 → body）/ **Decoder**（body → 对象）/ **Client**（真正发 HTTP）；横切：RequestInterceptor / ErrorDecoder / Logger / Retryer。
3. **定位**：Feign 是"**东西向**"（服务间调用，**不过网关**）；在 Spring Cloud 里它的 Client 被 **`FeignBlockingLoadBalancerClient`** 换成 LB 版——这就是 LB 系列 04 讲的"阻塞路径"。

---

## 2. HTTP 客户端的演进（为什么要 Feign）

| 方式 | 风格 | 出身 | 适合 |
|---|---|---|---|
| `RestTemplate` | 命令式模板 | Spring 3.0+ | 调用少、简单场景；**样板代码多** |
| **Feign / OpenFeign** | **声明式** | Netflix → OpenFeign 社区（Spring Cloud OpenFeign 集成） | **服务间调用主力**：契约化 + 生态（LB/熔断/配置） |
| `WebClient` | 响应式 | Spring 5+ | 高并发/流式（网关侧） |
| `RestClient` | 现代同步 fluent | Spring 6.1（Boot 3.2） | 新项目"少依赖"替代 RestTemplate（与 Feign 的对比 → 08） |

**RestTemplate 的六件样板事**（Feign 全包了）：

```text
① URL / 路径拼接       ② query / 表单参数编码
③ 请求体序列化         ④ 响应反序列化
⑤ 错误码 → 异常        ⑥ 连接池 / 超时 / LB / 重试 / 日志
```

"声明式"的动机就是把①~⑥从"每次调用"搬进"框架 + 配置"。

---

## 3. Feign 是什么：一次调用的流程（先看全景）

```text
启动期：
  @EnableFeignClients 扫描 @FeignClient 接口
   → 注册（FeignClientsRegistrar）
   → 构建（FeignClientFactoryBean：Contract 解析注解 + 生成 JDK 动态代理）

调用期（orderFeignClient.notifyPaySuccess(...)）：
  代理（ReflectiveFeign.FeignInvocationHandler）
   → 方法处理器（SynchronousMethodHandler）
   → RequestTemplate：方法+参数 → HTTP 请求模板（由 Contract 解析出来）
   → ★【Client】发 HTTP —— 在 Spring Cloud 里这一步被换成 LB 版：
        先选实例（SCLB），再发请求（04/07 的阻塞路径）
   → Decoder 把响应体变成 R<Void>
   → 出错时：ErrorDecoder / Retryer / fallbackFactory 介入
```

（启动期与调用期的每一步，02 和 09 会拆开讲。）

**四个核心组件的"官方实现 vs Spring Cloud 替换"**：

| 组件 | 职责 | Spring Cloud 版 |
|---|---|---|
| **Contract** | 注解 → 请求模板 | `SpringMvcContract`（支持 `@GetMapping`/`@RequestMapping` 等 MVC 注解） |
| **Encoder** | 入参对象 → 请求体 | `SpringEncoder`（复用 `HttpMessageConverters`） |
| **Decoder** | 响应体 → 返回对象 | `SpringDecoder` |
| **Client** | 真正发 HTTP | 默认 `Client.Default`；可换 OkHttp / HttpClient5（05）；**Spring Cloud 场景被 `FeignBlockingLoadBalancerClient` 包装（先 LB 再发）** |
| （横切）RequestInterceptor | 改请求：认证 / traceId / 内部令牌 / 灰度标签 | 04 |
| （横切）ErrorDecoder | 错误响应 → 异常 | 08 |
| （横切）Logger / Retryer | 日志 / 重试 | 03 / 07 |

---

## 4. 三个澄清（和周围系统的关系）

1. **与网关：两个方向**。网关 = **南北向**入口（外部/前端 → 内网，走 DNS/SLB 找到网关）；Feign = **东西向**（服务 ↔ 服务，**不过网关**）。xhs 的 `my-xhs-order` 等调用都是内网直连。
2. **与 LB**：Feign 自己**不选实例**——`FeignBlockingLoadBalancerClient` 把 `http://my-xhs-order` 里的**服务名**交给 SCLB 选（LB 系列 04 讲的"五种介入方式"之一，阻塞路径）。
3. **与注册中心**：`http://服务名` 是**寻址约定**（不是真 DNS）——由 LB + 注册中心解释成真实 `IP:端口`。

---

## 5. xhs 生产锚点（先看真实长什么样）

| 生产件 | 是什么 | 详见 |
|---|---|---|
| `OrderFeignClient` / `UserFeignClient` … | 真实 `@FeignClient` 接口：`fallbackFactory` + `configuration` 是标配；统一返回 `R<T>` | 本课 §0 |
| `FeignInternalCallInterceptor` | 拦截器：打**内部调用令牌**（防外部伪造） | 04 |
| `FeignTraceInterceptorConfig` | 拦截器：**traceId 透传** | 04（交叉 `tracing-lab`） |
| 31 个 `FallbackFactory` | 按服务组织的降级工厂 | 06 |
| `FeignSafeConfig` | **全局 `Retryer.NEVER_RETRY`**（非幂等不重试） | 07 |
| `spring.cloud.openfeign.client.config.default.*` | 超时/重试/拦截器/日志级别等默认配置（**4.x 新前缀**；注意：`feign.client.*` 旧前缀在 4.1.3 **已不绑定**——xhs 个别 javadoc 还写着旧写法，实际 yml 用的是新前缀） | 05 |
| `feign.sentinel.enabled: true` | 启用 Sentinel 作 Feign 熔断（**这个仍是 `feign.*` 前缀**——它是 SCA 的属性，别和上面的前缀迁移混淆） | 06 |

---

## 6. 边界与翻篇

- 本篇只立全景；**机制** 02/09、**使用** 03-08、**生产** 10；
- 不重复：LB 细节（`docs/loadbalancer`）、Sentinel 机制（L45 / `sentinel-lab`）、网关（`docs/gateway`）。

---

## 7. Self-Test

1. 声明式相对 RestTemplate，具体"省掉"了哪几件事？
2. Feign 的四个核心组件分别是什么？Spring Cloud 各替换成什么？
3. Feign 与网关、与 LB 的关系各一句话。
4. `@FeignClient(name = "my-xhs-order")` 里的 `name` 在运行时是怎么被解释成真实地址的？

---

## 8. 相关与衔接

- **02 声明式背后的机制**：注册 → 构建 → 代理 → MethodHandler（含 Feign 子上下文）；
- **03 使用与配置** → **04 拦截器** → **05 超时与客户端** → **06 容错** → **07 LB 与重试**；
- [`openfeign-lab`](../../openfeign-lab/HANDOVER.md)：[A-09 RestClient 对比](../../openfeign-lab/docs/A-09-RestClient对比.md) / [A-01 声明式调用与配置](../../openfeign-lab/docs/A-01-声明式调用与配置.md)；
- `docs/loadbalancer`：[04 阻塞路径](../loadbalancer/04-sclb-architecture.md) / [07 重试与摘除](../loadbalancer/07-instance-churn.md)。
