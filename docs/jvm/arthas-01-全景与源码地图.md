# arthas-01 · Arthas 全景与源码地图

> 核心点：**Arthas = 常驻 agent + 命令总线**。通道就是已学的 Attach+load；
> 能力来自 Instrumentation + Spy 字节码编织 + async-profiler；命令只是表层。

## 一、拿到的包（已下载，版本对齐 4.3.5）

- 二进制：`/data/workspace/tools/arthas/`（20MB，从 `arthas-bin.zip` 解出）
- 源码：`/data/workspace/source-code/arthas/`（31MB，tag `arthas-all-4.3.5`，commit `be42fd9`）

| 文件 | 是什么 | 对应已学概念 |
|---|---|---|
| `arthas-boot.jar` | 启动器：选进程 → attach → loadAgent | = 我们手写的 attach 客户端 + load |
| `arthas-agent.jar`（8KB） | 被装进目标 JVM 的 agent（AgentBootstrap） | "load 装进去的 agent" |
| `arthas-core.jar`（17MB） | 真正核心：命令实现 + netty server + 字节码编织 | 能力的实现层 |
| `arthas-spy.jar` | Spy 类，注入目标 bootstrap classpath | 增强后方法的回调点 |
| `async-profiler/*.so` | 采样引擎（x64/arm64/mac） | profiler 命令的底座 |
| `lib/libArthasJniLibrary*.so` | 原生小工具库 | 终端/环境适配 |
| `math-game.jar` | 官方演示靶子 | 练手用 |
| `as.sh` / `install-local.sh` / `arthas.properties` / `logback.xml` | 启动与配置 | 端口、安全见下 |

## 二、通道层：怎么"连上去"

1. **启动装载**：`arthas-boot` → `jdk.attach` attach → `loadAgent(arthas-agent.jar)`（= 已学的 load 通道）
2. **常驻服务**：agent 在目标 JVM 内起 netty 服务：telnet `3658` / HTTP `8563`（见 `arthas.properties`）
3. **命令通道**：终端 → arthas-client → telnet 3658 → 目标 JVM 内的命令解释器；
   **关键差异**：jstack 是"一次命令一次连接"，Arthas 是**装一次、常驻长连接**
4. **增强通道**：命令触发的字节码增强 → `Instrumentation.retransform` / JVMTI ClassFileLoadHook
5. **采样通道**：`profiler` → async-profiler（native agent）

## 三、接口层：连上后调什么

1. **Instrumentation**（retransform / ClassFileTransformer）→ watch/trace/stack 基础
2. **Spy + AdviceWeaver（字节码编织）**：把 `Spy.ON_BEFORE/ON_RETURN` 织进目标方法 ← **灵魂机制**
3. **JVMTI**：底层支撑
4. **async-profiler**（内置）→ profiler
5. **MXBean / DiagnosticCommand** → jvm/dashboard 指标
6. **OGNL** → ognl 命令表达式引擎

## 四、能力层：命令映射

| 能力 | 命令 |
|---|---|
| 看（页面级） | dashboard / thread / jvm / memory / heapdump / sc / sm / jad / classloader / logger |
| 方法观测（核心增量） | watch（入参/返回/异常）/ trace（耗时路径）/ stack（调用栈）/ monitor（统计）/ tt（时空回溯） |
| 改 | retransform / redefine / mc（内存编译热替换） |
| 采样 | profiler（cpu / alloc / lock + 火焰图） |
| 动态执行 | ognl / vmtool |

## 五、37 学习点（按重要度 + 深度）

通道层（A1-A5）：A1 boot 流程 ⭐ / A2 两层 agent 架构（薄 agent + 隔离 classloader）⭐ /
A3 防重复 attach ⭐ / A4 启动期装载 ◐ / A5 停止卸载 ⭐
服务命令（B1-B5）：B1 telnet/http/session ⭐ / B2 命令执行模型 ◐ / B3 内置分组 ⭐ /
B4 外部命令 SPI ○ / B5 web-ui/tunnel ○
观测（C1-C6）：C1 dashboard ⭐ / C2 thread ⭐ / C3 jvm/memory/sysprop ⭐ /
C4 sc/sm/jad/classloader ◐ / C5 heapdump/logger ◐ / C6 ognl/vmtool ○
方法观测（D1-D6）：D1 watch ⭐ / D2 trace ⭐ / D3 stack ⭐ / D4 monitor ◐ / D5 tt ◐ /
D6 Spy+Advice+ThreadLocal 原理 ⭐
增强（E1-E4）：E1 retransform/redefine 限制 ⭐ / E2 mc+redefine ◐ / E3 不可逆 ⭐ / E4 编织原理 ◐
采样（F1-F3）：F1 profiler 三模式 ◐ / F2 火焰图 ◐ / F3 async-profiler 装载 ○
生产（G1-G5）：G1 安全（telnet 默认本地免鉴权）⭐ / G2 性能开销 ⭐ / G3 会话管理 ◐ /
G4 清理 ⭐ / G5 与 JDK 工具对照 ⭐
生态（H1-H3）：MCP/skills ○ / labs（JFR/gRPC）○ / 源码主链 ◐

攻克顺序：通道 → 观测 → 灵魂（watch/trace/tt+Spy）→ 修改 → 采样+生产。

自测：① 画架构图并说清 AgentBootstrap/SpyAPI；② 说出 watch 一条命令的完整链路；
③ trace 定位慢方法 + profiler 火焰图 + redefine 改方法体；
④ retransform 限制与还原；⑤ 安全边界与开销来源。

## 六、源码验证地图（4.3.5 已实测定位）

| 结论 | 位置 |
|---|---|
| attach 通道（= 我们手写的那套） | `core/.../Arthas.java:124` VirtualMachine.attach → `:153` loadAgent |
| agent 入口 | `agent/pom.xml:51-52`（Premain-Class/Agent-Class = agent334.AgentBootstrap） |
| premain/agentmain 双入口 | `agent/.../agent334/AgentBootstrap.java:63/67` |
| 防重复 attach | 同文件 `:95` "already stared, skip attach" + 全局 classloader |
| 类加载隔离 | `agent/.../agent/ArthasClassloader.java` |
| Spy（bootstrap 可见） | `spy/.../java/arthas/SpyAPI.java`（包名 `java.arthas`） |
| Spy 注入 bootstrap | `core/.../server/ArthasBootstrap.java:227` appendToBootstrapClassLoaderSearch |
| 编织/增强 | `core/.../advisor/`（Enhancer/Advice/AdviceListenerManager/SpyImpl） |
| 命令实现 | `core/.../command/`（basic1000/klass100/monitor200/express/logger/view） |
| 服务端 | `core/.../server/ArthasBootstrap.java` + `shell/term/`（Telnet/HttpTermServer） |
| 配置 | `arthas.properties`（telnetPort/httpPort/auth/sessionTimeout/tunnel/MCP） |
| 内置 async-profiler | `async-profiler/*.so` |
| 嵌入式自 attach | `arthas-agent-attach/` + `arthas-spring-boot-starter/` |
| 新特性 | `labs/arthas-jfr-*`、`arthas-mcp-server/`、`skills/` |

## 七、一句话

**Arthas = attach + Java agent + 字节码编织 + 常驻服务的"产品化组合"。**
学它 = 学"已学机制怎么组装成产品"——命令是表层，Spy 编织和常驻 agent 是里子。
