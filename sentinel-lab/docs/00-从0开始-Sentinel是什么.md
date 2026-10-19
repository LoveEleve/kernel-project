# 00 从 0 开始：Sentinel 是什么 + 第一个限流例子

> 定位：sentinel-lab 的**入口篇**（从 0 讲起）——先看"它干了什么"，再看"为什么"，最后给全系列阅读地图。
> 基线：Sentinel **1.8.8**（对齐 xhs-project）；源码 ref：`source-code/code/spring/sentinel`（tag 1.8.8）。

---

## 一、它干了什么：一个例子（先看输入输出）

**场景**：一个接口 `GET /hello`，我们要求它**每秒最多通过 1 个请求**。

```java
// 1. 定义规则：资源 hello 的 QPS 上限 = 1
FlowRule rule = new FlowRule();
rule.setResource("hello");
rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
rule.setCount(1);
FlowRuleManager.loadRules(Collections.singletonList(rule));

// 2. 业务代码里"圈出"要保护的资源（Entry = 一次调用）
public String hello() {
    try (Entry entry = SphU.entry("hello")) {   // 进入被保护的资源
        return "hello";
    } catch (BlockException e) {                 // 被限流/熔断 → 走这里
        return "blocked";
    }
}
```

**同一秒内并发 3 次调用**，输出是：

```
请求1 → hello      （通过）
请求2 → blocked    （QPS 超限，被拒绝）
请求3 → blocked    （QPS 超限，被拒绝）
```

**它干的事一句话**：在方法调用外面套一层"检查站"——`SphU.entry()` 时按规则判断"放不放行"，放行就执行业务，不放行就抛 `BlockException`（业务自己决定降级返回什么）。

**在 lab 里跑真实版本**（端口 28011，规则在 `SentinelLabApplication` 启动时加载）：

```bash
# 默认阈值较高（见启动类的 setCount），复现限流效果先把阈值调小（如 QPS=1）再并发：
for i in $(seq 1 5); do curl -s "http://localhost:28011/sentinel/api?key=t" & done; wait
# 阈值内 → 返回下游数据；超限 → {"blocked":true,...}
```

> ⚠️ Sentinel 不能单次 curl 验证——必须**并发**触发统计窗口（1 秒内多次调用）。

---

## 二、为什么需要它：三种"把系统拖垮"的场景

| 场景 | 后果 | Sentinel 的答案 |
|------|------|----------------|
| **流量尖峰**（秒杀/爬虫） | 请求量超过处理能力 → 线程池打满 → 全线超时 | **流控**（FlowRule）：超阈值直接拒绝/排队 |
| **下游变慢/故障**（DB 慢查询、第三方 API 挂了） | 调用方线程被慢调用占满 → 级联雪崩 | **熔断**（DegradeRule）：慢调用比例/异常率超标 → 快速失败一段时间 |
| **单个参数热点**（某商品 ID 被刷） | 单点流量打爆，其他正常 | **热点参数限流**（ParamFlowRule）：按参数值单独限流 |

**定位（面试一句话）**：Sentinel 是**流量防卫兵**——以"资源"为中心，把流控/熔断/降级/系统保护统一在一条 SPI 责任链（Slot Chain）上；对比 Hystrix（线程池隔离、已停更），Sentinel 以统计窗口 + 规则驱动，轻量且支持动态规则。

**为什么不用手写 if 判断**：规则要动态调整（不重启）、多实例要一致、要能观测（Dashboard）——这三点决定了需要"统计 + 规则 + 数据源"的完整体系。

---

## 三、怎么做：5 个核心概念 + 全系列地图

### 3.1 核心概念（记住这 5 个就够开工）

| 概念 | 一句话 | 代码入口 |
|------|--------|---------|
| **资源（Resource）** | 被保护的"东西"（方法/接口/代码块） | `SphU.entry("name")` / `@SentinelResource` |
| **规则（Rule）** | 保护策略（Flow/Degrade/ParamFlow/Authority/System） | `XxxRuleManager.loadRules(...)` |
| **Entry** | 一次调用（进入资源到退出的生命周期） | `entry.exit()` |
| **Context** | 调用上下文（一条调用链共享） | `ContextUtil.enter()` |
| **Slot Chain** | 规则检查的责任链（每个 Slot 管一类规则） | SPI `ProcessorSlot` |

### 3.2 全系列地图（每篇"它干了什么"一句话）

**🔴 核心（面试必问）**

| 篇 | 它干了什么 |
|---|---|
| [D1 滑动窗口](D1-LeapArray-滑动窗口.md) | 用 `LeapArray`（环形数组 + 时间窗）高效统计"过去 1 秒的 QPS/RT"——所有规则的**数据底座** |
| [D2 Slot 链](D2-ProcessorSlot-SPI责任链机制.md) | 把规则检查拆成 9 个 `ProcessorSlot`（Node→Log→Stat→Authority→System→Flow→Degrade→ParamFlow），按序执行 |
| [D3 流控算法](D3-FlowSlot-流控算法.md) | FlowSlot：直接拒绝 / 匀速排队（漏桶）/ WarmUp（冷启动预热）/ 线程数隔离 |
| [D4 熔断状态机](D4-CircuitBreaker-熔断状态机.md) | CircuitBreaker：CLOSED→OPEN→HALF_OPEN 状态机（慢调用比例 / 异常比例 / 异常数） |
| [D8 数据源](D8-NacosDataSource-数据源机制.md) | 规则从 Nacos/Dashboard 动态下发（Listener 驱动）——**生产必用** |

**🟡 支撑（理解体系）**

| 篇 | 它干了什么 |
|---|---|
| [D5 Node 四层节点](D5-Node-四层节点体系.md) | 统计数据的四层聚合：Entry→Resource→Origin→Cluster |
| [D6 授权规则](D6-AuthoritySlot-授权规则.md) | AuthoritySlot：按调用来源（origin）黑白名单放行/拦截 |
| [D7 热点参数](D7-ParamFlowSlot-热点参数限流.md) | ParamFlowSlot：对某个参数值单独限流（如某商品 ID） |
| [D9 集群限流](D9-ClusterToken-集群限流.md) | TokenServer 模式：把单机 QPS 加总成集群 QPS |
| [D10 系统自适应](D10-SystemSlot-系统自适应.md) | SystemSlot：按 Load/CPU/RT/线程数/入口 QPS 做全局保护 |
| [D11 适配器](D11-Adapter-适配器机制.md) | Sentinel 怎么"插入"WebMVC/Dubbo/Feign（拦截器/Filter） |
| [D13 Context-Entry](D13-Context-Entry-调用上下文.md) | Context/Entry 的 ThreadLocal 生命周期与嵌套调用 |

**🟢 翻篇（知道是什么即可）**

| 篇 | 它干了什么 |
|---|---|
| [D0 EagleEye](D0-EagleEye-日志框架.md) | Sentinel 内部日志（独立于业务日志），排障时知道去哪个文件看 |
| [D12 Transport](D12-Transport-传输层.md) | Dashboard ↔ 客户端通信（心跳/规则拉取/命令下发），Dashboard 连不上时看它 |

### 3.3 规则怎么配（生产的三种方式）

1. **代码硬编码**：`FlowRuleManager.loadRules(...)`——仅适合本地实验；
2. **Dashboard**：控制台改规则 → 客户端心跳拉取（重启后丢失）；
3. **DataSource 持久化**（生产推荐）：规则存 Nacos → `NacosDataSource` 监听推送（见 D8）。

---

## 四、值不值得深挖

| 优先级 | 内容 | 理由 |
|:---:|---|---|
| 🔴 深挖 | **D1 滑动窗口 / D2 Slot 链 / D3 流控算法 / D4 熔断状态机** | 面试高频："滑动窗口比固定窗口好在哪""熔断半开怎么实现" |
| 🔴 会用 | **D8 数据源** | 生产规则持久化方案；与 Nacos 联动（本 lab 已接） |
| 🟡 理解 | D5~D7、D9~D11、D13 | 体系完整性；面试偶尔追问 |
| 🟢 翻篇 | D0、D12 | 知道是什么、排障时查即可 |
