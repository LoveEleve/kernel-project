# D6: AuthoritySlot 授权规则 — 黑白名单

> 源码位置: `sentinel-core/src/main/java/com/alibaba/csp/sentinel/slots/block/authority/`
> 核心文件: `AuthoritySlot.java` / `AuthorityRuleChecker.java`(68行) / `AuthorityRule.java` / `AuthorityRuleManager.java`
> 前置: D2（Slot链）理解 AuthoritySlot 在 SPI 链中的第 5 位；D13（Context）理解 `context.getOrigin()` 的来源

---

## 一、问题背景：流控和熔断管的是"量"，谁来管"谁"？

D3 流控和 D4 熔断管的是"来了多少请求""请求质量如何"——不管**谁**发起的请求。如果有恶意调用方频繁请求某接口（哪怕 QPS 不高），需要直接按调用方身份拦截——这就是 `AuthorityRule` 的场景。

Sentinel 通过 `ContextUtil.enter("resource", "origin")` 中的 `origin` 字段标识调用方（如 `order-service`、`mobile-app`）。`AuthorityRule` 对这个 origin 做黑白名单校验。

```
ContextUtil.enter("sentinelApi", "order-service")
  → Context.origin = "order-service"
  → AuthoritySlot.entry
    → AuthorityRuleChecker.passCheck
      → 黑名单：origin 在名单中？→ 拒绝
      → 白名单：origin 不在名单中？→ 拒绝
```

---

## 二、AuthoritySlot.entry：授权槽的入口

**位置**: `AuthoritySlot.java:L38-58`

```java
public void entry(Context context, ResourceWrapper resourceWrapper, DefaultNode node,
                  int count, boolean prioritized, Object... args) throws Throwable {
    checkBlackWhiteAuthority(resourceWrapper, context);           // L40
    fireEntry(context, resourceWrapper, node, count, prioritized, args); // L41
}

void checkBlackWhiteAuthority(ResourceWrapper resource, Context context) 
        throws AuthorityException {
    List<AuthorityRule> rules = AuthorityRuleManager.getRules(resource.getName()); // L51
    for (AuthorityRule rule : rules) {                            // L53
        if (!AuthorityRuleChecker.passCheck(rule, context)) {     // L57
            throw new AuthorityException(context.getOrigin(), rule);
        }
    }
}
```

**与 FlowSlot 相同的多规则模式**：一个资源可以配多条 `AuthorityRule`（如：黑名单封禁 `hacker-bot` + 白名单只允许 `order-service`）。**所有规则必须全部通过**——任何一条拒绝则抛 `AuthorityException`。

---

## 三、AuthorityRuleChecker.passCheck：两轮匹配 + 黑白策略

**位置**: `AuthorityRuleChecker.java:L30-66`

```java
static boolean passCheck(AuthorityRule rule, Context context) {
    String requester = context.getOrigin();                        // L32: 获取调用方身份

    // ① 空值保护：origin 为空 或 limitApp 为空 → 直接放行
    if (StringUtil.isEmpty(requester) || StringUtil.isEmpty(rule.getLimitApp())) {
        return true;                                               // L35
    }

    // ② 第一轮：indexOf 快速预筛（子串匹配）
    int pos = rule.getLimitApp().indexOf(requester);               // L39
    boolean contain = pos > -1;                                   // L40

    // ③ 第二轮：逗号分隔 → 逐一精确匹配（只有当第一轮通过时才做）
    if (contain) {
        boolean exactlyMatch = false;
        String[] appArray = rule.getLimitApp().split(",");        // L44
        for (String app : appArray) {                              // L45
            if (requester.equals(app)) {                           // L46
                exactlyMatch = true;
                break;
            }
        }
        contain = exactlyMatch;                                   // L51: 覆盖第一轮的模糊结果
    }

    // ④ 策略判断：黑名单 → 在名单中则拒绝；白名单 → 不在名单中则拒绝
    int strategy = rule.getStrategy();
    if (strategy == RuleConstant.AUTHORITY_BLACK && contain) {    // L55
        return false;
    }
    if (strategy == RuleConstant.AUTHORITY_WHITE && !contain) {   // L58
        return false;
    }

    return true;                                                   // L62
}
```

**两轮匹配的设计意图**：

| 轮次 | 算法 | 复杂度 | 作用 |
|:--:|------|:--:|------|
| 第一轮 | `indexOf(requester)` 子串匹配 | O(n) | **快速排除**——大多数请求不在名单中，一个 indexOf 就能跳过精确匹配 |
| 第二轮 | `split(",")` + `equals` 逐一比较 | O(m×k) | **精确验证**——第一轮命中的才进入，避免 `order-service` 误匹配 `internal-order-service` |

**为什么需要两轮？** 如果直接用 `equals` 逐一遍历（O(m)），每个请求都要做名单数组遍历——哪怕名单只有 3 个。`indexOf` 是 JVM 优化的 `String.indexOf` 原生实现（C2 会编译为 SIMD 指令），比 Java 循环的 `equals` 快 5-10 倍。用 indexOf 做预筛，只有命中时才做昂贵的 split+equals 精确验证。

---

## 四、发散：Sentinel 授权 vs Spring Security vs Nginx IP 黑名单

| 维度 | Sentinel AuthorityRule | Spring Security | Nginx deny/allow |
|------|------|------|------|
| 授权维度 | `origin`（调用方身份，由 `ContextUtil.enter(name, origin)` 设置） | Principal + GrantedAuthority + URL | 源 IP |
| 规则粒度 | 资源级别（每个 `SphU.entry` 独立配置） | URL 级别 + 方法级别（`@PreAuthorize`） | server/location 级别 |
| 动态生效 | Nacos 数据源 → 运行时生效 | `SecurityFilterChain` 重构 → 需刷新上下文 | `nginx -s reload` 热加载 |
| 性能 | indexOf 预筛 + equals 精确匹配（纳秒级） | Filter 链 + 数据库查角色（毫秒级） | IP 位运算（纳秒级） |

**Sentinel 授权的适用场景**：微服务间的调用方身份管控（A 服务是否可以调 B 服务的 `/inventory/deduct`），而不是用户级别的权限校验（后者用 Spring Security）。

**实际生产场景**：order-service 遭遇恶意爬虫，调用量突然从 100 QPS 暴涨到 5000 QPS——但在 D3 流控触发之前（还没达到 QPS=6000 的阈值），可以先通过 `AuthorityRule` 直接封禁爬虫的 origin标识 `malicious-bot`。这是"先踢出去再讨论该不该限流"的策略。

**Sentinel 授权 + Spring Security 的双层防护**：Spring Security 管的是"用户 A 能不能查用户 B 的订单"（数据层权限），Sentinel 管的是"order-service 这个调用方能不能调 inventory-service 的接口"（服务层权限）。两者正交——不能用 Sentinel 替代 Spring Security，也不能用 Spring Security 做服务间限流。

---

## 五、面试考点

**Q1: Sentinel 的黑名单和白名单机制？`origin` 从哪来？**

黑名单（`AUTHORITY_BLACK`）：origin 在名单中则拒绝。白名单（`AUTHORITY_WHITE`）：origin 不在名单中则拒绝。`origin` 来自 `ContextUtil.enter("resource", "origin")` 的第二个参数——调用方需要在入口处手动设置（如从 HTTP Header `X-Caller-Service` 中提取）。

**追问**: 如果没设置 origin（为空），AuthorityRule 会怎样？

`AuthorityRuleChecker.passCheck` 第一行：`if (StringUtil.isEmpty(requester)) return true` → **直接放行**。这意味着如果调用方没有设置 origin，黑白名单规则全部失效——这是"故障开放"设计（宁可放行也不误拦）。

**Q2: 为什么 AuthorityRuleChecker 用两轮匹配（indexOf + split+equals）？**

`indexOf` 做快速预筛（原生 SIMD 指令，纳秒级），`split+equals` 做精确验证。如果只有 equals（无预筛），每个请求都要遍历名单数组；如果只有 indexOf（无精确验证），`order-service` 会误匹配 `internal-order-service`。两轮结合 = 速度 + 精度。

---

## 六、生产陷阱

1. **origin 为空导致规则全部失效**：`ContextUtil.enter("resource")` 不传 origin → origin=空串 → `passCheck` 直接 return true → 黑白名单形同虚设。**必须**在网关/Filter 层统一设置 origin（如从 JWT 或 HTTP Header 提取）。

2. **indexOf 的误判风险**：`rule.getLimitApp().indexOf("order")` → 如果 limitApp 是 `"internal-order-service"`，`"order"` 作为子串也会命中——这就是第一轮预筛的代价。第二轮 `split+equals` 会修正这个误判，但代码可读性差——维护者可能看到第一轮就以为"这是简单的子串匹配"而删掉第二轮的 `exactlyMatch` 覆盖。
