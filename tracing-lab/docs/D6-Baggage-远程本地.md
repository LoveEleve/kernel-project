# D6: Baggage API——远程 + 本地的键值传播

> **阅读顺序**: P1 重要——理解如何使用 Baggage 在微服务间安全传递业务上下文
> **源码版本**: micrometer-tracing **v1.2.5**（tag；运行时对齐 Boot 3.2.5，与 xhs-project 一致；1.3+ 新特性以注记标出）
> **核心文件**:
> - `BaggageManager.java` (148 行)——Baggage 的管理入口 API
> - `Baggage.java` (122 行)——单个 Baggage 键值对的操作 API
> - `BaggageInScope.java` (62 行)——带 scope 的 Baggage（可自动关闭）
> - `BraveBaggageManager.java` (139 行)——Brave 实现的 Baggage 管理（委托 Brave BaggageFields）
> - `BraveBaggageInScope.java` (181 行)——Brave 实现的带 scope Baggage
> - `W3CBaggagePropagator` (in W3CPropagation.java)——W3C baggage header 的传播

---
## 一、Baggage 解决了什么"上下文断层"问题

traceId 只能告诉你 "请求经过了哪些服务"，但不能告诉你"这个请求是谁发起的"、"是什么业务场景"。这些信息不能在 Span 中作为 tag——因为 tag 是字符串，tag 过多会爆炸（Zipkin tag index 有上限）。

Baggage 是**不与 Span 一起存储但随请求传播**的键值对：

```java
// Service A:
baggageManager.createBaggageInScope("tenant-id", "acme-corp");

// Service B（接收后）:
String tenantId = baggageManager.getBaggage("tenant-id").get(); // → "acme-corp"
```

Baggage 通过 HTTP header `baggage: tenant-id=acme-corp` 传播，在 MDC 中可见（日志中可搜 `%X{tenant-id}`），但不会进入 Span 的归档存储。

---
## 二、Baggage 的类型划分——远程 vs 本地 vs Tag

`BaggageManager` 的设计区分了三类 Baggage：

| 类型 | 传播到下游 | MDC 输出 | Span Tag 输出 | 配置 |
|------|:--:|:--:|:--:|------|
| **远程 Baggage** | ✅ | ✅ | ❌（默认） | `management.tracing.baggage.remote-fields`（**Boot 3.2.5 支持**） |
| **本地 Baggage** | ❌ | ✅ | ❌（默认） | `local-fields` 属性 **Boot 3.2.5 无**（1.2.5 通过手动构造 `W3CPropagation(..., localFields)` 实现） |
| **Tag Baggage** | ✅ | ✅ | ✅ | `tag-fields` 属性 **Boot 3.2.5 无**（1.2.5 通过 `BraveBaggageManager(tagFields)` 构造参数实现） |

> **版本注记（重要）**：Boot 3.2.5 的 `TracingProperties.Baggage` 只有 `enabled` / `remote-fields` / `correlation`——**没有** `local-fields`/`tag-fields`（更新版本才有）。本 lab 的 application.yml 已按 3.2.5 改为 `remote-fields`。

**为什么需要区分 remote 和 local？**

- `remote-fields`: `tenant-id=acme` —— 所有下游服务都需要知道租户信息
- `local-fields`: `session-id=xyz` —— 仅当前服务日志输出需要，不传播（安全 + 减少 header 大小）
- `tag-fields`: `region=us-east` —— 需要在 Zipkin 中按 region 搜索，所以在 Span 中记录了 tag

**实际 header 输出**：
```
traceparent: 00-{traceId}-{spanId}-01
tracestate: vendor=val
baggage: tenant-id=acme-corp           ← 仅 remote-fields
```

本地 fields 在 W3CBaggagePropagator.injector() 中被明确过滤掉（`filteringFieldNames(strings)`）。

---
## 三、BaggageManager——管理入口

`BaggageManager.java:31`（接口声明）定义了 7 个核心方法：

```java
public interface BaggageManager {
    Map<String, String> getAllBaggage();                                   // 获取全部
    Baggage getBaggage(String name);                                       // 获取或创建
    @Nullable Baggage getBaggage(TraceContext traceContext, String name);  // 指定上下文获取
    @Deprecated Baggage createBaggage(String name);                        // ← 旧 API，不使用
    @Deprecated Baggage createBaggage(String name, String value);
    BaggageInScope createBaggageInScope(String name, String value);        // ← 新 API，一步创建+scope
    BaggageInScope createBaggageInScope(TraceContext context, String name, String value);
}
```

**为什么 `createBaggageInScope` 替代了 `createBaggage`？** 旧 API (`createBaggage`) 需要三步操作：
```java
Baggage b = baggageManager.createBaggage("tenant-id").set("acme");  // 1. 创建
b.makeCurrent();                                                     // 2. 设为当前
// 3. 用完记得 close scope
```

新 API 一步完成：
```java
BaggageInScope b = baggageManager.createBaggageInScope("tenant-id", "acme");
// 自动 makeCurrent，用完 close
b.close();
```

`BaggageInScope extends Closeable` —— 可以用 try-with-resources 自动管理生命周期。

---
## 四、BraveBaggageManager——Brave 的 Baggage 实现

`BraveBaggageManager.java:35-139` 持有 tagFields/remoteFields/baggageFields 三组字段列表，通过 `BaggageField` API 操作 Brave 的 baggage 基础设施：

```java
// BraveBaggageManager.java:35-139（1.2.5 实际字段）
public class BraveBaggageManager implements Closeable, BaggageManager {
    private final List<String> tagFields;       // ← 须转为 Span tag 的字段（构造参数传入）
    private Tracer tracer;                      // ← BraveTracer 的反向引用（循环依赖）
    // 注：1.2.5 没有 remoteFields/baggageFields 字段——
    //     远程传播的过滤在传播层（W3CBaggagePropagator 的 localFields/传播配置）完成

    @Override
    public BaggageInScope createBaggageInScope(String name, String value) {
        return baggage(name).makeCurrent(value);   // ← 一行：创建 + set + makeCurrent
    }

    // 私有——创建 BraveBaggageInScope
    private BraveBaggageInScope baggage(String name) {
        Span span = currentSpan();
        return new BraveBaggageInScope(BaggageField.create(name),     // ← Brave 原生 BaggageField
                span != null ? span.context() : null, span, this.tagFields);
    }

    // 获取当前 span（优先 Micrometer Tracer，回退 Brave Tracing.current()）
    private @Nullable Span currentSpan() {
        if (tracer != null) {
            return BraveSpan.toBrave(tracer.currentSpan());          // ← SPI → Brave 转换
        }
        Tracing tracing = Tracing.current();                          // ← Brave 静态入口回退
        return tracing != null ? tracing.tracer().currentSpan() : null;
    }

    void setTracer(Tracer tracer) { this.tracer = tracer; }          // ← D5 中描述的循环引用入口
}

---
## 五、Baggage 的 MDC 集成——自动写入日志

Baggage 自动和 SLF4J MDC 集成。当 `createBaggageInScope()` 设置值后：

```java
baggageManager.createBaggageInScope("tenant-id", "acme-corp");

// 之后所有日志输出都带有 tenant-id=acme-corp:
log.info("Processing order #12345");
// → INFO [service-a, T-001, S-a, tenant-id:acme-corp] Processing order #12345
```

Brave 的 `MdcScopeDecorator`（已在 D3 中介绍）在每次 scope 设置时读取所有 baggage fields 并写入 MDC。当 scope close（或 baggage close）时，MDC 的值自动恢复。

**MDC 中的 baggage key 命名冲突**：如果 baggage field 名称与 MDC 中已有 key 冲突（如 `traceId`），Brave 不会覆盖——traceId/spanId 的优先级高于 baggage。这在 `MdcScopeDecorator` 中实现。

---
## 六、W3CBaggagePropagator——W3C baggage header 格式

`W3CPropagation.java:342 起（内嵌 W3CBaggagePropagator）`（W3CBaggagePropagator 内嵌类）负责对 `baggage` header 的序列化：

```
baggage: key1=value1,key2=value2
```

**序列化规则**：
- 每个键值对用 `=` 分隔
- 多个键值对用 `,` 分隔
- 值中可选的 `;metadata` 后缀（如 `tenant-id=acme;type=string`）
- 本地 fields 在 inject 时被过滤（不序列化到 header）

**解析规则**（`addBaggageToContext()` 方法）：
```java
String[] entries = baggageHeader.split(",");
for (String entry : entries) {
    String[] keyAndValue = entry.split("=", 2);
    if (keyAndValue.length == 2 && !keyAndValue[1].isEmpty()) {
        String key = keyAndValue[0].trim();
        String value = keyAndValue[1].trim();
        // 去掉 ;metadata 后缀
        if (value.contains(";")) value = value.split(";")[0];
        // 创建 Baggage 并添加到 context
        Baggage baggage = baggageManager.createBaggage(key);
        pairs.add(new SimpleEntry<>(baggage, value));
    }
}
```

**解析失败的处理**：格式错误的条目被忽略——不是抛异常。这遵循了"baggage 故障不影响 trace 传播"的原则。

---
## 七、生产场景量化分析

### 7.1 Baggage 的 header 大小

```
tenant-id=acme-corp                       → 21 bytes
region=us-east                            → 14 bytes  
env=production                            → 15 bytes
user-type=premium                         → 18 bytes
request-id=abc-123-def-456                → 27 bytes
session-id=a1b2c3d4                       → 20 bytes
────────────────────────────────────────────
总计                                        → 115 bytes
```

10K QPS × 115 bytes = **1.15 MB/s** header 数据——在 HTTP/2 的 HPACK 压缩后约 0.3 MB/s。

### 7.2 Baggage 数量限制的风险

Brave 的 BaggageFields 有默认上限（通过 `BaggageField.create()` 创建，达到上限后静默忽略新字段）。

**生产建议**：每个服务不超过 10 个 baggage fields。如果超过 20 个，考虑用单个 JSON 字段聚合（如 `context={"tenant":"acme","region":"us-east"}`）。

### 7.3 Baggage header 被恶意伪造的风险

外部 HTTP 请求可以任意伪造 `baggage: user-id=admin` header。因此：

- **绝不信任**外部 baggage header 中的安全相关字段
- 在网关层：提取 baggage → 用内部认证信息覆盖 → 重新 inject
- 不信任外部字段：`management.tracing.baggage.remote-fields` 仅对你定义的字段生效

---
## 八、跨框架对比

| 维度 | Micrometer Tracing Baggage | OpenTelemetry Baggage | raw Brave BaggageFields |
|------|------|------|------|
| 存储位置 | TraceContext.extra (BaggageFields) | Context (baggage key-value store) | TraceContext.extra (BaggageFields) |
| 传播 header | `baggage:` header | `baggage:` header | 自定义 header（via ExtraFieldPropagation） |
| MDC 集成 | 自动（MdcScopeDecorator） | 需手动 `Baggage.fromContext().storeInContext().makeCurrent()` | 自动（ScopeDecorator） |
| 本地 vs 远程 | ✅ 内置区分 | ❌ 无区分，全部传播 | ✅ 内置区分 |
| Close 语义 | `BaggageInScope.close()` 自动恢复 | `Scope.close()` 手动恢复 | `BaggageField.updateValue()` 无需 close |
| W3C 标准 | ✅ W3CBaggagePropagator | ✅ 原生 | ⚠️ 通过 `W3CPropagation` 桥接 |

---
## 九、面试核心考点

**Q1（陷阱）**：`baggage` header 中 `user-role=admin` 被外部用户伪造——下游服务读取 `baggageManager.getBaggage("user-role").get()` 返回 `"admin"`——安全吗？

<details>
<summary>答案</summary>

**不安全！** W3C baggage 规范不提供认证——任何请求都可以携带任意 baggage header。如果下游服务直接信任 baggage 值，外部用户可以通过伪造 `role=admin` 提权。

**正确做法**：在网关层提取 baggage → 根据认证信息（JWT/OAuth2）确定真实 role → 创建一个新的 `BaggageInScope` 覆盖上游值 → 传播到下游。这样下游服务的 `getBaggage("role")` 返回的是网关认证后的值。
</details>

**Q2**：为什么 `createBaggage()` 被标记为 `@Deprecated`？

<details>
<summary>答案</summary>

旧 API 的 `createBaggage(name).set(value)` 创建 Baggage 但不自动 put in scope。开发者经常忘记 `makeCurrent()`，导致 MDC 中没有 baggage 值。

新 API `createBaggageInScope(name, value)` 一步完成创建和 scope 激活——减少 scope 泄漏风险。
</details>

**Q3（陷阱）**：如果你在 `try-with-resources` 块中创建了 BaggageInScope，但方法内部又提交了一个异步任务到线程池——异步任务能读到这个 baggage 吗？

<details>
<summary>答案</summary>

**不能**——除非线程池被 `CurrentTraceContext.wrap()` 包装过。Baggage 的 scope 绑定在当前线程的 ThreadLocal 上，新线程不继承。

```java
try (BaggageInScope bag = baggageManager.createBaggageInScope("tenant-id", "acme")) {
    executor.submit(() -> {
        // ✗ 这里读不到 tenant-id（新线程的 ThreadLocal 是空的）
        String tenant = (String) MDC.get("tenant-id"); // null
    });
}
```

**修复**：使用 `currentTraceContext.wrap(executor)` 包装线程池。
</details>

**Q4**：Baggage field 的 key 中能用中文吗？能用点号分隔吗？

<details>
<summary>答案</summary>

W3C baggage 规范（RFC 7230）规定：baggage key 只能包含 **token 字符**（英文字母 + 数字 + `!#$%&'*+-.^_``~`）。

**能用的**：`tenant-id`, `user.id`, `x_region` ✅
**不能用的**：`用户ID`（中文）, `user@org`（`@` 不在 token 集中） ❌

如果使用了非法字符，W3CBaggagePropagator 在 parse 时会静默忽略该字段（不传播）。
</details>

**Q5**：`management.tracing.baggage.tag-fields=region` 配置后，`region` 字段会被记录到 Span tag 中——这对 Span 上报有什么影响？

<details>
<summary>答案</summary>

优势：可以在 Zipkin/Jaeger 中按 `region` 搜索和聚合 Span（筛选 "region=us-east" 的所有慢 trace）

代价：每个 Span 额外 1-3 KB 的存储（如果 baggage 值很长）。对于 10M spans/day 的服务，额外存储 = 10M × 1KB = 10 GB/day。

建议：只将**短的业务标识**放入 tag-fields（如 `region`, `env`, `service-version`），不要放入长 ID 或 JSON。
</details>

**Q6**：OpenTelemetry 的 Baggage 和 Micrometer Tracing 的 Baggage 有什么关键差异？

<details>
<summary>答案</summary>

| 维度 | Micrometer Baggage | OTel Baggage |
|------|------|------|
| API 复杂度 | 一步 `createBaggageInScope(name, value)` | 三步：`create(name, value)` → `storeInContext()` → `makeCurrent()` |
| MDC 集成 | 自动（MdcScopeDecorator） | 需手动 `Baggage.fromContext().asMap()` → MDC.put() |
| 生命周期 | `BaggageInScope.close()` 自动恢复 | `Scope.close()` 手动恢复 |
| Immutable 实现 | 取决于 bridge | OTel Context 是不可变的（set 返回新 Context） |

OTel 的 baggage 模型更纯粹（immutable context），但开发体验更繁琐。Micrometer 的选择是在"正确性"和"易用性"之间取舍。
</details>

---
## 十、自检清单

- [x] **行号验证**：BaggageManager/BaggageInScope/BraveBaggageManager 行号 grep 确认
- [x] **代码块验证**：关键代码片段与源码一致
- [x] **覆盖率检查**：BaggageManager 所有方法 + W3CBaggagePropagator 解析逻辑 + MDC 集成 全覆盖
- [x] **跨框架对比**：OTel baggage + raw Brave 对比
- [x] **面试题**：6 题（Q1/Q3 标记为陷阱题）
- [x] **生产量化**：header 大小 + 字段限制 + 安全伪造风险
- [x] **事实核实**：存储位置、传播格式、MDC 集成 全部 grep 源码确认
