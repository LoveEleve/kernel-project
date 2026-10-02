# reactor-lab · Reactor 实验室

> 目的：把 **Stage 3（L09~L12）** 与 **前置课（L00·0.4.4 / 0.6）** 的响应式概念，拆成**能跑、能改、能观察**的最小代码。
> 版本：Java 17 / reactor-core **3.6.5** / reactor-netty-http 1.1.18（由 spring-boot-dependencies 3.2.5 管理，与 xhs-project 对齐）。
> 对应课程：`../docs/gateway/` 下的 `L09-reactive-streams.md`(L09) · `L10-reactor-operators.md`(L10) · `L11-backpressure-and-scheduling.md`(L11) · L12 待写

## 怎么跑（推荐用脚本，防"旧类"坑）

```bash
cd reactor-lab

./scripts/run-all.sh                                       # 全部 20 个实验
./scripts/run-one.sh l11.Ex01_BackpressureStrategies       # 单个实验
./scripts/run-one.sh l11.Ex05_EventLoopVsScheduler
```

<details>
<summary>原始 maven 命令（等价）</summary>

```bash
# ⚠️ 两个坑：
#   1) `compile` 必须显式加：exec 目标【不触发编译】，否则跑的是 target/classes 里的旧类
#   2) 用 exec:exec（fork 独立 JVM）而不是 exec:java —— 后者跑在 Maven 自己的 JVM 里，
#      Maven 的 SLF4J provider 会抢绑定 → reactor 的 log() 输出看不到（Ex03 就演示了 log()）
mvn -q -pl reactor-lab compile exec:exec \
  -Dexec.executable=java \
  -Dexec.args="-cp %classpath com.myxhs.demo.reactor.LabMain"
mvn -pl reactor-lab test
```

</details>

## 目录

```
reactor-lab/
├── scripts/
│   ├── run-all.sh                          # 一键跑全部（含强制 compile）
│   └── run-one.sh                          # 跑单个：./scripts/run-one.sh l11.Ex01_BackpressureStrategies
├── src/main/java/com/myxhs/demo/reactor/
│   ├── LabMain.java                        # 总入口（按课号顺序跑全部）
│   ├── l00/                                # 前置课 0.4.4 Reactor Netty / 0.6 WebFlux
│   │   ├── Ex01_MinimalHttpServerClient.java   # HttpServer + HttpClient 最小使用
│   │   ├── Ex02_MinimalTcpServerClient.java    # TcpServer + TcpClient（对比：字节流）
│   │   └── Ex03_MinimalWebFlux.java            # WebFlux 最小使用（不引 Spring Boot）
│   ├── l09/                                # L09 Reactive Streams 与 Reactor 模型
│   │   ├── Ex01_CustomPublisher.java       # 手写 4 接口 → 看信号顺序
│   │   ├── Ex02_SignalTrace.java           # Reactor 信号轨迹（拉模型）
│   │   ├── Ex03_Laziness.java              # just vs fromCallable vs defer
│   │   ├── Ex04_MonoVoid.java              # Mono<Void> 语义 / just(null) 报错
│   │   └── Ex05_RequestN.java              # 背压可见（request(n)）
│   ├── l10/                                # L10 Reactor 算子
│   │   ├── Ex01_MapVsFlatMap.java          # map vs flatMap（含"套娃"反例）
│   │   ├── Ex02_ThenAndDoOn.java           # then 接续 vs doOnXxx 旁听
│   │   └── Ex03_FilterAndFallback.java     # 过滤 + 空值/错误兜底
│   ├── l11/                                # L11 背压与调度
│   │   ├── Ex01_BackpressureStrategies.java    # 4 种背压策略实测
│   │   ├── Ex02_Schedulers.java                # 三种调度器的线程名与默认规模
│   │   ├── Ex03_PublishOnVsSubscribeOn.java    # 每阶段线程名对比
│   │   ├── Ex04_ParallelFlux.java              # parallel + runOn 才真并行
│   │   └── Ex05_EventLoopVsScheduler.java      # ★ EventLoop vs Schedulers（真实 HTTP 服务实测）
│   └── l12/                                # L12 Context / Sinks / 调试
│       ├── Ex01_Context.java                   # 写/读/跨线程/桥接 attributes
│       ├── Ex02_Sinks.java                     # 回调 → 流；三种 multicast 口味
│       ├── Ex03_Debug.java                     # log() / checkpoint() 路标
│       └── Ex04_HooksDebug.java                # Hooks.onOperatorDebug() 的 Assembly trace
└── src/test/java/com/myxhs/demo/reactor/
    ├── l09/L09_ContractTest.java           # 契约断言（5 个）
    ├── l10/L10_OperatorsTest.java          # 算子断言（6 个）
    ├── l11/L11_SchedulingTest.java         # 调度断言（4 个）
    └── l12/L12_ContextSinksTest.java       # Context/Sinks 断言（6 个）
```

## 各课实验：看什么

### L00 前置（0.4.4 / 0.6）

| 实验 | 要观察的现象 | 对应结论 |
|---|---|---|
| **Ex01** | `handle((request, response) -> ...)` 拿到的是 **HTTP 对象**；`HttpClient` 是**声明式链** | 0.4.4 基本使用 |
| **Ex02** | `handle((inbound, outbound) -> ...)` 拿到的是**字节流**（无 URL / Header）；Gateway **零引用** | 0.4.4 附录（层次关系） |
| **Ex03** | `RouterFunction → HttpHandler → ReactorHttpHandlerAdapter → HttpServer`；**不引 Spring Boot 也能跑** | 0.6 基本使用 |

### L09 契约与模型

| 实验 | 要观察的现象 | 对应结论 |
|---|---|---|
| **Ex01** | `onSubscribe` 第一个来、只一次；`onNext` 数量 ≤ 累计 request；`onComplete` 终态唯一 | 规范的 5 条规则 |
| **Ex02** | `request(1)` → `onNext` → `request(1)` → … **交替推进**；不 request 就没有数据 | 拉模型（消费者驱动） |
| **Ex03** | `just` **构造时就执行**；`fromCallable`/`defer` **订阅时才执行**；冷流重订阅重跑 | 惰性 |
| **Ex04** | `Mono<Void>` 只有 `onComplete` 没有 `onNext`；`Mono.just(null)` 抛 NPE | `Mono<Void>` 语义 |
| **Ex05** | 不 request = 流**根本不动**；一次要 1 个 = 源与消费者交替 | 背压 = `request(n)` |

### L10 算子

| 实验 | 要观察的现象 | 对应结论 |
|---|---|---|
| **Ex01** | `map` 返回普通值 / `flatMap` 返回 `Mono` 自动展平；**用错 `map` 会套娃**（内层查询根本没发生） | map vs flatMap |
| **Ex02** | `then` 丢弃值（post 阶段）/ `doOnXxx` 旁听不改流 / `onErrorResume` 兜底 | then vs doOnXxx |
| **Ex03** | `filter` / `defaultIfEmpty` / `switchIfEmpty`**非空时不订阅兜底流** | 过滤与兜底 |

### L11 背压与调度

| 实验 | 要观察的现象 | 对应结论 |
|---|---|---|
| **Ex01** | 默认**报错** / BUFFER **收全 5 个** / DROP **只收 [0]** / LATEST **收到 [0,4]** | 4 种背压策略 |
| **Ex02** | 三种调度器线程名（`parallel-1`/`boundedElastic-1`/`single-1`）+ 默认规模（CPU / 10×CPU / 1） | Schedulers 三种 |
| **Ex03** | ①三段同线程 ②`subscribeOn` 整条链被带走 ③`publishOn` **上游仍在调用线程** ④组合拳 | publishOn vs subscribeOn |
| **Ex04** | 串行 ≈1280ms / `parallel` 不 `runOn` **仍串行** ≈1210ms / `parallel+runOn` ≈326ms | ParallelFlux |
| **Ex05** | 默认 handler 跑在 `reactor-http-epoll-N`；`subscribeOn(boundedElastic)` 后**不切回**；响应仍发出 | EventLoop vs Schedulers |

### L12 Context / Sinks / 调试

| 实验 | 要观察的现象 | 对应结论 |
|---|---|---|
| **Ex01** | 写/读 / 不可变叠加 / **跟订阅走**（两次订阅不同值）/ 跨线程不丢 / **ContextView 存进 attributes**（Gateway 桥接） | Context |
| **Ex02** | `Sinks.one` 推值 / **三种 multicast 口味**（无订阅者：buffer=OK，direct=FAIL_ZERO_SUBSCRIBER）/ 回调 → 流 | Sinks |
| **Ex03** | `log()` 打印信号流；`checkpoint` 的路标在 **suppressed** 里（`*__checkpoint ⇢ 【说明】`） | 调试三件套 |
| **Ex04** | 不开 Hooks：无 Assembly trace；开启后：**suppressed 里出现业务代码行号**（`*__Flux.map ⇢ at ...chain(Ex04_HooksDebug.java:28)`）；关闭恢复 | `Hooks.onOperatorDebug()` |

## 约定（加新实验时）

- 一个实验一个类：`l{课号}/Ex{两位序号}_{名字}.java`，**自带 `main`** + 类注释写清"要观察的 N 件事"
- 新实验**同时注册到 `LabMain.LABS`**（保持"能一键跑全部"）
- 能变成断言的，往 `L{课号}_*Test.java` 加一条（`StepVerifier`）
- **验证姿势**：要展示"某阶段跑在哪个线程"，必须在**该阶段内部打印**线程名 —— 别用 `map` 的返回值传递（值会被下游覆盖，导致不可观测）
- 只观察现象、不做 Spring 集成（Spring 相关实验在 demo-gateway / 后续模块）
