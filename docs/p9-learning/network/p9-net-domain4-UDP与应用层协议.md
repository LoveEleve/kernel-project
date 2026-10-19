# p9-net-domain4 · UDP+应用层协议（P9 深度实验）

> 网络轨道域 4/10。覆盖：UDP/HTTP/DNS/TLS/HTTP2/HTTP3 QUIC/WebSocket

## 实验环境

```
工具: dig/curl/openssl/nc
外网: www.baidu.com（经代理）
```

---

## 实验 1：UDP 基础

### UDP vs TCP（P9 对比）

| | UDP | TCP |
|---|---|---|
| 连接 | 无连接 | 面向连接 |
| 可靠性 | 不保证（丢包不管） | 保证（重传/确认） |
| 边界 | 数据报有边界 | 字节流无边界 |
| 头部 | 8 字节 | 20 字节+ |
| 适用 | 视频/DNS/游戏/QUIC | HTTP/文件传输/SSH |

### UDP 应用场景

```
DNS：查询简单（一问一答，不需要连接开销）
视频/音频：允许丢帧（实时性>可靠性）
游戏：状态同步（丢包可补下一帧）
QUIC/HTTP3：基于 UDP（自建可靠传输）
```

---

## 实验 2：DNS 解析（dig 实测）

```
dig baidu.com +short
→ 110.242.74.102
→ 111.63.65.103
→ 111.63.65.247
```

### DNS 解析流程

```
① 客户端 → 本地 DNS（递归查询）
② 本地 DNS → 根 DNS → 顶级域(.com) → 权威 DNS（迭代查询）
③ 结果缓存（TTL 过期前不重复查询）
记录类型：
  A：域名→IPv4
  AAAA：域名→IPv6
  CNAME：域名→域名（别名）
  NS：域名→DNS 服务器
```

### P9 追问链

- **Q：递归 vs 迭代查询？** → 递归=本地 DNS 全程代查；迭代=本地 DNS 逐级问（根→顶级→权威）
- **Q：DNS 用 TCP 还是 UDP？** → 查询用 UDP（小包）；区域传送用 TCP（大数据）
- **Q：CDN 怎么调度？** → DNS 返回最近边缘节点 IP（GSLB 基于用户 IP/负载）

---

## 实验 3：HTTP 请求/响应（curl -v 实测）

```
> GET http://www.baidu.com/ HTTP/1.1
> Host: www.baidu.com
< HTTP/1.1 200 OK
< Content-Length: 2381
< Content-Type: text/html
< Connection: keep-alive
```

### HTTP 核心概念

```
请求：方法(GET/POST/PUT/DELETE) + URL + 版本 + 头 + 体
响应：版本 + 状态码(200/301/404/500) + 头 + 体
Keep-Alive：TCP 连接复用（默认 HTTP/1.1）
Content-Length：报文长度（chunked 时不用）
```

---

## 实验 4：TLS 握手（curl -v 实测）

```
TLSv1.3 Client Hello → Server Hello → Certificate → Server key exchange → Finished
TLSv1.2 Client key exchange → Change cipher spec
```

### TLS 握手流程（TLS 1.2 简版）

```
① Client Hello：支持的 TLS 版本/密码套件/随机数
② Server Hello：选定密码套件 + 证书（含公钥）
③ 证书验证：客户端验证证书链（CA→中间→服务器）
④ 密钥交换：客户端用服务器公钥加密预主密钥发给服务器
⑤ 双方用预主密钥生成会话密钥 → 后续加密通信
```

### TLS 1.3 改进

```
1-RTT 握手（1.2 是 2-RTT）；0-RTT 恢复（首次后 0-RTT）
移除不安全算法（RSA 密钥交换等）；前向保密（ECDHE）
```

### P9 追问链

- **Q：TLS 1.2 vs 1.3？** → 1.3 是 1-RTT（1.2 是 2-RTT）；0-RTT 恢复；更安全（移除旧算法）
- **Q：证书链验证？** → 根 CA→中间 CA→服务器证书（逐级验证签名）
- **Q：HTTPS 为什么安全？** → TLS 加密（防窃听）+ 认证（防冒充）+ 完整性（防篡改）

---

## 实验 5：HTTP2（ALPN 协商实测）

```
ALPN: curl offers h2,http/1.1
ALPN: server accepted http/1.1   ← 百度未启用 h2，但协商过程可见
```

### HTTP2 核心改进

```
二进制分帧：文本→二进制（更高效解析）
多路复用：一个 TCP 连接并发多个流（解决队头阻塞）
头部压缩：HPACK（静态表+霍夫曼编码）
服务器推送：主动推送资源（可选）
```

### P9 追问链

- **Q：HTTP2 解决什么？** → 应用层队头阻塞（多路复用）+ 重复头部（HPACK）+ 连接数
- **Q：HTTP2 还有队头阻塞吗？** → TCP 层还有（丢包阻塞所有流）；HTTP3/QUIC 解决

---

## 补充：HTTP3/QUIC（P9 趋势）

```
QUIC：基于 UDP 的可靠传输（Google）
  → 0-RTT 建连（1-RTT 恢复）
  → 连接迁移（IP 变化不断连，如 WiFi→4G）
  → 无 TCP 队头阻塞（每个流独立丢包恢复）
HTTP3：HTTP over QUIC（RFC 9114）
  → 取代 HTTP2 的 TCP 队头阻塞
```

**P9 答法**："HTTP3 基于 QUIC（UDP）：0-RTT 建连、连接迁移、无 TCP 队头阻塞。ALPN 协商 h3。"

---

## 补充：WebSocket

```
握手：HTTP Upgrade（101 Switching Protocols）
  GET /ws HTTP/1.1
  Upgrade: websocket
  Connection: Upgrade
  Sec-WebSocket-Key: ...（防缓存代理）
帧：二进制帧（客户端→服务端需掩码）
心跳：Ping/Pong 帧（保活）
适用：实时通信（聊天/推送/游戏）
```

**P9 答法**："WebSocket：HTTP Upgrade 握手升级（101）→ 二进制帧全双工。心跳 Ping/Pong 保活。适用实时通信。"

---

## 补充：HTTP 演进（P9 趋势）

| 版本 | 关键改进 |
|---|---|
| HTTP/1.0 | 短连接（每请求一连接） |
| HTTP/1.1 | Keep-Alive（连接复用）+ 管线化（有限） |
| HTTP/2 | 二进制分帧 + 多路复用 + HPACK + 服务器推送 |
| HTTP/3 | QUIC（UDP）+ 0-RTT + 连接迁移 + 无队头阻塞 |

## 补充 #106：HTTP 缓存机制（生产重要）

### 两级缓存

```
① 强缓存（不发请求，直接用本地缓存）：
   Cache-Control: max-age=3600   ← 1 小时内直接用
   Expires: <过期时间>            ← HTTP/1.0 遗留
   → 命中：不发请求（200 from cache）

② 协商缓存（发请求问服务器是否变化）：
   ETag: "abc123" / If-None-Match: "abc123"  ← 比对标识
   Last-Modified: <时间> / If-Modified-Since: <时间>  ← 比对时间
   → 未变：304 Not Modified（不返回体）
   → 变了：200 + 新内容
```

### P9 追问链

- **Q：强缓存 vs 协商缓存？** → 强缓存不发请求（max-age）；协商缓存发请求比对（ETag/Last-Modified）
- **Q：304 怎么产生？** → 协商缓存：If-None-Match 与 ETag 一致 → 304（省带宽）
- **Q：Cache-Control vs Expires？** → Cache-Control 更精确（max-age 秒数）；Expires 是绝对时间（HTTP/1.0）

## 补充 #107：HTTP 状态码/方法语义

### 状态码分类

```
1xx：信息（101 Switching Protocols = WebSocket 升级）
2xx：成功（200 OK / 201 Created / 204 No Content）
3xx：重定向（301 永久 / 302 临时 / 304 Not Modified）
4xx：客户端错误（400 Bad Request / 401 Unauthorized / 403 Forbidden / 404 Not Found）
5xx：服务器错误（500 Internal / 502 Bad Gateway / 503 Service Unavailable / 504 Gateway Timeout）
```

### 方法语义（幂等性/安全性）

| 方法 | 安全（无副作用） | 幂等（多次同结果） |
|---|---|---|
| GET | ✓ | ✓ |
| HEAD | ✓ | ✓ |
| POST | ✗ | ✗ |
| PUT | ✗ | ✓ |
| DELETE | ✗ | ✓ |
| PATCH | ✗ | ✗ |

**P9 答法**："GET 安全幂等；PUT/DELETE 幂等不安全；POST/PATCH 不幂等。重试用幂等方法（GET/PUT），避免重复扣款用幂等键。"

## 补充 #108：HTTP/1.1 队头阻塞（HTTP2 动机）

```
HTTP/1.1 问题：
  ① 应用层队头阻塞：请求必须按序响应（前面请求慢→后面全等）
     → 管线化（pipelining）有限改善（服务器仍按序处理）
  ② 头部重复：每个请求重复传 Host/User-Agent 等
  ③ 连接数限制：浏览器 6 连接/域名（并发受限）

HTTP/2 解决：
  多路复用：一个 TCP 连接并发多个流（帧交错）
  HPACK：头部压缩（静态表+霍夫曼）
  → 但 TCP 层队头阻塞仍在（丢包阻塞所有流）→ HTTP3/QUIC 解决
```

## 补充 #109：DNS 缓存层级（排障）

```
解析顺序（从近到远）：
① 浏览器缓存（DNS TTL）
② 操作系统缓存（/etc/hosts + nscd/systemd-resolved）
③ 本地 DNS（路由器/ISP DNS，递归查询）
④ 根/顶级/权威 DNS（迭代查询）

排障：
  dig 不带 +short 看完整流程（含 authoritative）
  清 OS 缓存：systemd-resolve --flush-caches
  看本地 DNS：cat /etc/resolv.conf
```

**P9 答法**："DNS 缓存层级：浏览器→OS→本地 DNS→递归 DNS。排障看 dig 完整输出（TTL/authoritative），清缓存 flush OS+浏览器。"

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 实验背书 |
|---|---|---|
| UDP vs TCP | UDP 无连接/有边界/8B 头；TCP 连接/流/20B+ 头 | 实验 1（对比表） |
| **DNS 解析** | 递归+迭代查询；UDP 查询；A/CNAME/NS 记录 | 实验 2（dig 实测） |
| HTTP 核心 | 请求/响应/状态码/Keep-Alive/Content-Length | 实验 3（curl 实测） |
| **TLS 握手** | Client Hello→证书→密钥交换→会话密钥；1.3 是 1-RTT | 实验 4（curl -v 实测） |
| **HTTP2** | 二进制分帧+多路复用+HPACK（ALPN 协商 h2） | 实验 5（ALPN 实测） |
| **HTTP3/QUIC** | 基于 UDP；0-RTT+连接迁移+无队头阻塞 | 补充（趋势） |
| WebSocket | HTTP Upgrade 101→二进制帧；Ping/Pong 心跳 | 实验 6（握手格式） |
| HTTP 演进 | 1.0 短连接→1.1 Keep-Alive→2 多路复用→3 QUIC | 补充（对比表） |
| **HTTP 缓存** | 强缓存(max-age 不发请求) + 协商缓存(ETag/304) | 补充 #106 |
| **状态码/方法语义** | 2xx 成功/3xx 重定向/4xx 客户端/5xx 服务器；GET 幂等安全 | 补充 #107 |
| **HTTP/1.1 队头阻塞** | 应用层按序响应（前面慢后面全等）；HTTP2 多路复用解决 | 补充 #108 |
| **DNS 缓存层级** | 浏览器→OS→本地 DNS→递归 DNS（排障看 dig 完整输出） | 补充 #109 |

## 相关文件

- 实验：dig/curl -v/openssl/nc 实测
- 抓包：TLS 握手流程（curl -v 输出）
