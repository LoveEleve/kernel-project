# Lesson 15: Custom RoutePredicateFactory — 自己造一个「匹配器」

> 课程索引：`../gateway-学习清单.md` → Stage 4 · Lesson 15
> 版本口径：xhs-demo 实际运行的 SCG **4.1.5**（本课代码已在 `demo-gateway` 跑通）
> 前置：L04（谓词概念：14 种、多个谓词是 **AND**）· L14（自定义 Filter 工厂，**结构几乎一样**）
> **提问方式**：直接说编号，如「15.3 没懂」

---

## 15.0 掌握卡（只要记这些）

| # | 要掌握的 | 一句话 |
|---|---|---|
| **①** | **三段式与 Filter 工厂一样** | `Factory`（造）→ `Config`（收参数）→ **`Predicate<ServerWebExchange>`**（判真假） |
| **②** | **谓词管"匹配"，过滤器管"加工"** | 谓词返回 `false` → **这条路由不匹配**（请求可能 404 或落到别的路由） |
| **③** | **命名/注册规则同 L14** | 类名去 `RoutePredicateFactory` 后缀 = yml 名；必须是 Spring Bean；`shortcutFieldOrder()` 必写 |
| **＋** | **⚠️ 谓词里不能做阻塞/远程调用** | 谓词在**匹配阶段**执行（跑在 EventLoop 上，L11）—— 匹配要快、要纯 |

---

## 15.1 例子先行：目标是什么

**目标**：写一个谓词，**只有带特定请求头的请求才匹配这条路由**：

```yaml
- id: demo-predicate-lab
  uri: http://localhost:8081
  predicates:
    - Path=/predicate-lab/**
    - DemoHeader=X-Demo-Env, gray       # ★ 我们自己造的谓词（两个参数：头名、头值）
  filters:
    - StripPrefix=1
```

**验证**：

```bash
# 带匹配的头 → 路由匹配 → 200
curl --noproxy '*' -s -o /dev/null -w '%{http_code}\n' -H 'X-Demo-Env: gray' http://localhost:8080/predicate-lab/hello
# → 200

# 不带 → 路由不匹配 → 404（网关找不到匹配的路由）
curl --noproxy '*' -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/predicate-lab/hello
# → 404
```

> **对比 L14**：过滤器是"匹配**之后**怎么加工"；谓词是"**能不能**匹配"。前者改请求，后者**决定路由**。

---

## 15.2 要掌握的 ①：三段式（和 L14 一模一样）

```
                    yml 的 `DemoHeader=A, B`
                              │
                              ▼  ConfigurationService 绑定（L20）
                       ┌──────────────┐
                       │   Config     │  收参数（A → headerName, B → headerValue）
                       └──────────────┘
                              │
                              ▼  apply(config)
                       ┌──────────────────────┐
                       │ Predicate<Exchange>  │  ★ 注意：返回的是"判真假"的谓词
                       └──────────────────────┘
                              │
                              ▼
                    exchange -> true / false
```

| 段 | L14（Filter 工厂） | **L15（Predicate 工厂）** |
|---|---|---|
| 基类 | `AbstractGatewayFilterFactory<C>` | **`AbstractRoutePredicateFactory<C>`** |
| 后缀 | `GatewayFilterFactory` | **`RoutePredicateFactory`** |
| `apply()` 返回 | `GatewayFilter` | **`Predicate<ServerWebExchange>`** |
| 干什么 | 改请求/响应、放行/短路 | **判"这条路由匹配吗"** |

---

## 15.3 要掌握的 ②：谓词管匹配，过滤器管加工

| | **谓词（Predicate）** | **过滤器（Filter）** |
|---|---|---|
| 时机 | **匹配阶段**（`RoutePredicateHandlerMapping.lookupRoute`，L23） | **执行阶段**（`FilteringWebHandler` 的链，L25） |
| 输出 | `boolean` | `Mono<Void>`（放行 / 短路） |
| 多个时 | **AND**（全部为 true 才匹配，L04） | **按 order 串成一条链**（L21） |
| 返回 false 的后果 | 这条路由**不匹配**（继续找下一条 → 可能 404） | — |
| 典型用途 | 按路径/方法/头/时间/权重…选路由 | 加头、改写路径、限流、鉴权 |

> **一句话**：**谓词决定"走哪条路"，过滤器决定"路上做什么"。**

**⚠️ 关键纪律：谓词里不能做阻塞或远程调用**
谓词在**匹配阶段**执行，跑在 **EventLoop 线程**上（L11）——
每条进来的请求都要跑一遍所有候选路由的谓词，**慢一点就是全站慢**。
（要"按用户查配置再决定"？那是**过滤器**或**上游服务**的活，不是谓词的。）

---

## 15.4 要掌握的 ③：完整代码（要能默写）

```java
package com.myxhs.demo.gateway.predicate;

@Component                                                    // ① Spring Bean
public class DemoHeaderRoutePredicateFactory                  // ② 类名去后缀 = yml 名（DemoHeader）
        extends AbstractRoutePredicateFactory<DemoHeaderRoutePredicateFactory.Config> {

    public DemoHeaderRoutePredicateFactory() {
        super(Config.class);
    }

    @Override
    public List<String> shortcutFieldOrder() {                // ③ 短写：DemoHeader=X-Demo-Env, gray
        return List.of("headerName", "headerValue");
    }

    @Override
    public Predicate<ServerWebExchange> apply(Config config) { // ④ 返回谓词
        return exchange -> {
            String actual = exchange.getRequest().getHeaders().getFirst(config.getHeaderName());
            return config.getHeaderValue().equals(actual);
        };
    }

    public static class Config {
        private String headerName;
        private String headerValue;
        // getter / setter 省略
    }
}
```

**和 L14 的代码对照**（唯一实质差别就是"返回什么 + 后缀"）：

| 位置 | L14 Filter 工厂 | L15 Predicate 工厂 |
|---|---|---|
| `extends` | `AbstractGatewayFilterFactory<Config>` | `AbstractRoutePredicateFactory<Config>` |
| `apply()` 返回 | `GatewayFilter`（`(exchange, chain) -> ...`） | `Predicate<ServerWebExchange>`（`exchange -> true/false`） |

---

## 15.5 实测（在 `demo-gateway` 已跑通）

**① 代码**：`demo-gateway/src/main/java/com/myxhs/demo/gateway/predicate/DemoHeaderRoutePredicateFactory.java`
**② yml**：新增路由 `demo-predicate-lab`（见 15.1）
**③ 验证**：

```bash
# 带匹配的头 → 200
curl -s --noproxy '*' -o /dev/null -w 'HTTP=%{http_code}\n' \
     -H 'X-Demo-Env: gray' http://localhost:8080/predicate-lab/hello
# → HTTP=200

# 不带 → 404（没有任何路由匹配）
curl -s --noproxy '*' -o /dev/null -w 'HTTP=%{http_code}\n' \
     http://localhost:8080/predicate-lab/hello
# → HTTP=404
```

**完整实测（4 个用例）**：

| # | 请求 | 结果 | 说明 |
|---|---|---|---|
| ① | 带 `X-Demo-Env: gray` | **200** | 两个谓词都通过 → 路由匹配 |
| ② | 不带该头 | **404** | `Path` ✅ 但 `DemoHeader` ❌ → **AND 为 false** |
| ③ | 头值 `prod`（不相等） | **404** | 同上（值不匹配） |
| ④ | 对照：`/provider/**` | **200** | 新谓词只影响**配了它的那条路由** |

---

## 15.6 顺带：内置谓词工厂怎么写的（源码对照）

**内置 `HeaderRoutePredicateFactory`** 的骨架：

```java
public class HeaderRoutePredicateFactory extends AbstractRoutePredicateFactory<HeaderRoutePredicateFactory.Config> {

    public static final String HEADER_KEY = "header";
    public static final String REGEXP_KEY = "regexp";

    @Override
    public List<String> shortcutFieldOrder() {
        return Arrays.asList(HEADER_KEY, REGEXP_KEY);      // ← 同样是 [名, 值]
    }

    @Override
    public Predicate<ServerWebExchange> apply(Config config) {
        Pattern pattern = StringUtils.hasText(config.regexp) ? Pattern.compile(config.regexp) : null;
        return exchange -> {
            List<String> values = exchange.getRequest().getHeaders().getValuesAsList(config.header);
            if (values.isEmpty()) return false;
            if (pattern == null) return true;               // 只检查"有没有这个头"
            return values.stream().anyMatch(pattern.asMatchPredicate());   // 正则匹配
        };
    }
}
```

**差别**：内置的 `Header` 只检查"头存在"（或按正则匹配），**不支持"头值相等"**；我们写的是"**头值相等**"。
→ 这正是"为什么要写自定义谓词"的典型理由：**内置的不满足你的规则**。

---

## 15.7 掌握边界

| 内容 | 判断 |
|---|---|
| 三段式（与 L14 的对应关系） | ✅ **必须记**（能默写） |
| 谓词管匹配 / 过滤器管加工 | ✅ **必须记** |
| **谓词里不能阻塞/远程调用** | ✅ **必须记**（匹配阶段 + EventLoop） |
| 多个谓词是 **AND** | ✅ 记（L04 已学，本课实测印证） |
| 命名/注册规则、`shortcutFieldOrder` | ✅ 记（与 L14 同） |
| 内置 14 种谓词的清单 | ⏭️ 用时查（L04 有表） |
| 谓词匹配的源码（`lookupRoute` / `AsyncPredicate`） | ⏭️ **L23 / L24** |

---

## 15.8 Self-Test

1. 自定义谓词工厂继承哪个基类？`apply()` 返回什么？
2. 谓词返回 `false` 会发生什么？
3. 类名叫 `FooRoutePredicateFactory`，yml 里写什么？
4. **为什么谓词里绝对不能做远程调用/阻塞？**
5. 内置的 `Header` 谓词和我们写的 `DemoHeader` 有什么差别？（提示：为什么需要自定义）

### 15.8.1 参考答案

<details open>

**1. 基类与返回**
`AbstractRoutePredicateFactory<Config>`；`apply(Config)` 返回 **`Predicate<ServerWebExchange>`**。

**2. 返回 false**
**这条路由不匹配**：匹配器继续找下一条候选路由；如果所有路由都不匹配 → **404**。

**3. yml 名**
**`Foo`**（`NameUtils.normalizeFilterFactoryName` 去掉 `RoutePredicateFactory` 后缀）—— 与 L14 同规则。

**4. 为什么不能阻塞/远程调用**
谓词在**匹配阶段**执行、跑在 **EventLoop 线程**上；**每个请求都要跑一遍所有候选路由的谓词**。
在这里阻塞 → 卡住整个 EventLoop → **全站变慢**（L11 的线程模型 + L35 的阻塞纪律）。

**5. 与内置 `Header` 的差别**
内置 `Header`：只检查"**头存在**"，或按 **regexp** 匹配头值；
`DemoHeader`（我们写的）：检查"**头值相等**"（`equals`）。
→ 内置不满足需求时，才需要自定义 —— **这就是自定义谓词的存在理由**。

</details>

---

## 15.9 Open Questions

> 用法：直接在这里追加，或对话里说编号（如「15.4 没懂」）。

| # | 疑问 | 状态 |
|---|---|---|
| | | |
