# Lesson 08: Actuator — 看路由 & 动态改路由

> 课程索引：`../gateway-学习清单.md` → Stage 2 · Lesson 08
> 版本口径：xhs-demo 实际运行的 SCG **4.1.5**（本课结论均在 demo 上实测）
> 来源：官方 `actuator-api.adoc` + `GatewayControllerEndpoint` / `InMemoryRouteDefinitionRepository` + 实测
> **提问方式**：直接说编号，如「8.3 没懂」

---

## 8.0 本课要掌握的 4 条

| # | 要掌握的 | 一句话 | 在哪节 |
|---|---|---|---|
| **①** | **两个视图** | **配置态** `/routedefinitions`（yml 长啥样） vs **运行态** `/routes`（编译后的对象）—— 排障先看哪个 | 8.3 |
| **②** | **写完不生效** | `POST` 加路由后，**必须 `POST /refresh`** 才进运行态 | 8.4 |
| **③** | **动态增删有边界** | 只能动"**内存 repo 里的路由**"（= Actuator 自己加的）；**yml 定义的路由 `DELETE` → 404** | 8.5 |
| **④** | **内存 repo 的代价** | **重启丢** + **多实例各一份**（不一致）→ 生产要换存储（L49） | 8.6 |

---

## 8.1 它干了什么（两个真实场景）

| 场景 | 用哪个端点 |
|---|---|
| **排障**：线上"这条路由到底匹配什么、挂了哪些过滤器？" | `GET /actuator/gateway/routes/{id}` |
| **临时**：不重启加/删一条路由（灰度、验证） | `POST/DELETE /actuator/gateway/routes/{id}` + `POST /refresh` |

**打开方式**（yml）：
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,gateway     # ← 必须显式暴露 gateway
  endpoint:
    gateway:
      enabled: true
```

---

## 8.2 端点清单（含实测状态码）

| 端点（基路径 `/actuator/gateway`） | 方法 | 作用 | 实测 |
|---|---|---|---|
| `/routes` | GET | **运行态**路由列表 | 200 |
| `/routes/{id}` | GET | 单条**运行态** | 200 |
| `/routes/{id}` | POST | 新增/覆盖一条路由定义（写**配置态**） | 201 |
| `/routes/{id}` | DELETE | 删除（**只能删内存 repo 的**） | 200 / 404 |
| `/routedefinitions` | GET | **配置态**列表（与 yml 一一对应） | 200 |
| `/refresh` | POST | 刷新（让配置态 → 运行态） | 200（GET 会 405） |
| `/globalfilters` | GET | 全局过滤器清单 + order | 200 |
| `/routefilters` | GET | 可用的 `GatewayFilterFactory` 清单 | 200（**32 个**） |

> ⚠️ `/routedefinitions/{id}` **不存在**（只有列表形式），实测 404。

---

## 8.3 要掌握的 ①：两个视图（配置态 vs 运行态）

**同一条路由 `cors-lab`，两个端点返回完全不同的东西**：

### 配置态 `GET /routedefinitions`（= yml 的形态）

```json
{
  "id": "cors-lab",
  "predicates": [ { "name": "Path", "args": { "_genkey_0": "/cors-lab/**" } } ],
  "filters":    [ { "name": "StripPrefix", "args": { "_genkey_0": "1" } } ],
  "metadata":   { "cors": { ... } }
}
```

### 运行态 `GET /routes/cors-lab`（= 已编译的对象）

```json
{
  "route_id": "cors-lab",
  "uri": "http://localhost:8081",
  "order": 0,
  "predicate": "Paths: [/cors-lab/**], match trailing slash: true",
  "filters": ["[[StripPrefix parts = 1], order = 1]"],
  "metadata": { "cors": { ... } }
}
```

### 对照表

| | 配置态（`routedefinitions`） | 运行态（`routes`） |
|---|---|---|
| 对应关系 | **和 yml 一一对应** | **编译后的运行对象** |
| `predicates` | `[{name, args}]`（含 `_genkey_0`，见 L06） | `predicate`：**一行人可读串** |
| `filters` | `[{name, args}]` | `filters`：**串列表**（含 order） |
| 用途 | 看"**配了什么**" | 看"**实际匹配/执行什么**" |

> **排障口诀**：**配置对不上运行 → 大概率没 refresh**（见 8.4）。

---

## 8.4 要掌握的 ②：写完不生效（必须 refresh）

**实测**：

```bash
# ① POST 新增
curl --noproxy '*' -X POST http://localhost:8080/actuator/gateway/routes/act-demo \
  -H 'Content-Type: application/json' \
  -d '{"id":"act-demo","uri":"http://localhost:8081",
       "predicates":[{"name":"Path","args":{"_genkey_0":"/act-demo/**"}}]}'
# → HTTP 201
```

| 查询时机 | `/routedefinitions`（配置态） | `/routes`（运行态） |
|---|---|---|
| **POST 后立刻** | ✅ **有** act-demo | ❌ **没有** |
| **`POST /refresh` 后** | ✅ 有 | ✅ **有了** |

**为什么**（分两层理解）：

**① 行为层（现在就要懂）**：

```
你写的     →  路由定义（RouteDefinition）   ← 配置态
网关跑的   →  编译后的路由快照（Route）      ← 运行态（缓存在内存）

写入配置 ≠ 重建快照  →  中间需要一道"重建"工序 = refresh
```

> 一句话：**你改的是"源码"，网关跑的是"编译产物"** —— 不重新编译，跑的还是旧的。

**② 机制层（L30 讲）**：`CachingRouteLocator` 怎么缓存、`RefreshRoutesEvent` 什么时候发布、`?metadata=` 局部刷新、以及**"刷新只换模型、不中断在途请求"**。

---

## 8.5 要掌握的 ③：动态增删的边界（实测）

| 操作 | 结果 | 原因 |
|---|---|---|
| `DELETE /routes/act-demo`（**Actuator 加的**） | ✅ **200** | 它在**内存 repo** 里 |
| `DELETE /routes/demo-provider`（**yml 定义的**） | ❌ **404** | yml 路由来自 `PropertiesRouteDefinitionLocator`，**不在内存 repo** 里 |

**源码印证**：`InMemoryRouteDefinitionRepository.delete()` 先 `containsKey(id)`，没有就报错 → 404。

**推论**：
- Actuator 的增删**只作用于"内存 repo"**这一层
- **yml / 配置中心定义的路由，Actuator 删不掉** → 要改源头（文件 / 配置中心）
- `POST` 是 **`put`（新增或覆盖）**，同名会**覆盖**

---

## 8.6 要掌握的 ④：内存 repo 的代价（生产注意）

| 代价 | 说明 |
|---|---|
| **重启丢** | `InMemoryRouteDefinitionRepository` 是内存 Map → 重启后 Actuator 加的路由**全没了**（yml 的还在） |
| **多实例不一致** | 每个网关实例**各有一份内存** → 打到 A 实例加的路由，B 实例不知道 → **请求漂移** |

**生产做法**：
- 路由变更走**配置中心**（Nacos，L44）或**共享存储**（Redis，L49）
- Actuator 动态改只用于**临时调试**

> 顺带：`InMemoryRouteDefinitionRepository` 是 **Spring 自动创建的**（`@ConditionalOnMissingBean`）→ 所以**不需要自己定义**就能用 POST/DELETE。

---

## 8.7 顺带福利：`/globalfilters` 是一张 order 表（实测）

```bash
curl --noproxy '*' http://localhost:8080/actuator/gateway/globalfilters
```
返回 **`{过滤器全类名: order}`** 的 Map。demo 实测：

| order | 过滤器 | 说明 |
|---|---|---|
| **-2147483648**（MIN） | `RemoveCachedBodyFilter` | 最先 |
| -2147482648 | `AdaptCachedBodyGlobalFilter` | |
| **-1** | `NettyWriteResponseFilter` | 写响应（见 L27） |
| 0 | `GatewayMetricsFilter` | |
| **100** | `DemoGlobalFilter`（自定义） | 我们的过滤器 |
| **10000** | `RouteToRequestUrlFilter` | 拼 URL（L27 第 1 步） |
| 10150 | `NoLoadBalancerClientFilter` | ⚠️ 没引 SCLB 时的**兜底**（`lb://` 会明确报错） |
| **2147483647**（MAX） | `NettyRoutingFilter` | 最后（真正转发） |

> **用途**：面试被问"转发四步的 order 是多少" → 这张表就是答案（**不用背，会用端点查**）。
> 注意 `ReactiveLoadBalancerClientFilter` 不在表里 —— 因为 demo 没引 SCLB，出现的是 `NoLoadBalancerClientFilter`（10150）。

---

## 8.8 值不值得深挖

### 掌握边界（就这 4 条）

| # | 要掌握 | 掌握到什么程度 |
|---|---|---|
| ① | **两个视图**（配置态 / 运行态） | 能说清"不是一个东西"；排障先对比（对不上 = 没 refresh） |
| ② | **写完必须 `POST /refresh`** | 知道 why 的**行为层**（改"源码" ≠ 换"编译产物"）；机制层见 L30 |
| ③ | **增删边界**：只能动内存 repo → **yml 路由 DELETE 404** | 知道（生产会踩） |
| ④ | **内存 repo 代价**：重启丢 + 多实例不一致 → 生产走配置中心/Redis | 知道（架构设计用） |

| 内容 | 判断 |
|---|---|
| `/globalfilters` 的具体 order 值 | ⏭️ **会查**即可（不背） |
| `combinedfilters` 的 TODO 细节 | ⏭️ 一句话翻篇（"它不真合并"） |
| Actuator 端点的源码实现 | ⏭️ Stage 5 再说 |

---

## 8.9 Self-Test

1. 配置态和运行态分别用哪个端点？两者对不上时第一反应是什么？
2. `POST /routes/{id}` 后立刻查 `/routes` 能查到吗？为什么？怎么才能查到？
3. 为什么 `DELETE /routes/demo-provider`（yml 定义）返回 404？什么样的路由才能删掉？
4. `POST` 同名路由会发生什么？
5. 内存 repo 有哪两个生产代价？生产应该用什么替代？
6. 想查"转发四步的 order"用哪个端点？

---

## 8.10 Open Questions

> 用法：直接在这里追加，或对话里说编号（如「8.5 没懂」）。

| # | 疑问 | 状态 |
|---|---|---|
| | | |
