---
title: "02-Agent无侵入原理：字节码增强"
series: "SkyWalking 可观测系列"
previous: "02-sw-data-model.md"
next: "04-接入工程三坑.md（待写）"
lab_refs:
  - "docs/中间件拷打/K14-SkyWalking.md（①插件机制+③坑1+⑤追问2/7+陷阱1/3）"
note: "源码仓说明：10.4.0 主仓只含 OAP/UI/protocol；Java agent 在独立仓 skywalking-java（本篇 agent 机制以 K14 实测+官方口径为准）"
---

# 02-Agent无侵入原理：字节码增强

## §0 它干了什么（例子先行）

```
java -javaagent:skywalking-agent.jar -jar order.jar   ← 业务 jar 一个字节没改
  → JVM 启动时 agent 的 premain 先跑
  → 类加载时：插件声明的目标类（SpringMVC DispatcherServlet 等）被字节码重写——织入 span 采集
  → 请求进来：框架方法前后自动 start/end span → 异步队列 → gRPC 上报 OAP
```

- **它干了什么**：在**类加载期**改写字节码，把采集逻辑织进框架代码——业务无感。
- **本篇回答**：增强在哪一刻发生？插件怎么知道该改谁？为什么"版本敏感"是天生的？

## §1 掌握卡（3 条）

1. **增强时机 = 类加载**：`-javaagent` 触发 premain（javaagent 的入口方法，先于业务 main 执行）→ 注册类加载回调 → 目标类被加载时按插件声明重写——运行期不再改（K14⑤2："类加载时字节码插桩（插件），不改业务代码"）。
2. **插件 = 增强声明 + 版本验证**：每个插件声明"增强哪些类 + **witness class**（见证类）"——见证类存在才激活（K14⑤7 实锤：`javax.servlet ... does not exist` 的 WARN 即此机制在报警）。
3. **代价 = 版本强绑定**（K14 原话）：插件与框架**大版本**绑定——Spring Boot 3 换 jakarta.servlet，老插件见证类找不到 → **不激活** → 业务链路全无。这是"无侵入"的固有账，不是 bug。

## §2 朴素答案的死法（推导）

**朴素答案**：全量框架增强用什么？改源码埋点？——每个框架每个版本全改一遍，不可能；OpenTelemetry 手动 SDK？——回到"业务代码要引要调"，违背无侵入目标。

**But**：框架代码也是字节码——**谁加载它，谁就能改它**。JVM 留了口子：javaagent（JVM TI 的 Java 层封装）。

**Therefore**：插件化声明目标类+见证类 → 类加载时统一改写。**一份 agent 覆盖所有部署了兼容框架的服务**——这才是 xhs"15/15 注册、代码零改"的机制根基。

## §3 版本绑定的实锤：9.1.0 的死法（K14③坑1）

```
现象：UI 只有 Redisson/SENTINEL 零散 trace + /actuator/prometheus——业务 HTTP 链路全无
根因：agent 9.1.0 只有 apm-springmvc-annotation-3/4/5.x（见证 javax.servlet）；
      项目是 Boot 3（jakarta.servlet）→ 6.x 插件不存在 → 不激活（日志有 WARN）
假正常陷阱：Redisson 插件不依赖 servlet → 照常采集 → "agent 工作正常"的错觉
```

**排查链**（追问7 原话）：现象（零散 trace）→ 插件目录比对（无 6.x）→ agent 日志 WARN → 升级 9.7 + 插件适配（03 展开）。

## §4 插件家族（xhs 实采清单）

```
HTTP 入口：SpringMVC 6.x / WebFlux 6.x / Cloud Gateway 4.x
RPC：Feign（01 说的双号缝合在 HTTP 头层面，SW 在协议层采）
存储：JDBC / Redis（Lettuce/Redisson）
```

**为什么 Gateway/WebFlux 插件在 optional**（原 K14③坑3 语境）：不是所有部署都需要——装错了还冲突（3/4/5.x 与 6.x 共存=插件冲突陷阱 3）。xhs 把它们**移入 plugins** 才有了 gateway→home 完整链——03 工程篇展开。

## §5 边界（一句话翻篇）

- ByteBuddy/具体字节码指令细节 → agent 独立仓源码，本系列不逐行（10.4.0 主仓无 agent）。
- 手动增强 API（`@Trace`/跨 MQ 增强）→ 追问3 提及，用时查官方文档。
- activations/bootstrap-plugins 目录含义 → 03。

## §6 Self-Test（3 题）

- Q1：增强发生在类加载哪个时机？premain 干什么？
- Q2：witness class 是什么？9.1.0 的 WARN 长什么样意味着什么？
- Q3："无侵入"的固有代价是什么？9.1.0"假正常"怎么造成的？

## §7 衔接

- 本篇回答"机制"；下篇 03 接入工程：三坑修复叙事（挂载治理/插件适配/降噪边界——工程事故的完整叙事）。
