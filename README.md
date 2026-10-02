# xhs-demo · 框架学习沙箱

> 目的：把 `xhs-project` 里用到的框架，一个个拆成**能跑、能改、能观察**的最小样例。
> 版本与 `xhs-project` 严格对齐：**Java 17 / Spring Boot 3.2.5 / Spring Cloud 2023.0.3 / Spring Cloud Alibaba 2023.0.1.2**。

## 当前骨架（第一阶段：Spring Cloud Gateway）

```
xhs-demo/
├── pom.xml                 # 父 POM：BOM 版本管理（不继承 spring-boot-starter-parent，和 xhs-project 一致）
├── demo-provider/          # 下游服务（Spring MVC，8081）—— 把收到的请求原样回显
└── demo-gateway/           # 网关（WebFlux，8080）—— 路由 + 自定义 GlobalFilter
```

为什么要有 `demo-provider`：学网关必须有个真实下游，否则你看不到"路径被改写了吗、Header 加上了吗、TraceId 传下去了吗"。

## 运行

```bash
# 1) 先起下游
mvn -pl demo-provider spring-boot:run

# 2) 再起网关（另开终端）
mvn -pl demo-gateway spring-boot:run
```

## 验证（网关到底做了什么）

```bash
# 直连下游：路径是 /echo/abc，没有网关加的 Header
curl -s http://localhost:8081/echo/abc

# 走网关：路径是 /provider/echo/abc，StripPrefix 后下游收到 /echo/abc
curl -s http://localhost:8080/provider/echo/abc

# 观察响应头里有没有 X-Demo-Gateway-Version
curl -si http://localhost:8080/provider/echo/abc | head -20

# 慢接口：调小 response-timeout 观察超时（10s 超时 / 3s 正常返回）
curl -s "http://localhost:8080/provider/slow?ms=3000"
```

对照两次 `curl` 的返回体：走网关时 `headers` 里会多出 `X-Demo-Trace-Id`、`X-Demo-From-Gateway`、`X-Demo-Route`，这就是过滤器链的作用。

## 学习路线（每一阶段都可独立跑通）

| 阶段 | 目标 | 在 xhs-project 的落点 |
|---|---|---|
| 1 ✅ | 路由 + 内置过滤器（StripPrefix/AddHeader）+ 自定义 GlobalFilter | `my-xhs-gateway/filter/*` |
| 2 | 自定义 `GatewayFilterFactory`（可配置、可复用，而非硬编码） | `GrayRouteFilter`/`ApiVersionFilter` |
| 3 | 接 Nacos：`uri: lb://demo-provider` + 服务发现/配置中心 | `spring.cloud.nacos.*` |
| 4 | 接 Sentinel：网关限流（`GatewayFlowRule`）+ 熔断降级 | `RateLimitFilter` + `sentinel-spring-cloud-gateway-adapter` |
| 5 | 鉴权 + 防重放：JWT 校验、HMAC 签名、nonce（Redis） | `GatewayAuthFilter`/`HmacSignatureFilter` |
| 6 | 灰度/染色：把标签放进 exchange attributes，交给自定义 LoadBalancer 选实例 | `TrafficColoringFilter` + `ZonePreferenceFilter` |
| 7 | 可观测：TraceId 全链路、Prometheus 指标、优雅停机 | `RequestLogFilter`、`GatewayMetricsConfig` |

## 关键概念（先记住这三条）

1. **Gateway 是 WebFlux 的**：不能引 `spring-boot-starter-web`，不能有阻塞调用，请求级数据放 `exchange.getAttributes()` 而不是 ThreadLocal。
2. **Filter 分两种**：`GlobalFilter`（全局，代码注册）和 `GatewayFilter`（绑在路由上，yml 配置）。两者最终合成一条链，按 `Order` 排序执行。
3. **`uri: lb://xxx` 才会走负载均衡**；写死 `http://host:port` 只是静态转发。

## 参考

- 真实实现与面试话术：`../xhs-project/my-xhs-gateway/docs/CODE-REVIEW.md`
- 组件深挖：`../xhs-project/docs/interview/xhs/40-53`
- Tomcat 面试单题详解（14 篇，含 lab 对应关系与自测）：`docs/tomcat/`（配套实验：`tomcat-lab/`）
