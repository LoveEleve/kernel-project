# polygateway-lab：polygateway 真实环境实验

> 目的：把 polygateway 放进**真实环境**（真实 Nacos + 真实 Spring MVC 应用 + 真实 SCG 网关）验证，
> 补 in-process 测试（29/32 个单测与桩 E2E）覆盖不到的**集成问题**。
> 口径：2026-10-05 实测；JDK 17.0.20.1 / Spring Boot 3.2.5 / Spring Cloud 2023.0.3 / SCA 2023.0.1.2 / Nacos 2.2.3 / polygateway 1.0.0-SNAPSHOT。

---

## 一、架构

```
lab-provider (8181, context-path=/api)   ─┐
lab-audit    (8182, 同一 jar 换服务名)     ─┤ 注册 Nacos（metadata 带 polygateway.web-endpoints / context-path）
                                          ↓
                                   Nacos 2.2.3 (8848)
                                          ↓
lab-gateway  (8180, SCG + Nacos + LB + polygateway)
   discovery.locator.enabled=false（关官方服务级路由）
   spring.cloud.nacos.discovery.heart-beat.enabled=true（★ 见发现 2）
   excludes: services=lab-provider + patterns=/internal/** + methods=POST
```

| 模块 | 端口 | 说明 |
|---|---|---|
| `lab-provider` | 8181 | Spring MVC + Nacos + `polygateway-spring-boot`（provider 侧自动发布端点） |
| `lab-audit` | 8182 | **同一 jar** 加 `--spring.application.name=lab-audit --server.port=8182`，验证服务作用域排除 |
| `lab-gateway` | 8180 | SCG WebFlux + Nacos + LoadBalancer + `polygateway-spring-boot`（gateway 侧生成端点路由） |

## 二、运行

```bash
# 0) 依赖：本机 Nacos（docker，见 docs/gateway/infra-nacos.md）
# 1) 安装 polygateway 到本地仓库（SNAPSHOT，未发布）
JAVA_HOME=/env/javaEnv/jdk17 mvn -f source-code/poly/polygateway/pom.xml install
# 2) 构建 lab
JAVA_HOME=/env/javaEnv/jdk17 mvn -f source-code/xhs-demo/polygateway-lab/pom.xml package -DskipTests
# 3) 启动（setsid 保证后台进程不被会话清理；先 provider 再 gateway 亦可，心跳会兜底）
setsid java -jar lab-provider/target/lab-provider-1.0-SNAPSHOT.jar &
setsid java -jar lab-provider/target/lab-provider-1.0-SNAPSHOT.jar --spring.application.name=lab-audit --server.port=8182 &
setsid java -jar lab-gateway/target/lab-gateway-1.0-SNAPSHOT.jar &
```

> ⚠️ 本机 shell 有 `http_proxy`，curl 访问 localhost 必须加 `--noproxy '*'`。

## 三、实测矩阵（2026-10-05，全部通过）

| # | 请求（经 8180） | 实测 | 证明点 |
|---|---|---|---|
| 1 | `GET /lab-provider/api/orders/42` | 200 `GET /api/orders/42` | 端点级路由 + `StripPrefix=1` + context-path 保留 |
| 2 | `GET /lab-provider/api/orders/special` | 200 | **更具体路由优先**（`/orders/special` 胜 `/orders/{id}`） |
| 3 | `POST /lab-provider/api/orders`（json） | 200 `POST /api/orders` | `Consumes` 谓词（自研补官方缺口） |
| 4 | `POST /lab-provider/api/orders`（text/plain） | **404** | Consumes 不匹配 → 无路由（非静默放行） |
| 5 | `POST /lab-provider/api/internal/reconcile` | **404** | **服务作用域排除**命中 `lab-provider` |
| 6 | `POST /lab-audit/api/internal/reconcile` | 200 `POST /api/internal/reconcile` | **同名端点在其他服务保留**（服务作用域生效的正面证据） |
| 7 | `GET /lab-provider/api/unknown` | 404 | 无路由 → 404 语义 |

**心跳刷新（新增端点/滚动发布实时生效）证据**：gateway 日志周期性出现

```
DEBUG i.g.l.p.g.WebEndpointRouteDefinitionLocator - Loaded 9 endpoint route definition(s)   [ask-Scheduler-1]
```

`9 = 5 (lab-audit) + 5 (lab-provider) − 1（被服务作用域排除的 /internal/reconcile）`——真实 `HeartbeatEvent` → `RefreshRoutesEvent` → 全量重算。

**Nacos metadata 证据**：实例 metadata 实见
`polygateway.web-endpoints=[{kind,id,patterns,methods,params,headers,consumes,produces}...]`、`polygateway.context-path=/api`。

## 四、真实环境发现的两个集成问题（in-process 测试覆盖不到，已修复）

### 发现 1：`polygateway-spring-boot` 把 SCG 传递进 MVC 应用 → provider 启动失败

- 现象：MVC provider 加 `polygateway-spring-boot` 后启动报
  `Spring MVC found on classpath, which is incompatible with Spring Cloud Gateway.`
- 根因：`polygateway-gateway` 把 `spring-cloud-starter-gateway` 声明为**非 optional** 依赖，经 `polygateway-spring-boot` 透传给 provider。
- 修复：`polygateway-gateway` + `polygateway-spring-boot` 中 SCG/LoadBalancer 改 `<optional>true</optional>`；
  验证：provider fat-jar 中 SCG 依赖数 1 → 0（体积 52MB → 33MB），gateway fat-jar 保留。

### 发现 2：SCA 2023 的 HeartbeatEvent 发布器是**条件创建** → 刷新静默失效

- 现象：`discovery.locator.enabled=false` 的网关在真实 Nacos 下**从不刷新路由**（无 `Loaded N` 日志）。
- 根因：SCA 2023 用 `NacosDiscoveryHeartBeatPublisher` 替代旧 `NacosWatch`，创建条件是三选一：
  ① `spring.cloud.nacos.discovery.heart-beat.enabled=true`；② `spring.cloud.gateway.discovery.locator.enabled=true`；
  ③ 存在 Spring Boot Admin 的 `InstanceDiscoveryListener`。
  polygateway 用户通常会关掉 ②（避免官方服务级路由）→ 心跳不发布 → polygateway 的刷新监听收不到事件。
- 修复/口径：Nacos 用户显式开 ①（lab-gateway 已配）；`polygateway` README「刷新依赖心跳」处已补充该说明。

## 五、停止

```bash
pkill -f "[l]ab-provider-1.0-SNAPSHOT.jar"
pkill -f "[l]ab-gateway-1.0-SNAPSHOT.jar"
```

## 六、关联

- 课程：`docs/gateway/L43-polygateway-extension.md`（§5.3 真实环境实测）
- 项目：`source-code/poly/polygateway/`（README「刷新依赖心跳」含 Nacos 配置说明）
