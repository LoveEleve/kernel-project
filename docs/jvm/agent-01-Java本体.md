# agent-01 · Java agent 本体

## 一、定义

**Agent = 装在 JVM 内部、按约定入口被 JVM 调用、拿到 JVM 能力对象的驻留程序。**
公式：agent = 约定入口 + 装载通道 + 能力对象。

## 二、双入口（时机/线程/异常语义）

| | premain | agentmain |
|---|---|---|
| 装载时机 | 启动期（-javaagent） | 运行期（attach + load） |
| 触发 | VMInit 事件（`post_vm_initialized`，thread.cpp:4032） | attach `load` 命令 |
| 线程 | main | Attach Listener |
| 相对业务 | main 之前（覆盖应用第一批类） | 业务已在跑（只能管"从现在起新加载的类"，存量要 retransform） |
| 失败 | JVM 启动失败 | 仅本次 attach 失败 |
| MANIFEST | Premain-Class | Agent-Class |

- 一个 agent 可以两个入口都写（Arthas 同款）；MANIFEST 里双声明
- MANIFEST 全家：Premain-Class / Agent-Class（入口）；
  Can-Retransform-Classes / Can-Redefine-Classes（权限开关，缺了直接 UnsupportedOperationException）；
  Launcher-Agent-Class（JDK9+ 可执行 jar 自动装载，知道即可）
- manifest 错误码（libinstrument）：100 BADJAR（找不到 jar/无 Agent-Class）、
  101 NOTONCP（加不进 system classpath）、102 STARTFAIL（无入口/执行失败）
  → **102 排查：真因在目标进程日志**（如 UnsupportedClassVersionError）

## 三、底层：libinstrument（JPLIS 机制层）

- `libinstrument.so` = JDK 自带的 JVMTI native agent，实现 JPLIS
  （JDK11 在 `$JAVA_HOME/lib/`，JDK8 在 `jre/lib/<arch>/`；源码 `src/java.instrument/share/native/libinstrument/`）
- 职责：注册 VMInit（启动期调 premain）/ 注册 ClassFileLoadHook（分发 transformer）/
  创建 `InstrumentationImpl` / 实现 retransformClasses0 等 native 方法
- 关键函数：`DEF_Agent_OnLoad` / `DEF_Agent_OnAttach` / `eventHandlerVMInit` /
  `transformClassFile` / `startJavaAgent` / `processJavaStart` / `loadClassAndCallAgentmain`
- **每次 attach 新建一个 JPLIS agent + 新的 InstrumentationImpl**
  （实测：两次 attach 的 inst identityHashCode 不同 914126904 vs 1900697436；
  源码：`createNewJPLISAgent` → `createInstrumentationImpl`）
- agent 类本身只加载一次（static 字段跨 attach 存活）；Instrumentation 多份 → 撤销必须用"注册时的那份"

## 四、Instrumentation 是什么

官方定义（`java.lang.instrument.Instrumentation` javadoc）：
"This class provides services needed to instrument Java programming language code."
JDK11 共 17 个方法，分三桶：看（getAllLoadedClasses/getInitiatedClasses/getObjectSize）/
改（addTransformer/removeTransformer/retransformClasses/redefineClasses/isModifiableClass）/
挂+判断（appendToBootstrapClassLoaderSearch/appendToSystemClassLoaderSearch/isXxxSupported）。
- 不是 new 出来的：JVM 在调入口时递进来；它是 agent 的唯一操作口
- 它是 JVMTI 的 Java 封装（第 3 组展开）

## 五、load vs -javaagent

| | -javaagent（启动期） | load（运行期） |
|---|---|---|
| 用途 | 常驻监控/全生命周期覆盖（APM/OTel/profiler/安全） | 临时救火（Arthas/jcmd 诊断） |
| JDK21+ 趋势 | 正规通道（动态加载将被收紧，JEP 451） | 聚焦人工诊断 |

## 六、agent 类加载与隔离

- 实测：agent 类 + 应用类同为 AppClassLoader（appendClassPath 机制；`String`=null=bootstrap）
- 后果：同名类冲突（先加载赢）、互相可见、无法卸载
- 工业模式（三段式）：入口极薄（system CL）/ 实现放自定义 CL（ArthasClassloader）/
  公共回调挂 bootstrap（Arthas 用 appendToBootstrapClassLoaderSearch 挂 Spy）

## 七、可重复性与守卫

- 同一个 agent 类重复装载：类复用 + agentmain 每次都调（实测 args=demo-args/second-run）
- 改代码重装同名类无效（类不可替换）→ 换类名/换进程/或 retransform
- agentmain 别阻塞：Attach Listener 单线程，sleep 10s → 后续 attach 全排队（实测 jcmd 排队 8.2s）
- 所以 Arthas 有 "already started, skip attach" 守卫（ spy `SpyAPI.isInited()`）

## 八、实验清单（`03-java-agent/`，新家在 `xhs-demo/agent-lab/`）

- MyAgent（agentmain 打一句 + 数类）/ AttachLoadAgent（VirtualMachine.attach+loadAgent 客户端）
- ClassWatcherAgent（addTransformer 打印每次类加载；打包坑：漏 `$1.class` → 102）
- LoaderProbeAgent（loader 归属三问 + 同一 loader + jar 来源 + 可见应用类）
- SleepAgent（sleep 10s 复现排队）
- PremainAgent（双入口同 jar：premain 在 main 线程抓 HelloApp 首批类；attach 同 jar 走 agentmain）
- NoRetransformAgent（无 flag → addTransformer(t,true) 被拒；有 flag → 成功）
- InstIdAgent（两次 attach 的 inst hash 不同）
- 构建：手写 `build-agent.sh`（javac 全量 + 自动 MANIFEST）/ Maven（manifestEntries；真实 agent 用 shade，注意隔离）
- 坑：环境 JAVA_HOME 指向 21 会编出 65 版 class（目标 11 只认 55）→ 用显式 JDK11 + `--release 11`
