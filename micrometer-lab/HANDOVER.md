# micrometer-lab 交接文档（2026-10-05 重写）

> 位置：`/data/workspace/source-code/xhs-demo/micrometer-lab`
> 版本基线：**Boot 3.2.5 / micrometer 1.12.5（旧版 simpleclient 0.16.0）**——与 xhs-project 一致
> 用途：Micrometer 指标采集学习模块（MeterRegistry/MeterBinder/Observation/@Timed/@Counted/Prometheus/Step/Push）——生产使用 + 核心原理 + 面试题
> 评审记录：`docs/REVIEW-2026-10-05.md`

---

## 一、迁移与范围调整（2026-10-05）

- 自 `source-code/sca-lab/micrometer-lab` **迁入** xhs-demo；pom 改造：继承 xhs-demo 父 POM、版本由 Boot 3.2.5 BOM 管理、注册为模块。
- 用户决定：**砍掉 C 类手写**（C1 手写 Registry / C2 Prometheus 格式 / C3 StepDouble，共 13 文件）。
- 恢复途径：sca-lab git 历史。
- **版本重定基**：sca-lab 基线是 Boot 3.5.13/1.15+；本 lab 重定基到 **Boot 3.2.5/1.12.5**（xhs-project 对齐），文档全量重定基。

## 二、已完成（9 篇 D 文档 + A/B 验证代码）

| 内容 | 状态 |
|---|---|
| D1 MeterRegistry 多后端路由（模板方法/registerMeterIfNecessary/Composite/生命周期） | ✅ 审校（§三 重写为 1.12.5 机制） |
| D2 MeterBinder 自动绑定（32 Binder/JVM/Kafka/PG/deferBinding） | ✅ 审校 |
| D3 Observation API（Handler 链/Context/Convention/KeyValue） | ✅ 审校（§4/§5/§7.1/§8.2 重写——清除编造内容） |
| D4 @Timed/@Counted AOP（切点/percentiles 真相/longTask/MeterTag） | ✅ 重写为 1.12.5 精确版 |
| D5 PrometheusMeterRegistry（/actuator/prometheus 背后） | ✅ 重写为旧版 simpleclient 主体 |
| D6 MeterFilter 过滤链（DENY/NEUTRAL/ACCEPT/maxAllowable/map 族） | ✅ 审校 |
| D7 StepMeterRegistry 步进聚合（双线程/rollCount/关闭链） | ✅ 审校 |
| D8 PushMeterRegistry 推模式（三路线/publish 契约/PushGateway） | ✅ 审校 |
| A 类 13 控制器（A01-A13，含 @Timed/@Counted/@Observed/SLO） | ✅ 保留（A10 改为编程式 SLO） |
| B 类 5 扩展点（UptimeBinder/LoggingRegistry/ObservationHandler/MeterFilter/ObservationFilter） | ✅ 保留 |

## 三、验证状态

- 编译 ✓；文档评审 + 大段重写（详见 REVIEW）
- **运行时注意**：`management.observations.annotations.enabled=true` 已在 application.yml 显式开启（Boot 3.2.5 默认 false——@Timed/@Counted 切面否则不注册）

## 四、构建与运行

```bash
cd /data/workspace/source-code/xhs-demo
export JAVA_HOME=/env/javaEnv/jdk17
export MAVEN_OPTS="-Xmx512m -XX:MaxMetaspaceSize=256m"
mvn -pl micrometer-lab compile
mvn -pl micrometer-lab spring-boot:run     # 端口 28020

# 验证（自包含，无需外部依赖）
curl http://127.0.0.1:28020/actuator/prometheus | grep a10_slo
curl http://127.0.0.1:28020/a07/work        # @Timed
curl http://127.0.0.1:28020/a08/count       # @Counted
curl http://127.0.0.1:28020/a10/work        # SLO 直方图（编程式）
```

## 五、审计语料

| 语料 | 位置 |
|---|---|
| **micrometer 1.12.5**（评审基线） | `/data/workspace/source-code/code/spring/micrometer-1.12.5`（worktree，tag v1.12.5） |
| **Spring Boot 3.2.5**（actuator autoconfigure 基线） | `/data/workspace/source-code/code/spring/spring-boot-3.2.5`（worktree，tag v3.2.5） |
| micrometer v1.17.1（对照） | `/data/workspace/source-code/code/spring/micrometer` |

## 六、口径约定

1. 版本基线 **Boot 3.2.5 / micrometer 1.12.5**（= xhs-project）；1.13+/1.14+/1.15+/1.16+ 特性一律以"版本注记"标出，不作为本基线行为；
2. 引文逐字；示例必须标注；
3. 环境自包含（无外部中间件）。

## 七、已知边界

- C 类手写已移除（如需恢复见 sca-lab git）；
- A/B 端点保留（sca-lab 实测过，本轮未重跑；@Timed/@Counted 依赖 yml 开关已补）；
- tracing 相关（SpanContextSupplier/Exemplar）不在本 lab（无 tracing 依赖）。
