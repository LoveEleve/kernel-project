# tracing-lab 交接文档（2026-10-05 重写）

> 位置：`/data/workspace/source-code/xhs-demo/tracing-lab`
> 版本基线：**Boot 3.2.5 / micrometer-tracing 1.2.5（bridge-brave）**
> 用途：分布式链路追踪学习模块（Tracer/Span/Propagation/Baggage/Sampler/自动配置）——生产使用 + 核心原理 + 面试题
> 评审记录：`docs/REVIEW-2026-10-05.md`

---

## 一、迁移与范围调整（2026-10-05）

- 自 `source-code/sca-lab/tracing-lab` **迁入** xhs-demo；pom 改造：继承 xhs-demo 父 POM、版本由 Boot 3.2.5 BOM 管理、注册为模块。
- 用户决定：**砍掉 C 类手写**（C1 Span 模型 / C2 W3C 传播 / C3 Baggage，共 9 文件）。
- 恢复途径：sca-lab git 历史。
- **版本重定基**：sca-lab 基线 Boot 3.5.13/1.5.x → **Boot 3.2.5/1.2.5**；文档全量重定基（行数/行号/Order 常量/baggage 属性）。

## 二、已完成（9 篇 D 文档 + A/B 验证代码）

| 内容 | 状态 |
|---|---|
| D1 Tracer API + Span 生命周期 | ✅ 审校（行数/行号重定基） |
| D2 Propagation（W3C/B3/B3_MULTI） | ✅ 审校（W3CPropagation 493 行重定位） |
| D3 TraceContext + CurrentTraceContext | ✅ 审校 |
| D4 Observation→Tracing 桥接（三 Handler） | ✅ 审校（**Order 常量纠错**） |
| D5 Brave 桥接层（12 包装类） | ✅ 审校（**LinkUtils 编造清除**） |
| D6 Baggage（remote/local/tag） | ✅ 审校（**Boot 3.2.5 属性边界**） |
| D7 Sampler + SpanHandler | ✅ 审校 |
| D8 自动配置 + 日志集成 | ✅ 审校（bridge 装配代码纠错） |
| A 类 11 控制器（A01-A11：Span 生命周期/传播/Baggage/MDC/Sampling） | ✅ 保留 |
| B 类 5 扩展点（LoggingSpanHandler/DebugSampler/自定义 header/Tenant Baggage/ObservationHandler） | ✅ 保留（B04 文案按 3.2.5 修正） |

## 三、验证状态

- 编译 ✓；文档评审完成（详见 REVIEW）
- **配置注意**：`management.tracing.baggage.remote-fields`（Boot 3.2.5 仅支持此项；local-fields/tag-fields 不存在）

## 四、构建与运行

```bash
cd /data/workspace/source-code/xhs-demo
export JAVA_HOME=/env/javaEnv/jdk17
export MAVEN_OPTS="-Xmx512m -XX:MaxMetaspaceSize=256m"
mvn -pl tracing-lab compile
mvn -pl tracing-lab spring-boot:run     # 端口 28030

# 验证（自包含，日志 MDC 看 traceId/spanId）
curl http://127.0.0.1:28030/a01/start     # Span 生命周期
curl http://127.0.0.1:28030/a04/w3c       # W3C 传播头
curl http://127.0.0.1:28030/a06/baggage   # Baggage
# 日志：grep "tracing-lab" 应用日志 → [tracing-lab,traceId,spanId]
```

## 五、审计语料

| 语料 | 位置 |
|---|---|
| **micrometer-tracing 1.2.5**（评审基线） | `/data/workspace/source-code/code/spring/micrometer-tracing-1.2.5`（worktree，tag v1.2.5） |
| **Spring Boot 3.2.5**（autoconfigure 基线） | `/data/workspace/source-code/code/spring/spring-boot-3.2.5`（worktree，tag v3.2.5） |
| micrometer-tracing v1.7.1（对照） | `/data/workspace/source-code/code/spring/micrometer-tracing` |

## 六、口径约定

1. 版本基线 **Boot 3.2.5 / micrometer-tracing 1.2.5**；1.3+ 特性一律"版本注记"；
2. **xhs-project 对照口径**：xhs-project 实际用**自研 traceId**（`TraceContextHolder`/`MqTraceHelper`/`FeignTraceInterceptorConfig`/`MdcAwareExecutorService`）——不是 micrometer-tracing；本 lab 教标准实现，概念与自研方案同源（对照见 Z 篇 G 组）；
3. 引文逐字；示例必须标注；
4. 环境自包含（无 Zipkin/OTLP；日志 MDC 验证）。

## 七、已知边界

- C 类手写已移除（如需恢复见 sca-lab git）；
- A/B 端点保留（sca-lab 实测过，本轮未重跑；B04 文案已按 3.2.5 修正）；
- 无 exporter（Zipkin/OTLP）——span 数据不落外部存储，属设计内（日志 MDC 验证）。
