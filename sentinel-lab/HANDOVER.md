# sentinel-lab 交接文档（2026-10-05 重写）

> 位置：`/data/workspace/source-code/xhs-demo/sentinel-lab`
> 版本基线：**Sentinel 1.8.8**（SCA 2023.0.1.2 BOM 自带；对齐 xhs-project）
> 用途：Sentinel 容错学习模块（流控/熔断/降级）——生产使用 + 核心原理 + 面试题
> 入口：`docs/00-从0开始-Sentinel是什么.md`（从0讲起 + 全系列地图）
> 评审记录：`docs/REVIEW-2026-10-05.md`

---

## 一、迁移与范围调整（2026-10-05）

- 自 `source-code/sca-lab/sentinel-lab` **迁入** xhs-demo；pom 改造：继承 xhs-demo 父 POM、**删除 javax 的 `sentinel-web-servlet`**（Jakarta 不兼容；starter 已内置 **v6x(Jakarta) adapter 1.8.8**）、pin `nacos-client 2.3.0`（对齐 xhs-project）、注册为模块。
- 用户决定：**砍掉 C 类手写**（`SimpleLeapArray`/`SimpleRateLimiter`/`SimpleCircuitBreaker` + 3 测试类 18 用例 + 控制器 2 个 circuit 端点）。
- 恢复途径：sca-lab git 历史。

## 二、已完成

| 类 | 内容 | 状态 |
|---|---|---|
| 入口篇 | `00-从0开始-Sentinel是什么`（例子先行 4 步法 + 全系列地图 + 规则配置方式） | ✅ 新增 |
| D 类 | **14 篇源码解读**（D0~D13：LeapArray/Slot 链/流控算法/熔断状态机/Node/授权/热点/数据源/集群/系统/适配器/传输/Context） | ✅ 已审校（D8/D9/D13/D3 修复） |
| B 类 | 5 项扩展点（`TimingSlot`/`CustomSlotChainBuilder`/`FileFlowRuleDataSource`/`SentinelBlockHandlerConfig`/`CustomInitFunc`） | ✅ 保留 |
| A 类 | 验证端点（`/sentinel/api` 流控、`/sentinel/error` 熔断、`/sentinel/fallback` 降级、`/sentinel/auth` 授权、`/sentinel/global` 全局 Handler） | ✅（A-07 集群/A-09 Feign/A-10 Gateway 待基础设施） |

## 三、验证状态

- 编译 ✓（sentinel-core **1.8.8** + `sentinel-spring-webmvc-v6x-adapter` + nacos-client 2.3.0）
- 引文审计：460 引文 / 397 命中 / 63 残留（reflow/标注示例为主；无编造大段）

## 四、构建与运行

```bash
cd /data/workspace/source-code/xhs-demo
export JAVA_HOME=/env/javaEnv/jdk17
export MAVEN_OPTS="-Xmx512m -XX:MaxMetaspaceSize=256m"
mvn -pl sentinel-lab -am compile

# 启动（端口 28011；Nacos 21.130.247.89:18848 / Dashboard 21.130.247.89:8858）
mvn -pl sentinel-lab spring-boot:run
# 或 ./sentinel-lab/start-sentinel-lab.sh

# 并发验证（Sentinel 必须并发触发统计窗口）
for i in $(seq 1 5); do curl -s "http://localhost:28011/sentinel/api?key=t" & done; wait
# 注意：默认阈值见 SentinelLabApplication（当前 QPS=100），演示限流先调小阈值
```

## 五、环境与依赖

| 项 | 值 |
|---|---|
| 应用端口 | 28011 |
| 注册中心 | Nacos `21.130.247.89:18848`，namespace `sca-lab-dev` |
| Sentinel Dashboard | `21.130.247.89:8858`（sentinel/sentinel） |
| 依赖服务 | **nacos-lab**（xhs-demo，28001）作为被保护的下游目标 |
| 源码 ref | `/data/workspace/source-code/code/spring/sentinel`（tag **1.8.8**） |

## 六、口径约定

1. 版本基线 **Sentinel 1.8.8**；版本差异写"版本差异"注记；
2. 引文逐字（容忍 reflow/行尾注释）；截断显式 `...`；
3. 坐标以 1.8.8 源码为准；示例/实验必须标注。

## 七、已知边界

- C 类手写已移除（如需恢复见 sca-lab git）；
- A-07 集群限流（需 TokenServer）、A-09 Feign 整合未做（归后续/其他线）；**A-10 Gateway 整合已完成** —— 见网关课程 `docs/gateway/L45-sentinel.md`（raw gateway-adapter + `GatewayFlowRule` + Nacos 规则源 + T-106 双 BlockHandler）；
- 旧文档中的"双 profile（2025.0.2 / 2025.1）"问题已随迁移解决——xhs-project 口径下 v6x adapter 由 starter 内置，A-06 授权无需切 profile。
