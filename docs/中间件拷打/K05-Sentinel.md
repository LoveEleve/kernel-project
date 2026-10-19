# K05 — 组件深度拷打：Sentinel

> 难度：★★★★☆｜频率：★★★★★｜区分度：高
> 关键词：Slot 责任链、LeapArray 滑动窗口、流控三策略、熔断状态机、Nacos 数据源、集群限流、Gateway 适配
> 依据：sentinel-lab（00 + D0-D13 + REVIEW）**Sentinel 1.8.8** 语料 + xhs-project Gateway 实战

---

## 问题

Sentinel 在项目里怎么用的？限流怎么做统计？为什么用滑动窗口？熔断怎么实现？规则怎么持久化？集群限流怎么做？

---

## 面试可讲版（五段式）

### ① Slot 责任链与滑动窗口（源码核心）

**核心模型（5 个概念）**：资源（Resource）→ 规则（Rule）→ Entry（一次调用）→ Context（调用链上下文）→ **Slot Chain（责任链）**。

**Slot 链（9 个，SPI 组装）**：
```
NodeSelector → ClusterBuilder → Log → Stat → Authority → System → Flow → Degrade → ParamFlow
（统计 → 授权 → 系统保护 → 流控 → 熔断 → 热点）
```
`SphU.entry()` 沿链执行：任一 Slot 抛 `BlockException` → 后续链**短路**（如 FlowSlot 拒绝后 DegradeSlot 不执行）。

**统计底座：LeapArray 滑动窗口**（所有规则的数据来源）：
- 环形数组 + 时间窗（窗口长度 + 样本数），当前窗口 `windowStart` 对齐后定位槽位，**CAS/覆盖写**推进
- 对比固定窗口：固定窗口在"窗口边界"可双倍放行（前窗尾+后窗头）；滑动窗口按时间滑过，统计更准
- `node.passQps()` 读的就是"过去 1 秒"的统计值

### ② 流控与熔断（算法核心）

**流控三种策略（FlowSlot）**：

| 策略 | 机制 | 场景 |
|---|---|---|
| QPS 直接拒绝 | 过去 1s pass 数 > 阈值 → 抛 FlowException | 保护下游 |
| QPS 匀速排队 | 超限计算等待时间 → sleep 放行（漏桶） | 削峰（秒杀排队） |
| 线程数隔离 | 当前活跃线程数 > 阈值 → 拒绝 | 保护慢资源 |

外加 **WarmUp（冷启动预热）**：阈值从低到高爬坡（冷系统不瞬间打满）。

**熔断状态机（DegradeSlot + CircuitBreaker）**：
```
CLOSED ──[慢调用比例/异常比例/异常数 超阈值]──→ OPEN ──[retryTimeout 到期]──→ HALF_OPEN
   ↑                                                                              │
   └──────────────[探测请求成功]────────────────────────────────────────────────┘
（HALF_OPEN 探测失败 → 回 OPEN）
```
三策略：慢调用比例（SLOW_REQUEST_RATIO）/ 异常比例（ERROR_RATIO）/ 异常数（ERROR_COUNT）。

### ③ 数据源与集群限流（生产必用）

**规则持久化三档**：代码硬编码（实验）→ Dashboard（重启丢）→ **DataSource（生产）**：
- `NacosDataSource`：监听 Nacos 配置变更 → 推送 `FlowRuleManager`（xhs 口径）
- 注意（1.8.8 真源）：DataSource 的调度线程池是**单线程池**（队列容量 1 + DiscardOldestPolicy）——单数据源串行处理

**集群限流（ClusterToken）**：单机限流各自算（N 台=阈值×N）；集群模式起 **TokenServer**（内嵌/独立）——客户端向 TokenServer 要 token，把单机 QPS 加总成集群 QPS（xhs 未上，单机限流 + 网关层总控）。

**系统自适应（SystemSlot）**：Load / CPU 使用率 / 平均 RT / 并发线程数 / 入口 QPS——全局兜底（先保命再谈单接口）。

### ④ 生产口径（xhs Gateway 实战）

**xhs 用法 = 双轨限流（不是只用 Sentinel）**：

**轨一：网关层 Sentinel（接口级粗粒度）**
```
请求 → HMAC 签名校验 → SentinelGatewayFilter（GatewayFlowRule 按 route ID）→ 路由转发
规则：Nacos 数据源（datasource.flow.nacos → my-xhs-gateway-sentinel-flow.json，gw-flow）→ GatewayRuleManager
     JSON 转换器 Bean（sentinel-json-gw-flow-converter）需手动注册（SCA 已知问题）
兜底：yml 路由 metadata（metadata.rate-limit-qps，默认 100）+ ApplicationReadyEvent + 30s 真空期检查
     （Nacos 异步推送未到时不用本地规则覆盖——避免覆盖后续推送）
BlockHandler：自定义 429 JSON（与 401 格式一致）
```

**轨二：业务层 @RateLimit（方法级精确）**——自研注解 + `RateLimitAspect`（@Order(10) 最先执行）：
Redis **Lua ZSet 滑动窗口**（移除过期成员→计数判断→ZADD 记录，原子），多实例精确限流；Redis 不可用 **fail-open** + Micrometer 计数（降级可见）；45 处使用（敏感接口）。
另有登录防爆破（账号 5 次 / IP 20 次 / 锁 15 分钟——安全限流）。

**主动提的缺口（架构师加分）**：用户级限流**未落地**（ParamFlowRule 需从 X-User-Id 提取，代码注释"后续实现"）；Sentinel 集群限流**未部署**（需 TokenServer）。

**隔离手段的真实口径（重要修正）**：xhs 曾写"Feign 线程池舱壁"配置 `csp.sentinel.bulkhead.*`——**该键不是 Sentinel 配置项，已删除**。实际隔离 = ① Sentinel 规则（Nacos 推送）② Feign 关重试 + 连接/读超时 ③ 自研最小连接 LB。

**Dashboard**：`dashboard=192.168.0.142:8858`、`eager=true`（启动即建连接）；`transport.port` 各服务不同（网关 8719 / content 8722）。

### ⑤ 拷打追问（面试官视角）

1. **"滑动窗口比固定窗口好在哪？"** 固定窗口边界双倍放行；LeapArray 按时间滑动统计（环形数组+窗口对齐），限流更平滑。
2. **"QPS 限流和线程数隔离怎么选？"** QPS 控"进入速率"；线程数控"并发占用"——慢调用（第三方 API）用线程数，防线程池打满。
3. **"匀速排队会阻塞线程吗？"** 会（sleep 等待）——所以只适合"愿意等"的场景（秒杀排队）；普通接口用直接拒绝。
4. **"熔断半开状态怎么探测？"** OPEN 到期转 HALF_OPEN，放少量请求试探：成功 → CLOSED；失败 → 回 OPEN（重置计时）。
5. **"规则改了要重启吗？"** 不用——DataSource（Nacos）监听推送；生产禁止硬编码规则（不可运维）。
6. **"单机和集群限流区别？"** 单机各自统计（总阈值=实例数×单机阈值）；集群要 TokenServer 集中发放 token（代价：TokenServer 高可用）。
7. **"Sentinel 和 Hystrix 区别？"** Hystrix 线程池/信号量隔离（重、已停更）；Sentinel 统计窗口+规则驱动、轻量、动态规则、Dashboard 可观测。
8. **"限流后请求怎么处理？"** BlockException → 业务自定义降级（返回兜底/排队/429）——**拒绝策略是业务决策**，Sentinel 只负责"判"。

### ⑥ 话术

> "Sentinel 在我们项目是双轨限流的第一轨：网关 SentinelGatewayFilter + GatewayFlowRule（Nacos 数据源 my-xhs-gateway-sentinel-flow.json，30 秒真空期回退 yml metadata 兜底），路由级 QPS 限流，BlockHandler 返回 429。第二轨是业务层自研 @RateLimit + Redis Lua ZSet 滑动窗口，方法级精确限流（45 处，Redis 挂了 fail-open 有 Micrometer 计数）。缺口主动说：用户级限流未落、集群限流需 TokenServer。隔离手段是组合拳：Sentinel 规则 + Feign 关闭重试和超时 + 最小连接 LB——之前文档写的'Feign 线程池舱壁'配置是无效键，已修正。"

---

## 生产陷阱（Sentinel 6 条）

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **硬编码规则不可运维** | `loadRules` 写死在代码——改规则要发版；生产用 DataSource |
| 2 | **本地兜底规则覆盖 Nacos 推送** | 启动时序（@PostConstruct 早于数据源初始化）——用 ApplicationReadyEvent + 真空期检查（xhs 已修） |
| 3 | **@SentinelResource 的 fallback 不生效** | 方法签名/异常类型不匹配——fallback/blockHandler 规则要严格对齐 |
| 4 | **线程数隔离当 QPS 用** | 语义不同——线程数管并发占用（慢调用），QPS 管速率 |
| 5 | **以为 Sentinel 是集群限流** | 默认单机统计（N 台=N 倍）——集群要 TokenServer |
| 6 | **假配置（如 bulkhead.*）** | 非 Sentinel 配置项被当生效——隔离靠真手段（规则+超时+LB），配置前先查源码键名 |

---

## 发散追问地图（横向）

- **对比**：Hystrix（线程池隔离/已停更）、Resilience4j（函数式/轻量）、Spring Cloud Gateway 自带 RequestRateLimiter（Redis+Lua 令牌桶）——选型维度
- **网关限流**：Sentinel Gateway（route/API 分组/参数限流）vs 自定义 Filter vs Nginx 层限流——分层限流体系
- **数据源**：Nacos/ Apollo/ ZK/ Redis 数据源；push vs pull 模式
- **观测**：Dashboard 实时监控、集群流控统计；与 Prometheus 集成
- **深入**：LeapArray 时间窗对齐细节；WarmUp 的冷却因子；热点参数限流的 LRU 统计
- **生态**：Spring Cloud Alibaba 适配（WebMVC/Feign/Gateway）；Dubbo 适配

---

## 面试官评分点

**高级开发级**：Slot 链 + 滑动窗口讲得清；流控三策略/熔断三策略/三状态；规则持久化方案；知道自己项目限流落点（网关/接口）。
**架构师加分**：固定窗口 vs 滑动窗口的本质（边界双倍）；线程数 vs QPS 的语义选择；数据源启动时序与兜底规则覆盖问题；集群限流 TokenServer 的取舍；限流的分层体系（网关+服务+DB）；**主动给出用户级/集群限流缺口**（xhs 47 口径）。
**危险信号**：以为 Sentinel 天生集群限流；说不清 BlockException 谁处理；规则硬编码当生产方案；把线程数隔离和 QPS 混为一谈；编造不存在的配置项。

---

## 本项目真实证据

**xhs-project（Gateway 实战）**：
- `RateLimitFilter`：SentinelGatewayFilter 委托 + 自定义 BlockHandler + `ApplicationReadyEvent` 加载 + 30s 真空期兜底（注释完整，含 @PostConstruct 时序坑说明）
- `GatewayConfig`：`sentinel-json-gw-flow-converter` Bean（SCA 已知问题手动注册）
- yml：`dashboard=192.168.0.142:8858` / `eager=true`；`datasource.flow.nacos`（my-xhs-gateway-sentinel-flow.json）
- **轨二**：`RateLimitAspect`（@Order(10)，Lua ZSet 滑动窗口，fail-open + Micrometer）；`@RateLimit` 45 处；登录防爆破
- `config/sentinel/`：16 服务 degrade-rules.json + gateway flow-rules.json 制品（手工导入 Dashboard）
- `SentinelBulkheadConfig`：**无效配置修正记录**（csp.sentinel.bulkhead.* 非 Sentinel 键，已删；真实隔离=规则+Feign 超时+最小连接 LB）
- 无 `@SentinelResource`——限流落点在网关（Sentinel）+ 业务层（自研 @RateLimit）双轨

**sentinel-lab（Sentinel 1.8.8 基线）**：
- 00 从 0 开始（限流例子 + 全系列地图）+ D0-D13（LeapArray/Slot 链/流控/熔断/Node/授权/热点/数据源/集群/系统/适配器/传输/Context）
- REVIEW：460 引文审计 + 概念修正（D8 单线程池真码/D9 TokenResult 真码/D13 contextCount 编造清除）

---

## 版本与来源

Sentinel **1.8.8**（源码语料 `code/spring/sentinel` + sentinel-lab）；官方文档/wiki；xhs-project Gateway 与隔离配置代码。

## 真实性说明

Sentinel 机制为 1.8.8 源码核验结论（lab D 类逐篇审过，行号已核）；xhs 用法（双轨限流/数据源/真空期兜底/用户级未落/舱壁修正）为代码与 FINAL-HANDOFF 事实（429 实测 100/50）。
