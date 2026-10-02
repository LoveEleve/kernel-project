# Lesson 07: CORS — 跨域配置

> 课程索引：`../gateway-学习清单.md` → Stage 2 · Lesson 07
> 版本口径：xhs-demo 实际运行的 SCG **4.1.5**（本课所有结论均在 demo 上实测）
> 来源：官方 `cors-configuration.adoc` + `RoutePredicateHandlerMapping` / `CorsGatewayFilterApplicationListener` / `AbstractHandlerMapping` + 实测
> **提问方式**：直接说编号，如「7.4 没懂」

---

## 7.0 本课要掌握的 3 条

| # | 要掌握的 | 一句话 | 在哪节 |
|---|---|---|---|
| **①** | **CORS 处理不在 Gateway 过滤器链里** | 在 **`RoutePredicateHandlerMapping`（WebFlux HandlerMapping 层）**，由 `DefaultCorsProcessor` 处理 | 7.3 |
| **②** | **两种配置** | 全局 `globalcors.cors-configurations` vs 路由级 `metadata.cors`（L06 说的第 3 个原生 key） | 7.4 |
| **③** | **预检坑 + 解药** | 预检（OPTIONS）要成功必须"**匹配上路由** + **有 CORS 配置**"，缺一 → **403**；解药 = **全局 CORS + `add-to-simple-url-handler-mapping: true`** | 7.5 |

---

## 7.1 它干了什么（先看现象）

**场景**：前端页面跑在 `http://localhost:3000`，要调网关 `http://localhost:8080/provider/hello`。
> 同源 = 协议 + 域名 + 端口

浏览器发现"**域名/端口不同**"= 跨域，于是（对非简单请求）先发一个**预检请求**：

```http
OPTIONS /provider/hello HTTP/1.1
Origin: http://localhost:3000
Access-Control-Request-Method: GET
```

**网关必须回答**（否则浏览器不发真正的请求，控制台报 CORS 错误）：

```http
HTTP/1.1 200 OK
Access-Control-Allow-Origin: http://localhost:3000
Access-Control-Allow-Methods: GET,POST
```

**一句话**：CORS = 网关告诉浏览器"**这个跨域来源我允许**"的一套**响应头协议**；预检是浏览器先问一句"我能发吗"。

> ⚠️ **CORS 是纯浏览器行为** —— 服务端只是"被要求回答"。curl / Postman **完全不理会**（所以用 curl 测只能看到"服务端行为"，看不到"浏览器拦截"）。

### 两种请求的完整流程（★ 理解这一节）

#### 简单请求（不预检）

```
① 浏览器【直接发真请求】      （自动加 Origin 头）
② 网关转发 → 下游执行          ★ 副作用可能已经发生（数据库写了！）
③ 响应回给浏览器
④ 浏览器检查响应头：
     ├─ 有 Access-Control-Allow-Origin 且匹配  → JS 能读 ✅
     └─ 没有 / 不匹配                          → ❌ 浏览器【丢弃响应】
                                                   JS 拿到网络错误，什么也读不到
```

| 问题 | 答案 |
|---|---|
| 请求能发出去吗 | ✅ **能发** |
| 网关会转发吗 | ✅ **会** |
| 下游会执行吗 | ✅ **会**（**写操作已发生**） |
| 响应回到浏览器了吗 | ✅ **回来了** |
| 浏览器让 JS 读吗 | ❌ **读不到**（连 HTTP 状态码都拿不到） |

#### 非简单请求（预检）

```
① 浏览器先发 OPTIONS 预检        （不碰业务、不碰下游）
② 检查预检响应：
     ├─ 通过（200 + 允许的 origin/method/headers）
     │     → ③ 发真请求 → 下游执行 → 响应 → 浏览器检查头 → JS 能读 ✅
     └─ 不通过（403 / 没有头）
           → ✋ 真请求【根本不发】→ 下游【零感知】
```

#### 对比（★ 核心）

| | 简单请求 | 非简单请求 |
|---|---|---|
| CORS 不允许时，真请求**会发**吗 | ✅ **会发** | ❌ **不发** |
| 下游**会执行**吗 | ✅ **会**（副作用已产生） | ❌ **不会** |
| JS 能**读响应**吗 | ❌ 读不到 | （预检挡在门口，没到这一步） |

#### 为什么这么设计？

**因为 CORS 不能"阻止发送"** —— 历史上 `<form>` / `<img>` / `<script>` 早就能跨域发请求；一律禁止会让 Web 没法用。

| 请求类型 | 策略 |
|---|---|
| **老式请求**（简单请求） | 放行**发送**，只**禁止读取** |
| **新式请求**（`PUT/DELETE` / 自定义头 / JSON） | 加一道**预检**门 |

> ⚠️ **重要推论：CORS 不是安全防护**
> - 简单请求的**写操作可能已经执行**（CORS 拦不住）
> - 想防"别的网站**读**你的数据" → ✅ CORS 有用
> - 想防"别的网站**冒充你发写请求**"（CSRF）→ ❌ CORS 不管用 → 靠 **CSRF Token / SameSite Cookie**

---

## 7.2 实测：路由级配置怎么用

挂到路由的 `metadata.cors`（**L06 说的第 3 个原生 metadata key**）：

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: cors-lab
          uri: http://localhost:8081
          predicates:
            - Path=/cors-lab/**
          metadata:
            cors:
              allowedOrigins: '*'
              allowedMethods: [GET, POST]
              allowedHeaders: '*'
              maxAge: 30
```

**实测（OPTIONS 预检）**：

```bash
curl --noproxy '*' -i -X OPTIONS http://localhost:8080/cors-lab/hello \
  -H 'Origin: http://localhost:3000' \
  -H 'Access-Control-Request-Method: GET' \
  -H 'Access-Control-Request-Headers: x-custom'
```

```http
HTTP/1.1 200 OK
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET,POST
Access-Control-Allow-Headers: x-custom        ← allowedHeaders: '*' 会回显请求的头
Access-Control-Max-Age: 30
content-length: 0
```

**实测（真实 GET 也带 CORS 头）**：

```bash
curl --noproxy '*' -i http://localhost:8080/cors-lab/hello -H 'Origin: http://localhost:3000'
```
→ 响应里有 `Access-Control-Allow-Origin: *` ✅

> ⚠️ **注意**：预检请求**不会**进业务路由（`content-length: 0`，下游没被调用）—— 它在 HandlerMapping 层就被短路了（见 7.3）。

---

## 7.3 要掌握的 ①：CORS 处理在哪？（★ 本课核心认知）

> **结论：CORS 完全不在 Gateway 的过滤器链里，而在 WebFlux 的 HandlerMapping 层。**

### 证据链

| 配置 | 谁在处理 |
|---|---|
| 全局 `globalcors.cors-configurations` | `GlobalCorsProperties` → **`RoutePredicateHandlerMapping.setCorsConfigurations(...)`** |
| 路由级 `metadata.cors` | `CorsGatewayFilterApplicationListener`（监听 `RefreshRoutesEvent`）：把 `metadata.cors` 读出来、**合并进 HandlerMapping 的 CORS 配置** —— **它不是创建 Gateway Filter！** |

**真正处理 CORS 的**：`AbstractHandlerMapping.getHandler()` 里的 **`DefaultCorsProcessor`**（Spring **WebFlux** 的）。

### 请求流经顺序

```
客户端
 │
 ▼ ① RoutePredicateHandlerMapping.getHandler()
 │      ├─ 路由匹配
 │      └─ ★ CORS 处理在这里（DefaultCorsProcessor）
 │            └─ 预检请求 → 直接短路返回（不进下游）
 │
 ▼ ② FilteringWebHandler（Gateway 过滤器链）
 │      └─ 到这里时，CORS 已经处理完了
 │
 ▼ ③ 转发到下游
```

### 为什么这么设计

| 原因 | 说明 |
|---|---|
| CORS 是 **HTTP 语义**的事 | 就是加响应头 + 预检短路，属于 HandlerMapping 的本职 |
| 放过滤器链反而麻烦 | 过滤器链要自己实现"预检短路"（否则预检会被转发到下游） |

### 路由级配置的一个细节

它拿路由的**第一个 `Path` 谓词**作为 URL pattern；**路由没有 Path 谓词时用 `/**`**（官方文档写明，源码 `getPathPredicate()` 印证）。

---

## 7.4 要掌握的 ②：两种配置对比

| | 全局 | 路由级 |
|---|---|---|
| 配置位置 | `spring.cloud.gateway.globalcors.cors-configurations` | `routes[].metadata.cors` |
| 结构 | **URL pattern → `CorsConfiguration`** 的 Map | 直接一个 `CorsConfiguration` |
| 生效范围 | 按 URL pattern | 按路由（用它的 Path 谓词做 pattern） |
| 官方原话 | "Both offer the same possibilities" | 同左 |

**全局写法**：

```yaml
spring:
  cloud:
    gateway:
      globalcors:
        cors-configurations:
          '[/**]':
            allowedOrigins: "https://docs.spring.io"
            allowedMethods:
              - GET
```

> **注意**：yml 里的 `CorsConfiguration` 是 **common 包**（`org.springframework.web.cors.CorsConfiguration`）—— 它是"配置数据"，Servlet / Reactive 共用。
> 分开的是**处理器**：`CorsConfigurationSource` / `CorsProcessor` / `CorsWebFilter` 要用 **`org.springframework.web.cors.reactive`** 包（Servlet 版 WebFlux 不认 → **静默不生效**）。

---

## 7.5 要掌握的 ③：预检的坑 + 解药（实测 6 组）

### 实测量表

| 场景 | 配置 | OPTIONS 预检结果 |
|---|---|---|
| **A** | 路由级 `metadata.cors` + Path 谓词（无 Method） | ✅ **200** + 全 CORS 头 |
| **B** | 路由级 `metadata.cors` + **`Method=GET` 谓词** | ❌ **403** |
| **C1** | 无路由匹配 | ❌ 403 |
| **C2** | 有路由、但无 CORS 配置 | ❌ 403 |
| **D1** | B + **`add-to-simple-url-handler-mapping: true`** + 全局 CORS | ✅ **200** |
| **D2** | C2 + 同上 | ✅ **200** |

### 为什么 B 会挂（关键理解）

1. 浏览器预检的 HTTP 方法是 **`OPTIONS`**
2. 但路由要求 **`Method=GET`** → **OPTIONS 匹配不上路由**
3. 请求落到**兜底 mapping**（`SimpleUrlHandlerMapping`，管静态资源那个）
4. 那里**没有 CORS 配置** → `DefaultCorsProcessor` 判定"预检不允许" → **403**
5. 浏览器拿到 403 → **不发真正的 GET** → 前端报跨域错误

> **本质**：预检要成功，必须"**能匹配上路由（或兜底 mapping）**"**且**"**该处有 CORS 配置**"——**缺一即 403**。

### 解药（官方文档原话）

> To provide the same CORS configuration to requests that are **not handled by some gateway route predicate**, set `spring.cloud.gateway.globalcors.add-to-simple-url-handler-mapping` to `true`. This is useful when you try to support CORS preflight requests and your route predicate does not evaluate to `true` because the HTTP method is `options`.

```yaml
spring:
  cloud:
    gateway:
      globalcors:
        add-to-simple-url-handler-mapping: true     # ★ 关键
        cors-configurations:
          '[/**]':
            allowedOrigins: '*'
            allowedMethods: [GET, POST]
```

**实现**（源码）：`SimpleUrlHandlerMappingGlobalCorsAutoConfiguration` 把**全局** CORS 配置挂到 `SimpleUrlHandlerMapping`（兜底 mapping）上 → **任何路径**的预检都能拿到 CORS 头。

> ⚠️ **注意**：它挂的是**全局**配置 —— 所以**路由级 `metadata.cors` 单独用救不了 B 场景**。

### 生产建议

| 场景 | 做法 |
|---|---|
| 需要浏览器跨域访问 | ✅ **全局 CORS + `add-to-simple-url-handler-mapping: true`** |
| 只给某些路由开 CORS，且路由无 `Method` 谓词 | 路由级 `metadata.cors` 够用 |
| 路由有 `Method` 谓词 + 需要预检 | ❌ 只靠 `metadata.cors` **必挂** |

---

## 7.6 在 demo 上复现（配置已落盘）

**配置位置**：`demo-gateway/src/main/resources/application.yml`

| 配置项 | 内容 |
|---|---|
| `gateway.globalcors` | 全局 CORS（`/**`，`allowedOrigins: '*'`）+ **`add-to-simple-url-handler-mapping: true`** |
| 路由 `cors-lab` | 只有 `Path` 谓词 + `metadata.cors` |
| 路由 `cors-lab2` | `Path` + **`Method=GET`** + `metadata.cors`（预检坑场景） |

**启动**（⚠️ `java -jar` 用的是 jar 里的**旧** yml；maven 重新打包慢 → 用 `--spring.config.location` 直接指向源码 yml）：

```bash
cd /data/workspace/source-code/xhs-demo
java -jar demo-gateway/target/demo-gateway-1.0-SNAPSHOT.jar \
  --spring.config.location=file:demo-gateway/src/main/resources/application.yml
```

**三条验证命令**：

```bash
# ① 路由级 CORS 预检（应 200 + CORS 头）
curl --noproxy '*' -i -X OPTIONS http://localhost:8080/cors-lab/hello \
  -H 'Origin: http://localhost:3000' -H 'Access-Control-Request-Method: GET'

# ② Method=GET 路由的预检（有解药 → 200）
curl --noproxy '*' -i -X OPTIONS http://localhost:8080/cors-lab2/hello \
  -H 'Origin: http://localhost:3000' -H 'Access-Control-Request-Method: GET'

# ③ 无路由路径的预检（解药兜底 → 200）
curl --noproxy '*' -i -X OPTIONS http://localhost:8080/nothing/foo \
  -H 'Origin: http://localhost:3000' -H 'Access-Control-Request-Method: GET'
```

**复现"预检 403"坑**：把 yml 里的 `add-to-simple-url-handler-mapping: true` **注释掉** → 重启 → 再打 ② 和 ③ → **403 且无 CORS 头**。

| 配置 | ②（`Method=GET` 路由） | ③（无路由路径） |
|---|---|---|
| **有解药** | ✅ 200 | ✅ 200 |
| **无解药** | ❌ 403 | ❌ 403 |

> ⚠️ 这里测的是"**网关行为**"（curl 不受同源策略约束）。要看"**浏览器拦截**"得写前端页面（`localhost:3000`）用 `fetch` 试 —— 那是浏览器侧的行为，服务端日志看不到。

---

## 7.7 有 nginx 时：CORS 放哪层？（nginx vs Gateway）

### nginx 怎么做 CORS

```nginx
location /api/ {
    add_header Access-Control-Allow-Origin  $http_origin always;
    add_header Access-Control-Allow-Methods "GET,POST,OPTIONS" always;
    add_header Access-Control-Allow-Headers "Content-Type,Authorization" always;
    add_header Access-Control-Allow-Credentials "true" always;

    if ($request_method = OPTIONS) {
        add_header Access-Control-Max-Age 86400;
        return 204;              # ★ 预检直接短路，不转发
    }
    proxy_pass http://gateway;
}
```

### 核心区别（4 条）

| | **nginx** | **Gateway** |
|---|---|---|
| **懂不懂 CORS 语义** | ❌ **不懂** —— 只会"无脑加头 + 短路" | ✅ **懂** —— 内建 CORS 规范实现（校验 origin 白名单 / method / header，不允许就 **403**） |
| 配置方式 | 静态指令（`add_header` + `if`） | yml（`globalcors` / `metadata.cors`），可 Actuator 动态改 |
| 粒度 | location（路径级） | URL pattern（全局）/ 路由级 |
| 靠前性 | ✅ **更靠前** —— 预检在它这层就短路了 | 后面一层（**nginx 短路的预检永远到不了它**） |

**"不懂 CORS 语义"的含义**：nginx 要做 origin 白名单得自己写 `map` + `if`；写不对时它**仍然会把头加出去** → 变成"**假授权**"。Gateway 会**真的拒绝**。

### 怎么选（决策表）

| 场景 | 建议 |
|---|---|
| 有 nginx 且它是统一入口 | **nginx 做**（最靠前、预检短路不消耗网关资源） |
| 没 nginx / 想让网关统一管 | **Gateway 做**（内建规范，配置集中） |
| nginx 只做纯转发 | **Gateway 做** |
| ❌ **两层都配** | **千万别** —— 响应里会出现**两个** `Access-Control-Allow-Origin` → 浏览器直接报错 |

### 一句话原则

> **CORS 只在"最外层"处理一次** —— 谁离浏览器最近谁处理最省事（能在最前面短路预检）。

> ⚠️ **nginx 经典坑**：`add_header` 是**覆盖式继承** —— 子 `location` 里只要写了一个 `add_header`，父层的 `add_header` **全部失效**（CORS 头莫名丢失通常就是这么丢的）。

### 跨域本身要掌握的 4 条（通用，与 Gateway 无关）

| # | 要掌握 | 要点 |
|---|---|---|
| ① | **同源判定** | 同源 = **协议 + 域名 + 端口** 三者全同；任一不同 = 跨域 |
| ② | **跨域的本质** | **不是"请求发不出去"** —— 是**浏览器不让 JS 读响应**；<br>但**非简单请求**预检不过 → **真的不发** |
| ③ | **CORS = 服务端授权** | 核心头 `Access-Control-Allow-Origin`；<br>⚠️ **`Allow-Credentials: true` 不能与 `Allow-Origin: *` 同用** |
| ④ | **简单请求 vs 预检** | 简单请求（GET/POST + 标准头 + 表单类 Content-Type）直接发；<br>其它（`PUT/DELETE`、自定义头、`application/json`）→ 先 OPTIONS 预检；<br>**预检要求服务端能响应 OPTIONS** |

> **本质一句话**：CORS 是**浏览器强制**的"服务端授权"机制 —— 服务端不加头，浏览器就不让页面拿到数据。**跟 curl / Postman 无关。**

---

## 7.8 值不值得深挖

### 掌握边界（明确"这里只用记什么"）

| 内容 | 判断 |
|---|---|
| **CORS 处理不在过滤器链**（在 HandlerMapping 层） | ✅ **必须记**（面试答这个 = 加分，多数人答错） |
| 两种配置 | ✅ 记 |
| 预检要"匹配路由 + 有配置" | ✅ **必须记**（生产踩过） |
| `add-to-simple-url-handler-mapping` | ✅ **必须记**（解药） |
| reactive 包 vs Servlet 包 | ✅ 记（自己写代码时） |
| **架构判断：同源 > CORS** | ✅ **必须记**（见下） |
| `DefaultCorsProcessor` 源码逐行 | ⏭️ 翻篇 |
| CORS 头细节（`Vary` / `credentials` 与 `*` 冲突 / `maxAge`） | ⏭️ 用时再查 |
| 预检的完整规则（哪些算"简单请求"） | ⏭️ 知道"自定义头 / JSON / PUT 会触发预检"即可 |

### 架构视角：**同源 > CORS**（★ 最有价值的答法）

> **跨域的根本解法是"同源"** —— 前端和 API 走同一个域名，用 nginx / Vite 代理 `/api`。
> **同源就不需要 CORS**。只有"前端独立域名部署"或"开放给第三方"时，才需要配 CORS。

**判断顺序**（架构设计时先问这三句）：
1. 能不能**同源**？（前端和 API 同一域名 + 代理）→ 能，则**不需要 CORS**
2. 必须跨域的话，**在哪一层处理**？（最外层处理一次，别重复）
3. 用**谁**处理？（nginx = 靠前但不懂规范；网关 = 懂规范但在后面）

### 面试权重

**低-中**。CORS 是**配置细节**，不是架构决策；但这两条能答出来就是加分：
- "**处理在 HandlerMapping 层，不在过滤器链**"
- "**同源 > CORS**"（以及"nginx 和网关只在一层处理"）

---

## 7.9 Self-Test

1. CORS 是在 Gateway 的过滤器链里处理的吗？如果不是，在哪一层？
2. 路由级 CORS 是创建的 Gateway Filter 吗？（它实际做了什么）
3. 全局 CORS 和路由级 CORS 的配置位置分别在哪？路由级没有 Path 谓词时用哪个 pattern？
4. 一条路由配了 `Method=GET` + `metadata.cors`，浏览器发 OPTIONS 预检会发生什么？为什么？
5. `add-to-simple-url-handler-mapping: true` 做了什么？它挂的是全局还是路由级配置？
6. 自己写 `CorsConfigurationSource` 时，为什么要用 `org.springframework.web.cors.reactive` 包？
7. 为什么说"**同源 > CORS**"？架构设计时判断跨域的三句话是什么？
8. nginx 和 Gateway 处理 CORS 的区别是什么（"懂不懂语义"指什么）？
9. 两层都配 CORS 会发生什么？为什么？
10. `Access-Control-Allow-Credentials: true` 能和 `Access-Control-Allow-Origin: *` 一起用吗？
11. **简单请求**在 CORS 不允许时：请求发出去了吗？下游执行了吗？JS 能读吗？
12. **非简单请求**预检失败时：真请求发出去了吗？下游知道有这个请求吗？
13. CORS 能防 CSRF 吗？为什么？（答不出这题说明还没理解"简单请求的副作用"）

---

## 7.10 Open Questions

> 用法：直接在这里追加，或对话里说编号（如「7.5 没懂」）。

| # | 疑问 | 状态 |
|---|---|---|
| | | |
