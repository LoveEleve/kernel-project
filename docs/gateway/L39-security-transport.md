# Lesson 39: Security & Transport — Spring Security 集成 / TLS / HTTP2

> 课程索引：`../gateway-学习清单.md` → **Stage 7**（内置能力补全）· Lesson 39
> 前置：**L07**（CORS——同为"边缘安全"话题）· **L16**（LB/实例元数据——XFF 与可信代理背景）· **L36**（`principalNameKeyResolver`——本课认证后它才真正可用）
> 口径：🔴 ① **网关做认证的两种模式**（token 校验 vs OAuth2 客户端）② **HTTPS 在哪里终止**（Nginx / 网关 / 服务）与证书管理
> 【v5 补】**安全响应头**：`SecureHeadersGatewayFilterFactory`
> 版本口径：demo 跑的是 **SCG 4.1.5 / Spring Boot 3.2.5 / Spring Security 6.2（WebFlux）**；实测均基于此。

**一句话（先说人话）**：网关站在最边上，最适合干三件事——**验"你是谁"（认证）、给出口套上 TLS（HTTPS）、统一贴安全响应头**。本课把这三件事都跑出真实行为。

**本课路线图**：

1. **39.1 三组实验**——安全头（SCG vs Spring Security 两套来源）/ 认证（401→200）/ TLS + HTTP/2（ALPN、h2c、X-Forwarded-Proto）；
2. **39.2 认证**——两种模式的取舍：token 校验（资源服务器）vs OAuth2 客户端（登录），以及"认证 / 鉴权"放哪层；
3. **39.3 安全头源码**——`SecureHeaders` 的 8 个头、`addIfAbsent`、配置方式，以及与 Spring Security 默认头的叠加关系；
4. **39.4 TLS**——三种终止位置的对比、证书与 `X-Forwarded-*`；
5. **39.5 HTTP/2**——怎么开、怎么验、逐跳的含义；
6. **39.6–39.9**——坑表 / 面试问答 / Self-Test / Open Questions。

---

## 39.1 三组实验：先看现象

### 实验 A：安全响应头（`SecureHeaders`）

落盘改动：

- `application.yml` 加路由 `secure-lab`（挂 `- SecureHeaders` 过滤器）+ 全局参数覆盖：

```yaml
      filter:
        secure-headers:
          frame-options: SAMEORIGIN      # 覆盖默认 DENY（演示可配置）
          disable: x-xss-protection      # 禁用某个头（小写头名）
        local-response-cache:
          enabled: true
```

- 路由：

```yaml
        - id: secure-lab
          uri: http://localhost:8081
          predicates:
            - Path=/secure-lab/**
          filters:
            - StripPrefix=1
            - SecureHeaders
```

实测（认证通过的请求；完整响应头）：

```
HTTP/1.1 200 OK
Strict-Transport-Security: max-age=631138519
X-Frame-Options: SAMEORIGIN
X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer
Content-Security-Policy: default-src 'self' https:; font-src 'self' https: data:; img-src 'self' https: data:; object-src 'none'; script-src https:; style-src 'self' https: 'unsafe-inline'
X-Download-Options: noopen
X-Permitted-Cross-Domain-Policies: none
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Expires: 0
X-XSS-Protection: 0
```

**意外收获：这些头来自两个不同的机制。** 对照一个**没挂** `SecureHeaders` 的路由（`/hello`）：

```
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Expires: 0
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
X-XSS-Protection: 0
Referrer-Policy: no-referrer
```

`/hello` 上这些头是 **Spring Security 的默认安全头**（XFO `DENY`、XSS `0`、`nosniff`、`Cache-Control: no-store` 等）；
`/secure-lab` 上是**两套叠加**：SCG 覆盖了 `X-Frame-Options` 为 `SAMEORIGIN`、`disable` 生效（SCG 没加 XSS 头），
但 Spring Security 默认的 `X-XSS-Protection: 0`、`Cache-Control: no-store` 依然在。**配置安全头时要意识到来源**（39.3 展开）。

### 实验 B：网关做认证（Spring Security）

落盘改动：

- `pom.xml`：加 `spring-boot-starter-security`；
- `application.yml`：内存用户（Demo 用）；`SecurityConfig`：

```java
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)       // Demo 简化：否则带 body 的 POST 路由会被拦
            .authorizeExchange(ex -> ex
                .pathMatchers("/secure-lab/**").authenticated()   // ★ 只保护这一组路由
                .anyExchange().permitAll())                       // 其余 lab 路由照常放行
            .httpBasic(Customizer.withDefaults())                 // Demo 用 Basic；生产换 JWT/OAuth2
            .build();
    }
}
```

实测：

```
① 无凭证      → 401 Unauthorized
               WWW-Authenticate: Basic realm="Realm"
② 错误密码    → 401
③ 正确凭证    → 200
④ 其他路由    → 200（不受影响）
```

三个可以带走的点：

- 认证发生在**网关的 WebFilter 链**里（比路由/过滤器链更早），拦下来的请求**根本到不了任何路由**；
- 认证通过后 `exchange.getPrincipal()` 有值——L36 的 `principalNameKeyResolver`（按登录用户限流）这时才真正可用；
- 原请求的 `Authorization` 头会**原样转发给下游**（网关不做剥离）——实测 provider 端拿到
  `authorization = Basic ZGVtb3VzZXI6ZGVtb3Bhc3M=`（demouser:demopass 的 Base64）——要么下游再验一遍，要么在网关统一转换/剥离。

（本 lab 用 Basic 是为了**最小可运行**；生产替换成 39.2 的两种模式即可，`SecurityConfig` 的骨架不变。）

### 实验 C：TLS 终止 + HTTP/2

落盘改动：`keytool` 自签证书 + 起一个**独立 TLS 实例**（不动 8080 HTTP 实例）：

```bash
# 1) 自签证书（PKCS12，SAN 含 localhost/127.0.0.1）
keytool -genkeypair -alias gateway -keyalg RSA -keysize 2048 -storetype PKCS12 \
  -keystore /tmp/opencode/gateway.p12 -validity 3650 \
  -storepass changeit -keypass changeit \
  -dname "CN=localhost, OU=lab, O=demo, L=shanghai, ST=shanghai, C=CN" \
  -ext "SAN=dns:localhost,ip:127.0.0.1"

# 2) 起 TLS 实例（8443）+ 显式开启 HTTP/2
java -jar demo-gateway.jar \
  --server.port=8443 --server.ssl.enabled=true \
  --server.ssl.key-store=/tmp/opencode/gateway.p12 \
  --server.ssl.key-store-password=changeit --server.ssl.key-store-type=PKCS12 \
  --server.ssl.key-alias=gateway \
  --server.http2.enabled=true          # ★ 实测：不显式开，协商不到 h2
```

实测：

```
https+h2:  version=2 code=200          ← curl -k --http2 https://127.0.0.1:8443/hello
ALPN protocol: h2                      ← openssl s_client -alpn h2,http/1.1
http+h2c:  version=2 code=200          ← curl --http2-prior-knowledge http://127.0.0.1:8082/hello
x-forwarded-proto= https | x-forwarded-port= 8443   ← 回源请求头（下游看到的是明文 HTTP）
```

三个结论：

- **TLS 终止在网关**：客户端 → 网关走 HTTPS，网关 → 下游仍是**明文 HTTP**（`x-forwarded-proto: https` 告诉下游"外面那跳是加密的"）；
- **HTTP/2 实测要显式开启**：不开 `server.http2.enabled=true` 时，TLS 上 `curl --http2` 退回 `1.1`、openssl 报 `No ALPN negotiated`；
- **h2 在 TLS 上走 ALPN；明文上走 h2c**（prior-knowledge/upgrade），两条路都验过。

---

## 39.2 认证：两种模式 + 放在哪层（本课 🔴①）

### 两种模式

| | **Token 校验**（资源服务器） | **OAuth2 客户端**（登录） |
|---|---|---|
| 令牌从哪来 | 客户端带来的 **Bearer JWT** | **网关自己**走授权码流程换取 |
| 网关状态 | **无状态**（本地验签，不存 session） | **有状态**（WebSession 存 token） |
| 验什么 | `issuer-uri`/JWK 本地验签 + 标准声明 | 登录回调、token 刷新 |
| 下游怎么拿到令牌 | 原请求的 `Authorization` 原样转发 | 用 **`TokenRelay`** 把网关持有的 token 注入下游请求 |
| 适用 | 前后端分离 / API 网关统一鉴权 | BFF / 浏览器应用（不想让前端管 token） |

配置示意（骨架，生产替换 lab 的 Basic）：

```yaml
# 模式一：资源服务器（JWT）
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://idp.example.com/issuer
```

```java
// 模式一：SecurityConfig 里改成
http.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
```

```yaml
# 模式二：OAuth2 客户端 + TokenRelay
spring:
  security:
    oauth2:
      client:
        registration: { ... }        # client-id/secret/authorization-grant-type 等
```

```yaml
# 模式二：路由里加 TokenRelay（把网关持有的 access token 传给下游）
filters:
  - TokenRelay=myregistrationid
```

`TokenRelay` 是 SCG 内置过滤器（4.1.5 的 jar 里有 `TokenRelayGatewayFilterFactory`，文档口径）：

> A Token Relay is where an OAuth2 consumer acts as a Client and forwards the incoming token to
> outgoing resource requests. … Spring Cloud Gateway can forward OAuth2 access tokens downstream.

### 认证 / 鉴权放哪层

- **认证（authentication）放网关**：统一入口、统一凭证校验，服务不用各自实现；
- **粗粒度授权（authorization）可以放网关**：按路径/方法/角色（`pathMatchers("/admin/**").hasRole("ADMIN")`）；
- **细粒度业务鉴权必须放服务内**："这笔订单是不是你的"网关不知道，也不该知道——网关做安全**边界**，不做业务规则。

---

## 39.3 `SecureHeaders` 源码：8 个头 + `addIfAbsent`

### 默认的 8 个头（`SecureHeadersProperties` 源码常量）

| 头 | 默认值 | 作用 |
|---|---|---|
| `X-XSS-Protection` | `1 ; mode=block` | 旧浏览器 XSS 审计（现代浏览器已废弃，Security 默认给 `0`） |
| `Strict-Transport-Security` | `max-age=631138519` | HSTS：强制浏览器以后走 HTTPS（≈20 年） |
| `X-Frame-Options` | `DENY` | 禁止被 iframe 嵌套（防点击劫持） |
| `X-Content-Type-Options` | `nosniff` | 禁止浏览器嗅探 MIME |
| `Referrer-Policy` | `no-referrer` | 不发 Referer |
| `Content-Security-Policy` | `default-src 'self' https:; …` | 限制资源加载来源 |
| `X-Download-Options` | `noopen` | IE：下载文件不直接打开 |
| `X-Permitted-Cross-Domain-Policies` | `none` | 禁止跨域策略文件 |

### 两个关键行为（源码）

```java
// SecureHeadersGatewayFilterFactory.apply（节选）
return chain.filter(exchange).then(Mono.fromRunnable(() -> {
    if (isEnabled(disabled, X_FRAME_OPTIONS_HEADER)) {
        headers.addIfAbsent(X_FRAME_OPTIONS_HEADER, config.getFrameOptions());   // ★ 下游/他人已设置就不覆盖
    }
    ...
}));
```

- **写响应头发生在 `chain.filter` 之后**（响应阶段），所以它永远晚于下游写头；`addIfAbsent` 保证**不覆盖下游已有的头**；
- 配置三件套：全局参数 `spring.cloud.gateway.filter.secure-headers.*`、路由 args 覆盖、`disable`（**小写**头名列表）禁用某些头；
- 与 Spring Security 的**默认安全头叠加**（实验 A）：两边都管安全头时，明确"谁主导"——要么关掉一边（Security 的 headers DSL 或 SCG 的 disable），要么接受观察到的合并结果（本次实测：XFO 取 SCG 的 `SAMEORIGIN`、XSS 取 Security 的 `0`）。

---

## 39.4 TLS：在哪里终止（本课 🔴②）

### 三种终止位置

| 终止点 | 优点 | 代价与注意 |
|---|---|---|
| **Nginx / ELB / CDN** | 证书集中管理、TLS 卸载、可叠加 WAF | 到网关是**内网明文**；必须正确配置 `X-Forwarded-*` 与**可信代理链**（否则 XFF 可伪造，L36 提过） |
| **网关**（本 lab） | 少一跳、网关统一证书与路由策略 | 网关承担 TLS 开销；下游仍是内网明文（要零信任就继续往下加密） |
| **服务内** | 端到端加密（零信任） | 证书管理分散（每服务一套）、性能开销大 |

**判断口径**：证书想集中管、想省 CPU → 终止在 Nginx/网关；合规要求"内网也不许明文" → 必须到服务（或至少网关到服务也上 TLS）。

### 证书管理要点

- 本 lab 用**自签证书**（`keytool -genkeypair`，PKCS12）——只用于本地验证；生产必须 CA 签发（或 ACME 自动续期）；
- `server.ssl.*` 指向 keystore；**证书和密码不能进 git**（lab 放 `/tmp` 就是演示性规避）；
- 轮换：Boot 3.2 支持 SSL Bundle（`spring.ssl.bundle.*`）做多证书/重载，运维上要演练"证书过期"；`HSTS` 一旦下发，回退 HTTP 会很难（浏览器强制 HTTPS）；
- 终止在网关后，下游拿到的 scheme 是 `http`——**必须靠 `X-Forwarded-Proto` 感知原始协议**（实验 C 实测 `x-forwarded-proto: https`；SCG 的 `ForwardedHeadersFilter` 自动回填 `Forwarded`/`X-Forwarded-Proto/Port/Host`）。

---

## 39.5 HTTP/2：怎么开、怎么验、逐跳

- **开启**：`server.http2.enabled=true`——**Boot 3.2.5 默认是 `false`**（源码 `org.springframework.boot.web.server.Http2`：
  `private boolean enabled = false;`），不显式开启时 TLS 上协商不到 h2（实测 `No ALPN negotiated` 退回 1.1）；
- **TLS 上**：走 **ALPN** 协商（`h2` / `http/1.1`）——`openssl s_client -alpn h2,http/1.1` 可验证；
- **明文上**：走 **h2c**（`--http2-prior-knowledge` 或 Upgrade）；gRPC 就是 h2c 的典型用户；
- **验证命令**：

```bash
curl -k --http2 https://127.0.0.1:8443/hello              # version=2
curl --http2-prior-knowledge http://127.0.0.1:8082/hello  # version=2（h2c）
openssl s_client -connect 127.0.0.1:8443 -alpn h2         # ALPN protocol: h2
```

- **HTTP/2 是逐跳的**：客户端 ↔ 网关可以是 h2，网关 ↔ 下游仍可以是 HTTP/1.1（回源客户端独立配置）——两端协议互不影响；
- **收益与边界**：多路复用（一条连接并发多流，缓解 HTTP/1.1 队头阻塞）、头压缩（HPACK）；但 TCP 层队头阻塞仍在——这正是 HTTP/3（QUIC）要解决的问题。网关侧主要是"减少连接数/提升并发效率"。

---

## 39.6 坑表：六种"配错了"的下场

| 配法 | 现象 | 原因 |
|---|---|---|
| 加了 security 却没写放行规则 | **全站 401**（包括所有原 lab 路由、actuator） | 默认 `anyExchange().authenticated()`；必须显式 `permitAll` 白名单 |
| WebFlux 默认 CSRF 拦 POST | 所有 POST 路由 403（含网关自己的改写实验） | Security 默认开 CSRF；API 网关通常关掉并单独评估 csrf 策略 |
| SCG `SecureHeaders` + Security 默认头同时开 | 头重复/来源混乱（实测 XFO 取 SCG、XSS 取 Security） | 两套机制各自写头；先定"谁主导" |
| 忘了 `server.http2.enabled=true` | 一直协商失败退回 HTTP/1.1（`No ALPN negotiated`） | Boot 3.2.5 默认 `false`（源码 `org.springframework.boot.web.server.Http2`），实测一致 |
| 自签证书直接上生产 | 客户端不信任/告警 | 自签仅 lab；生产 CA/ACME + 轮换演练 |
| TLS 终止在网关后，下游按 `http` 判断协议 | 生成的回调/重定向 URL 是 http（混合内容） | 下游要读 `X-Forwarded-Proto`（可信代理链） |

---

## 39.7 面试问答（一句话答案 + 追问弹药）

1. **"网关做认证的两种模式？"**
   一句话：**token 校验**（无状态、本地验 JWT）与 **OAuth2 客户端**（有状态、登录换 token + `TokenRelay` 转发）。
   弹药：两种模式的会话/令牌/下游传递差异表（39.2）。
2. **"认证和鉴权，哪些放网关？"**
   一句话：认证统一放网关；粗粒度授权可以放；**细粒度业务鉴权放服务内**。
3. **"HTTPS 在哪终止？"**
   一句话：想集中管证书/卸载就 Nginx/ELB/网关；要零信任就到服务；无论哪种，下游要靠 `X-Forwarded-Proto` 感知原协议。
4. **"HSTS 是干什么的？"**
   一句话：让浏览器以后强制走 HTTPS（实验里 SCG 默认 `max-age=631138519`）；下发要谨慎（回退困难）。
5. **"HTTP/2 对网关有什么价值？怎么开？"**
   一句话：多路复用+头压缩，减少连接、提升并发；`server.http2.enabled=true`，TLS 走 ALPN、明文走 h2c，且是逐跳协议。
6. **"`SecureHeaders` 和 Spring Security 的头会冲突吗？"**
   一句话：会叠加（实测两种来源并存）——`addIfAbsent` + 两套默认值，必须定主导方。

---

## 39.8 掌握边界（值不值得深挖）

| 内容 | 口径 |
|---|---|
| OAuth2 授权码流程细节（PKCE/refresh） | ⚪ 项目用到再展开（L45 之后再排） |
| TLS 握手/证书链细节 | ❌ 知道"终止点 + XFF + 证书管理"即可 |
| **两种认证模式的区别与选型** | ✅ **本课 🔴①** |
| **HTTPS 终止位置与证书管理** | ✅ **本课 🔴②** |
| **SecureHeaders 8 个头 + 两套来源叠加** | ✅ 要会（排障高频） |
| HTTP/2 开启与验证 | ⚪ 了解（能开能验即可） |

---

## 39.9 Self-Test

1. 网关认证的两种模式是什么？各自的"令牌从哪来、下游怎么拿到"？
2. 认证通过后 `exchange.getPrincipal()` 有值——这和 L36 的哪个 KeyResolver 呼应？
3. `SecureHeaders` 加哪 8 个头？为什么它是 `addIfAbsent`、为什么写在 `chain.filter` 之后？
4. 实验 A 里 `X-XSS-Protection: 0` 来自哪里？为什么 `disable` 没把它去掉？
5. HTTPS 三种终止位置各有什么代价？终止在网关后，下游怎么知道原始协议是 https？
6. HTTP/2 为什么"TLS 走 ALPN、明文走 h2c"？怎么用命令验证？为什么说是"逐跳"的？
7. （架构）一个前后端分离系统：认证放网关还是服务？如果换成浏览器 BFF，方案怎么变？

---

## 39.10 Open Questions

- [ ] OAuth2 两种模式只写了配置骨架，**没有跑通真实 IdP 流程**（资源服务器验签 / 授权码登录 + TokenRelay）——待补实验。
- [ ] HTTP/2 下 WebSocket/SSE 的网关行为（流式场景）——与 L28/L41 并线。
- [ ] SSL Bundle 的证书轮换/热加载实践（`spring.ssl.bundle.*`）——运维主题，L49/L50 并线。
