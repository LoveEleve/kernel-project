# Lesson 14: Custom GatewayFilterFactory — 自己造一个「能配到 yml」的过滤器

> 课程索引：`../gateway-学习清单.md` → Stage 4 · Lesson 14（**★分水岭：从"用现成的"到"自己造"**）
> 版本口径：xhs-demo 实际运行的 SCG **4.1.5**（本课代码已在 `demo-gateway` 跑通）
> 前置：L05（过滤器概念）· L13（Custom GlobalFilter：`exchange` / `chain` / attributes）
> **提问方式**：直接说编号，如「14.2 没懂」

---

## 14.0 掌握卡（只要记这些）

```java
@Component                                        // ① 必须是 Spring Bean
public class DemoHeaderGatewayFilterFactory       // ② 类名 = yml 名 + "GatewayFilterFactory"
        extends AbstractGatewayFilterFactory<DemoHeaderGatewayFilterFactory.Config> {

    public DemoHeaderGatewayFilterFactory() { super(Config.class); }   // ③ 告诉基类 Config 类型

    @Override
    public List<String> shortcutFieldOrder() {                         // ④ 短写 yml 靠它：字段顺序
        return List.of("name", "value");
    }

    @Override
    public GatewayFilter apply(Config config) {                        // ⑤ 返回过滤器（闭包捕获 config）
        return (exchange, chain) -> { ... };
    }

    public static class Config { private String name; private String value; ... }  // ⑥ 配置类
}
```

| # | 要掌握的 | 一句话 |
|---|---|---|
| **①** | **三段式** | `Factory`（造）→ `Config`（收参数）→ `GatewayFilter`（干活） |
| **②** | **名字规则** | 类名去掉 `GatewayFilterFactory` = **yml 里的名字**（`DemoHeaderGatewayFilterFactory` → `DemoHeader`） |
| **③** | **`shortcutFieldOrder()` 必写** | 不写 → 短写语法 `DemoHeader=a,b` **绑不上字段**（只有完整 Map 写法能用） |
| **＋** | **和 `GlobalFilter` 怎么选** | **只作用于某些路由** → Factory；**所有路由都要** → GlobalFilter |

---

## 14.1 例子先行：目标是什么

**目标**：在 yml 里写一行，就给下游请求加一个头：

```yaml
- id: demo-provider
  uri: http://localhost:8081
  predicates:
    - Path=/provider/**
  filters:
    - StripPrefix=1
    - DemoHeader=X-Demo-Factory, hello-from-my-factory   # ★ 我们自己造的过滤器
```

**验证**（下游 `demo-provider` 的 echo 端点会把收到的请求头原样返回）：

```bash
curl --noproxy '*' -s "http://localhost:8080/provider/hello" | grep -o '"x-demo-factory":"[^"]*"'
# → "x-demo-factory":"hello-from-my-factory"     ✅ 下游真的收到了
```

**问题**：怎么让 `DemoHeader=X-Demo-Factory, hello-from-my-factory` 这一行生效？
→ 需要一个名为 `DemoHeader` 的 **`GatewayFilterFactory`**。

---

## 14.2 要掌握的 ①：三段式（Factory → Config → GatewayFilter）

```
                     yml 的 `DemoHeader=A, B`
                              │
                              ▼  ① ConfigurationService 绑定（L20 详讲）
                       ┌──────────────┐
                       │   Config     │   ② 收参数（A → name, B → value）
                       └──────────────┘
                              │
                              ▼  ③ apply(config)
                       ┌──────────────┐
                       │ GatewayFilter│   ④ 真正干活（拿到 exchange + chain）
                       └──────────────┘
                              │
                              ▼
                    (exchange, chain) -> chain.filter(...)
```

| 段 | 角色 | 代码元素 |
|---|---|---|
| **① Factory** | **造**过滤器 | extends `AbstractGatewayFilterFactory<Config>` |
| **② Config** | **收**参数 | 一个普通 POJO（字段 + getter/setter） |
| **③ GatewayFilter** | **干**活 | `apply(Config)` 返回的 lambda（`filter(exchange, chain)`） |

---

## 14.3 要掌握的 ②：名字规则（类名 ↔ yml 名）

```java
// NameUtils.normalizeFilterFactoryName（源码）
clazz.getSimpleName().replace("GatewayFilterFactory", "")
```

| 类名 | yml 里的名字 |
|---|---|
| `DemoHeaderGatewayFilterFactory` | **`DemoHeader`** |
| `StripPrefixGatewayFilterFactory` | **`StripPrefix`** |
| `AddRequestHeaderGatewayFilterFactory` | `AddRequestHeader` |

> ⚠️ **两个前提**：
> 1. 类必须**注册成 Spring Bean**（`@Component` 或 `@Bean`）——否则框架收集不到你的工厂
> 2. **不用**处理命名转换的大小写：`normalizeFilterFactoryName` 只去后缀，其余照搬

---

## 14.4 要掌握的 ③：完整代码（要能默写）

```java
package com.myxhs.demo.gateway.filter;

@Component
public class DemoHeaderGatewayFilterFactory
        extends AbstractGatewayFilterFactory<DemoHeaderGatewayFilterFactory.Config> {

    public DemoHeaderGatewayFilterFactory() {
        super(Config.class);              // ★ 必须：把 Config 类型告诉基类
    }

    /** ★ 短写 yml 靠它：DemoHeader=A, B → name=A, value=B */
    @Override
    public List<String> shortcutFieldOrder() {
        return List.of("name", "value");
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            // pre 阶段：改请求头（注意 mutate + 用新对象）
            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .header(config.getName(), config.getValue())
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        };
    }

    /** 配置类：字段名要与 shortcutFieldOrder 里的名字一致 */
    public static class Config {
        private String name;
        private String value;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }
}
```

**⚠️ `shortcutFieldOrder()` 为什么必写**（**破坏性实测结果**）：

| 写法 | 重写了 `shortcutFieldOrder()` | 没重写（改成 `List.of()`） |
|---|---|---|
| 短写：`DemoHeader=name,value` | ✅ 正常（下游收到头） | ❌ **HTTP 500** |
| 完整：`DemoHeader: {name:..., value:...}` | ✅ | ✅ |

**没重写时的精确症状**（实测三步）：

| 观察点 | 结果 |
|---|---|
| ① 配置态（`/actuator/gateway/routedefinitions`） | 参数**仍是占位 key**：`{'name': 'DemoHeader', 'args': {'_genkey_0': 'X-Demo-Factory', '_genkey_1': '...'}}` —— **没被映射成 name/value** |
| ② 发请求 | **HTTP 500** |
| ③ 网关日志 | `ObjectUtil.checkNotNull` → **NPE**（`Config.name` 为 null → `header(null, null)`） |

**原因**（源码）：`ShortcutConfigurable.normalizeKey()` 里有个前置条件 ——
只有当 `!argHints.shortcutFieldOrder().isEmpty()` 时，才会把 `_genkey_N` 映射成真实字段名（见 L06 · 6.3.2）。
空列表 → 映射不发生 → Config 字段为 null → 运行时 NPE。

---

## 14.5 实测（在 `demo-gateway` 已跑通）

**① 代码**：`demo-gateway/src/main/java/com/myxhs/demo/gateway/filter/DemoHeaderGatewayFilterFactory.java`
**② yml**：`demo-gateway/src/main/resources/application.yml` 的 `demo-provider` 路由加了 `- DemoHeader=X-Demo-Factory, hello-from-my-factory`
**③ 启动**：`mvn -pl demo-gateway spring-boot:run`（或用打包好的 jar）
**④ 验证**：

```bash
curl -s --noproxy '*' "http://localhost:8080/provider/hello" | python3 -m json.tool | grep -i demo
```

**下游 echo 回来的头（节选）**：

```json
"x-demo-factory": "hello-from-my-factory",     ← ★ 自定义过滤器加的
"x-demo-route": "demo-provider",               ← 内置 AddRequestHeader 加的
"x-demo-trace-id": "…",                        ← DemoGlobalFilter（L13）加的
```

> **三种过滤器同场** —— 正好复习：`DemoGlobalFilter`（全局）、`AddRequestHeader`（内置工厂）、`DemoHeader`（**自定义工厂**）。

---

## 14.6 怎么选：`GlobalFilter` vs `GatewayFilterFactory`

| 维度 | **`GlobalFilter`**（L13） | **`GatewayFilterFactory`**（本课） |
|---|---|---|
| 作用范围 | **所有**路由 | **只在配置了它的**路由 |
| 配置能力 | ❌ 无参数（要参数得用 attributes/查配置） | ✅ **带参数**（yml 里写） |
| 注册方式 | `@Component` 即生效 | `@Component` + **配到路由的 `filters`** |
| 典型用途 | TraceId、全局度量、鉴权兜底 | **可按路由开关/调参**的能力（加头、限流参数、灰度标签） |

**判断口诀**：
> **"这条规则对所有路由都成立吗？"** 是 → `GlobalFilter`；否（要按路由配参数）→ `GatewayFilterFactory`。

---

## 14.7 顺带：内置工厂是怎么写的（源码对照）

**① 内置的便利基类**：`AbstractNameValueGatewayFilterFactory`（name/value 场景的专用基类）

```java
public abstract class AbstractNameValueGatewayFilterFactory
        extends AbstractGatewayFilterFactory<NameValueConfig> {
    @Override
    public List<String> shortcutFieldOrder() {
        return Arrays.asList(GatewayFilter.NAME_KEY, GatewayFilter.VALUE_KEY);   // ← 就是 [name, value]
    }
}
```

**② 内置 `AddRequestHeader` 的完整实现**（`AddRequestHeaderGatewayFilterFactory`，只有 10 行）：

```java
public class AddRequestHeaderGatewayFilterFactory extends AbstractNameValueGatewayFilterFactory {
    @Override
    public GatewayFilter apply(NameValueConfig config) {
        return (exchange, chain) -> {
            String value = ServerWebExchangeUtils.expand(exchange, config.getValue());   // 支持占位符
            ServerHttpRequest request = exchange.getRequest().mutate()
                    .headers(h -> h.add(config.getName(), value)).build();
            return chain.filter(exchange.mutate().request(request).build());
        };
    }
}
```

> **看到了吗**：**内置的 `AddRequestHeader` 和你写的一模一样** —— 差别只是它继承了 `AbstractNameValueGatewayFilterFactory`（省掉 `shortcutFieldOrder`）。
> 另外它多了一个 `ServerWebExchangeUtils.expand(...)`：**支持 `${...}` 占位符**（从 attributes/请求里取值）。

---

## 14.8 掌握边界

| 内容 | 判断 |
|---|---|
| **三段式**（Factory / Config / GatewayFilter） | ✅ **必须记**（能默写） |
| **名字规则**（去 `GatewayFilterFactory` 后缀） | ✅ **必须记** |
| **`shortcutFieldOrder()` 必写** | ✅ **必须记**（不然短写绑不上） |
| **GlobalFilter vs Factory 怎么选** | ✅ **必须记** |
| `AbstractNameValueGatewayFilterFactory` | ✅ 知道有（name/value 场景可继承） |
| `apply()` 里做 post 阶段（`.then(...)`） | ✅ 会（复习 L10） |
| 工厂的装配/绑定细节（`ConfigurationService`） | ⏭️ **L20**（工厂机制源码） |
| `ordered` / `GatewayFilterFactory` 的 order 处理 | ⏭️ 用时查 |

---

## 14.9 Self-Test

1. 三段式是哪三段？各自的职责？
2. 类名叫 `FooBarGatewayFilterFactory`，yml 里应该写什么名字？还有哪个前提条件？
3. `shortcutFieldOrder()` 不重写会怎样？为什么？
4. `GlobalFilter` 和 `GatewayFilterFactory` 怎么选？
5. 内置的 `AddRequestHeader` 和你手写的版本，差别在哪？

### 14.9.1 参考答案

<details open>

**1. 三段式**
- **Factory**（`extends AbstractGatewayFilterFactory<C>`）：造过滤器、声明 Config 类型与 `shortcutFieldOrder`
- **Config**（POJO）：收 yml 参数
- **GatewayFilter**（`apply(config)` 返回的 lambda）：真正干活（`filter(exchange, chain)`）

**2. 名字与前提**
yml 写 **`FooBar`**（`NameUtils.normalizeFilterFactoryName` = 去掉 `GatewayFilterFactory` 后缀）。
前提：类必须**注册为 Spring Bean**（`@Component`/`@Bean`），否则框架收集不到。

**3. 不重写 `shortcutFieldOrder()`**
短写语法 `FooBar=a,b` **绑不上字段**（Config 里为空）；只有完整 Map 写法能用。
原因：短写的参数是占位 key `_genkey_0/_genkey_1`，靠 `shortcutFieldOrder()` 映射成真实字段名（L06 · 6.3.2）。

**4. 怎么选**
"这条规则对**所有**路由都成立吗？" 是 → `GlobalFilter`；否（要**按路由配参数/开关**）→ `GatewayFilterFactory`。

**5. 与内置 `AddRequestHeader` 的差别**
逻辑**一模一样**（mutate 请求头 + `chain.filter`），差别：
① 它继承 `AbstractNameValueGatewayFilterFactory`（省掉 `shortcutFieldOrder`）；
② 它多了 `ServerWebExchangeUtils.expand(exchange, value)` —— **支持 `${...}` 占位符**。

</details>

---

## 14.10 Open Questions

> 用法：直接在这里追加，或对话里说编号（如「14.4 没懂」）。

| # | 疑问 | 状态 |
|---|---|---|
| | | |
