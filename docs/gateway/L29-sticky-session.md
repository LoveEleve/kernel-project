# Lesson 29: Sticky Session — `LoadBalancerServiceInstanceCookieFilter`

> 课程索引：`../gateway-学习清单.md` → Stage 5 · Lesson 29（★v5.4 新增）
> 前置：**L27 §27.3**（LB filter 把选中实例放进 `GATEWAY_LOADBALANCER_RESPONSE_ATTR`）· **L16**（SCLB 自定义）
> 口径：🔴 会话保持怎么用 **cookie** 把同一客户端钉到同一实例（"官方专节"在 **Spring Cloud Commons / LoadBalancer 文档**，见 29.3）

---

## 29.0 掌握卡（只要记这些）

| # | 要掌握的 | 一句话 |
|---|---|---|
| **①** | **两个半边** | **写**：SCG 过滤器把"选中实例 id"追加到**转发请求**的 cookie；**读**：SCLB 的 sticky supplier 按**请求 cookie** 钉住实例 |
| **②** | **默认关** | `spring.cloud.loadbalancer.sticky-session.add-service-instance-cookie` **默认 false**（不设 = 过滤器空转） |
| **③** | **order = 10151** | `LOAD_BALANCER_CLIENT_FILTER_ORDER(10150) + 1` —— 必须**紧跟 LB 过滤器**（要它算出的实例） |
| **④** | **cookie 约定** | 名 `sc-lb-instance-id`（可配 `instance-id-cookie-name`）；值 = `ServiceInstance.getInstanceId()` |

---

## 29.1 例子先行：没有 sticky，会话为什么会丢？

```
用户会话存在实例 X1 的本地内存（有状态服务）
  ┌──────────┐   ① 请求1 → LB 轮询选中 X1   → 会话建立 ✓   （X1 内存里有会话）
  │  client  │   ② 请求2 → LB 轮询选中 X2   → 会话丢失 ✗   （X2 内存里没有）
  └──────────┘   ③ 请求3 → X1 → X2 → X1 → X2 …… 随机漂移
```

sticky 的目标：**同一个客户端**永远（尽量）落在**同一个实例**：

```
① 请求1：LB 选 X1 → 网关把 "sc-lb-instance-id=X1" 写进请求 cookie
② 请求2：带 cookie 进来 → LB 看到 cookie，直接选 X1（不再轮询）
```

---

## 29.2 源码：`LoadBalancerServiceInstanceCookieFilter` 全解

```java
public class LoadBalancerServiceInstanceCookieFilter implements GlobalFilter, Ordered {

    private LoadBalancerProperties loadBalancerProperties;
    private ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerClientFactory;

    LoadBalancerServiceInstanceCookieFilter(LoadBalancerProperties loadBalancerProperties) {   // 测试/静态配置用
        this.loadBalancerProperties = loadBalancerProperties;
    }
    public LoadBalancerServiceInstanceCookieFilter(                                                     // 装配用
            ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerClientFactory) {
        this.loadBalancerClientFactory = loadBalancerClientFactory;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Response<ServiceInstance> serviceInstanceResponse =
                exchange.getAttribute(GATEWAY_LOADBALANCER_RESPONSE_ATTR);          // ★ L27 第②步的产物
        if (serviceInstanceResponse == null || !serviceInstanceResponse.hasServer()) {
            return chain.filter(exchange);                                          // 没走 LB / 没选中 → 放行
        }
        LoadBalancerProperties properties = loadBalancerClientFactory != null
                ? loadBalancerClientFactory.getProperties(serviceInstanceResponse.getServer().getServiceId())
                : loadBalancerProperties;                                           // 按服务维度取属性
        if (!properties.getStickySession().isAddServiceInstanceCookie()) {
            return chain.filter(exchange);                                          // ★ 默认 false → 什么都不做
        }
        String instanceIdCookieName = properties.getStickySession().getInstanceIdCookieName();
        if (!StringUtils.hasText(instanceIdCookieName)) {
            return chain.filter(exchange);                                          // cookie 名空 → 放行
        }
        ServerWebExchange newExchange = exchange.mutate().request(exchange.getRequest().mutate().headers(headers -> {
            List<String> cookieHeaders = new ArrayList<>(headers.getOrEmpty(HttpHeaders.COOKIE));
            String serviceInstanceCookie = new HttpCookie(instanceIdCookieName,
                    serviceInstanceResponse.getServer().getInstanceId())            // ★ 值 = 实例 id
                .toString();                                                        // "sc-lb-instance-id=X1"
            cookieHeaders.add(serviceInstanceCookie);                               // ★ 追加（保留已有 cookie）
            headers.put(HttpHeaders.COOKIE, cookieHeaders);
        }).build()).build();
        return chain.filter(newExchange);
    }

    @Override
    public int getOrder() {
        return LOAD_BALANCER_CLIENT_FILTER_ORDER + 1;                               // ★ 10150 + 1 = 10151
    }
}
```

**四点读法**：
1. **数据来源**：读 `GATEWAY_LOADBALANCER_RESPONSE_ATTR`（L27 `ReactiveLoadBalancerClientFilter` 写的"选中的实例"）→ 所以 order 必须是 **10151**（紧跟在 10150 后面）
2. **默认空转**：`addServiceInstanceCookie` 默认 **false**（SCLB `LoadBalancerProperties.StickySession:192`）→ 不配置就"什么都 不做"
3. **动作**：**追加**一条请求 cookie（不覆盖已有 cookie）——测试证据：`["testCookieName=testCookieValue", "sc-lb-instance-id=test-01"]`（`LoadBalancerServiceInstanceCookieFilterTests:87-88`）
4. **值**：`ServiceInstance.getInstanceId()`（不是 host/port）——注册中心实例的 ID

**装配**（`GatewayReactiveLoadBalancerClientAutoConfiguration`）：需要 `ReactiveLoadBalancerClientFilter` + `LoadBalancerClientFactory` 两个 bean 存在（即 SCLB 在 classpath）；`@ConditionalOnEnabledGlobalFilter` 可关。

---

## 29.3 另一半边：谁"读"这个 cookie？（SCLB 的 sticky supplier）

写出去的 cookie 要有"读者"才有意义。读者在 **SCLB**（Spring Cloud Commons）：

```java
// RequestBasedStickySessionServiceInstanceListSupplier（spring-cloud-loadbalancer）
public Flux<List<ServiceInstance>> get(Request request) {
    String instanceIdCookieName = properties.getStickySession().getInstanceIdCookieName();
    Object context = request.getContext();
    if ((context instanceof RequestDataContext)) {
        MultiValueMap<String, String> cookies = ((RequestDataContext) context).getClientRequest().getCookies();
        String cookie = cookies.getFirst(instanceIdCookieName);                    // ★ 读请求 cookie
        if (cookie != null) {
            return delegate.get(request).map(serviceInstances -> selectInstance(serviceInstances, cookie));
        }                                                                          // ★ 钉到该实例
    }
    return delegate.get(request);                                                  // 没 cookie → 全部实例（回落）
}
// selectInstance：遍历实例列表，instanceId == cookie → 只返回这一个
```

**完整闭环**（官方文档口径，原文两段合一）：

- **读侧**：把 `ServiceInstanceListSupplier` 配成 `RequestBasedStickySessionServiceInstanceListSupplier`（`spring.cloud.loadbalancer.configurations=request-based-sticky-session` 或自定义 bean）→ LB 会**优先**请求 cookie 里指定的实例
- **写侧**：文档原话——"it is useful to have the selected service instance (**which can be different from the one in the original request cookie** if that one is not available) to be **updated before sending the request forward**. To do that, set `spring.cloud.loadbalancer.sticky-session.add-service-instance-cookie` to `true`"
  → SCG 侧就是这个 `LoadBalancerServiceInstanceCookieFilter`（SCLB 侧对应 `LoadBalancerServiceInstanceCookieTransformer`，给 `@LoadBalanced` 客户端用）

**配置表**：

| 属性 | 默认 | 作用 |
|---|---|---|
| `spring.cloud.loadbalancer.sticky-session.add-service-instance-cookie` | **false** | 写开关（SCG 过滤器/SCLB transformer 是否动手） |
| `spring.cloud.loadbalancer.instance-id-cookie-name` | `sc-lb-instance-id` | cookie 名 |
| `spring.cloud.loadbalancer.configurations=request-based-sticky-session` | 未启用 | 读侧：启用 sticky supplier |

> ⚠️ **口径说明**：学习清单说"官方文档有专节"——实查后，**专节在 Spring Cloud Commons 文档**（"Request-based Sticky Session for LoadBalancer"），本地 4.1.5 仓库的 SCG 文档**没有**这一节（SCG 侧靠这份笔记）。

---

## 29.4 边界与坑（什么时候该开、什么时候别开）

| 场景 | 判断 |
|---|---|
| 下游服务**有状态**（本地会话/内存缓存与实例绑定） | ✅ 需要 sticky（现在多数微服务无状态 + Redis 会话，不需要） |
| 无状态服务 | ❌ 别开：强制钉实例会**降低均衡性**（热门实例压力大） |
| 滚动发布 | ⚠️ 实例下线 → cookie 指向的实例不在列表 → supplier **回落全量**（不会报错，但会漂移）；配合"优雅下线"减少抖动 |
| 与 LB 缓存（35s，L16） | ⚠️ 实例列表缓存未过期时可能含已下线实例 → sticky 钉到死实例；SCLB 的重试/健康检查兜底 |
| xhs 网关 | 网关自身无状态；会话类状态在 Redis → 网关层**不需要** sticky（若个别有状态服务需要，按 service 维度单独开） |

---

## 29.5 掌握边界（值不值得深挖）

| 内容 | 口径 |
|---|---|
| SCLB `selectInstance` 的完整实现 | ❌ 上面已给核心（找不到就回落） |
| cookie 在浏览器侧的建立闭环（谁 Set-Cookie） | ⚪ 文档未明说应用场景口径；我们笔记按"多跳/服务间 + 客户端携带"理解（见 OQ） |
| **两个半边（写/读）+ order 10151** | ✅ **本课唯一 🔴** |
| **默认关闭 + 三个配置属性** | ✅ 要会（否则你会以为它默认生效） |

---

## 29.6 Self-Test

1. sticky 的"写"和"读"分别由谁做、各读哪些数据？
2. SCG 过滤器的 order 为什么是 `10150 + 1`？它依赖上一步留下的什么属性？
3. 不配置开关时它会做什么？（默认值是多少）
4. cookie 名和值的来源？已有 cookie 会被覆盖吗？（测试怎么断言）
5. 钉住的实例被下线后会怎样？为什么这不是灾难？
6. 什么服务才需要 sticky？为什么无状态服务最好别开？

---

## 29.7 Open Questions

- [ ] 浏览器直连场景下 cookie 的"首跳建立"（谁给客户端 Set-Cookie / 谁第一次种下 `sc-lb-instance-id`）—— 官方文档未展开，未实测。
- [ ] `addServiceInstanceCookie=true` 时，SCG 过滤器与 SCLB `LoadBalancerServiceInstanceCookieTransformer` 是否会**双重添加** cookie —— 未验证（取决于调用路径）。
- [ ] sticky supplier 与 SCLB 健康检查（`health-check` supplier）叠加时的优先级顺序 —— 未细究。
