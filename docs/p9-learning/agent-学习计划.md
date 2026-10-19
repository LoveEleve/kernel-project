# JVM Agent 知识包 · 学习计划（进入 Arthas 的前置）

定位：路线图第 1 项（启动参数）+ 第 3/4/5 项（JVMTI / Instrumentation / 字节码增强）
目标：理解"agent 是什么、怎么被装载、拿到什么能力、怎么改代码"
理由：Arthas 的启动链路（AgentBootstrap / ArthasBootstrap）、灵魂机制（Spy / Advice）、watch / trace 全建立在这上面

深度标记：★ 必须掌握 / ◐ 会用 / ○ 知道即可

产物目录：
  旧实验（历史归档）：/data/workspace/source-code/experiments/jvm-diagnostics/（03-java-agent / 04-bytecode / 05-native-agent）
  ★ 新家（后续代码都写这里）：/data/workspace/source-code/xhs-demo/agent-lab/
     Maven 模块（已挂进 xhs-demo 父 pom），包名 com.myxhs.demo.agent，class 目标 11（可装载进 JDK11/17/21）
     依赖：asm / asm-util / asm-tree（9.7）；agent jar 用 build-agent.sh 构建（自带 ASM）
参照源码：/data/workspace/openjdk11u/src（java.instrument / java.lang.instrument / hotspot）
版本：2026-09-17 review 增补；2026-10-09 deep review（同步 1.1/2.8/第3组/第4组真实状态，证据见各实验日志）

────────────────────────
进度总览
────────────────────────
第 1 组：完成（1.1-1.10 ✅ 双入口/MANIFEST/API/类加载/停止设计/排队/libinstrument；剩余细节：1.2 Can-Redefine、1.3 appendToXxx 已有 P0a 实测、1.8 自 attach/安全开关扫盲）
第 2 组：完成（2.1-2.8 ✅ 织入/撤销/加方法被拒/打印入参/JIT 生效窗口；2.9/2.10 坑与补充实验部分完成）
第 3 组：基本完成（3.4 native demo ✅、capabilities ✅、hook 事件 ✅、JPLIS 链 ✅；剩余 3.x 纸面收尾可选）
第 4 组：基本完成（4.1 premain ✅、4.2/4.3 等价覆盖：-agentpath 启动期 demo ✅、4.4 ✅）
第 5 组：部分完成（5.4 perfdata/HPROF 已掌握；5.1 ✅ JMX 轻量：MXBean 全家 + 本地连接器实测；5.5 ✅ DiagnosticCommand 经 JMX 调 threadPrint 成功；剩余 5.2/5.6-5.8 可延后）
第 6 组：未开始（横切：版本差异与安全；65vs55 坑已记录一例）

────────────────────────
第 1 组：Java agent 与 Instrumentation（当前）
────────────────────────
1.1 agentmain / premain 双入口与加载时机（运行期 vs 启动期）—— ★
    [已实测] 运行期：agentmain 跑在 Attach Listener 线程（不是 main）
             证据：实验输出 thread=Attach Listener；
             源码：load_agent 直接调 JvmtiExport::load_agent_library，无 VMThread::execute；
                   Agent_OnAttach 无新线程创建
    [已实测] 启动期：premain 由 VMInit 事件触发，跑在 main 线程、应用 main 之前
             证据：03-java-agent/helloapp*.log（thread=main，抓到 HelloApp 首批类）
    [补充] 入口异常语义：premain 抛异常 → 启动失败；agentmain 抛异常 → 本次 attach 失败，进程继续
    [补充] 多 agent：多个 -javaagent 按命令行顺序依次执行
1.2 MANIFEST 约定全家 —— ★
    [已用] Agent-Class / Premain-Class / Can-Retransform-Classes（NoRetransform 实验：无 flag 则 addTransformer(t,true) 被拒）
    [剩余] Can-Redefine-Classes 实测（仅声明过）
    [补充] 只有 Premain-Class（无 Agent-Class）的 jar 被 load → 报错；反之 -javaagent 缺 Premain-Class 也报错
    [补充] 错误码语义：100 BADJAR / 101 NOTONCP / 102 STARTFAIL（已源码定位）
1.3 Instrumentation API 全景（五类能力）—— ★
    查类：getAllLoadedClasses / getInitiatedClasses / getObjectSize
    转换器：addTransformer / removeTransformer
    重定义：redefineClasses / retransformClasses / isModifiableClass
    附加：appendToBootstrapClassLoaderSearch / appendToSystemClassLoaderSearch（★，Arthas 注入 Spy 就靠它）
    判断：isRetransformClassesSupported / isRedefineClassesSupported / isNativeMethodPrefixSupported 等
    [补充-会用] getObjectSize：估算对象大小（连对象布局课）
    [补充-知道] setNativeMethodPrefix：native 方法增强的钩子（Arthas trace 对 native 方法无效的原因）
    [补充-知道] redefineModule（JDK9+）：模块重定义
    [实验完成] ClassWatchAgent：注册 transformer，打印"每次类加载"（app.log 里 [ClassWatch] 行）
    [踩坑记录] jar 漏打内部类 $1.class → 客户端 AgentInitializationException（对应错误码 102 STARTFAIL）
1.4 实验：最小 agent（打印一句话 + 数已加载类）—— ★  ✅ 完成
1.5 实验：loadAgent 装进 RandomLoop —— ★  ✅ 完成
    [已验证] 重复装载：agentmain 每次装载都会再调；类只加载一次、装进去无法卸载
    [已验证] 输出落在目标进程 stdout（app.log）；目标进程不重启（PID/启动时间不变）
1.6 Agent 类的加载与隔离 —— ★（新增，Arthas 隔离设计的前置）
    agent 类由 system class loader 加载（jar 被追加到 system classpath）→ 与业务类同空间
    后果：依赖冲突/污染；工程模式 = 独立 classloader（ArthasClassloader）
    对照组：Spy 必须放 bootstrap classpath（Arthas 用 appendToBootstrapClassLoaderSearch）
1.7 Agent 生命周期与停止/还原设计 —— ★（新增，Arthas stop 的前置）
    装了就常驻、类不可卸载（已实测）；重复装载防护（Arthas isInited 守卫）
    停止设计：服务线程、transformer 移除、retransform 回滚、注册的 shutdown 清理
    [已实测-重要坑] 每次 attach 创建新的 JPLIS agent + 新的 Instrumentation 实例；
      transformer 用【注册它的那个 Instrumentation】才能移除；否则 removeTransformer 找不到，
      retransform 时旧回调会把增强"重新织入"（撤销失败）；旧 agent 的回调一直存在到进程退出
      → 这就是 Arthas "already started, skip attach" 防护的根本原因
1.8 自 attach 与安全开关 —— ◐（新增）
    jdk.attach.allowAttachSelf（同 JVM attach 自己，测试常用）
    -XX:+DisableAttachMechanism / -XX:+StartAttachListener
    EnableDynamicAgentLoading（新版 JDK 的动态 agent 加载开关/警告；11.0.32 源码已见，注意版本差异）
1.9 agentmain 不该阻塞（attach 单线程模型的实操坑）—— ★（新增，可实验）
    Attach Listener 单线程：agentmain 里 sleep 10 秒 → 后续所有 attach 命令全排队
    实验：agent 里 sleep，另一个终端执行 jstack/jcmd 观察排队
    [已实测] sleep 10s 期间 jcmd 耗时 8233ms（正常 <400ms）；装载器也是等 agentmain 返回才回
1.10 libinstrument.so（JPLIS 机制层）—— ◐（新增）
    角色：JDK 自带的 JVMTI native agent，实现 java.lang.instrument 的整套机制
    职责：注册 VMInit（启动期调 premain）、注册 ClassFileLoadHook（分发 transformer）、
          创建 InstrumentationImpl、实现 retransformClasses0 等 native 方法
    位置：$JAVA_HOME/lib/libinstrument.so（JDK8: jre/lib/<arch>/）；
          源码 src/java.instrument/share/native/libinstrument/（JPLISAgent.c / InvocationAdapter.c）
    关键函数：DEF_Agent_OnLoad / DEF_Agent_OnAttach / eventHandlerVMInit / transformClassFile / startJavaAgent
    深度：知道职责与关键函数即可（不必通读源码）

第 1 组关键结论（已钉死）：
- agent = 约定入口（agentmain/premain）+ 装载通道（load/-javaagent）+ JVM 给的能力对象（Instrumentation）
- load 不需要 STW（在 Attach Listener 线程执行）；jstack/jmap 需要（提交 VM 操作给 VMThread）
- agentmain 里的活是在"业务线程照常跑"的情况下执行的（无停世界）

────────────────────────
第 2 组：字节码增强（Arthas 灵魂）
────────────────────────
2.1 ClassFileTransformer 工作流程：何时回调、classfileBuffer 进出、返回值语义 —— ★
2.2 字节码基础：往方法里插代码（ASM 直接改 / ByteBuddy 封装）—— ◐
    [已提前使用] 实验用到 ASM 最小模型：ClassReader → ClassVisitor/MethodVisitor → ClassWriter + visitCode 插指令
    [2.2 正式展开] JVM 描述符（(I)V）、指令类型（GETSTATIC/LDC/INVOKE*）、操作数栈与局部变量表、
                  COMPUTE_MAXS 与 COMPUTE_FRAMES、visitMethod/visitCode 的语义与透传规则
    [独立立项] 完整 ASM（Tree API、Frame、复杂改写）→ 后续单独学习（同 Maven）；
               ByteBuddy 属于封装层（少写 ASM），后续对比学习
2.3 retransformClasses vs redefineClasses：区别 + 限制 —— ★
    [已源码验证] retransform 是 VM 操作：Instrumentation.retransformClasses
                 → libinstrument native → jvmtiEnv::RetransformClasses
                 → VM_RedefineClasses + VMThread::execute（jvmtiEnv.cpp:448-449）
                 → VM_RedefineClasses : public VM_Operation（默认 safepoint）→ 会 STW
2.4 实验：最小改造一个方法（插记时/打印参数），验证生效 —— ★
2.5 与 Arthas 对应：Spy + Advice 就是"把回调织进方法"的工业化版本 —— ★
2.6 isModifiableClass：哪些类改不了 —— ★（新增）
    原始类型/数组/部分特殊类（VM anonymous 等）不可修改；改前必须先判断
2.7 为什么不能加字段/方法（JVM 规范根因）—— ★（新增，概念层）
    字段布局/对象大小、常量池引用、栈图（StackMapTable）校验等约束
    （注意：redefine 只允许改方法体；个别 flag 才允许增删私有方法）
2.8 JIT 与增强的相互影响 —— ★（新增）
    retransform 触发去优化（deoptimize）与重新编译："改了为什么没立即生效/什么时候生效"
2.9 实操坑集合 —— ◐（新增）
    VerifyError / ClassFormatError（改错字节码的报错）、
    transformer 链会被反复执行（重复增强风险）、
    lambda / 桥接方法 / 被内联的方法 trace 不到（Arthas 的常见困惑）
    [已踩坑] 编译 JDK 与目标 JVM 版本不匹配：class file version 65(Java21) 装不进 55(Java11)
             → 客户端只报 102 "agent failed to initialize"，真因要看【目标进程日志】
             → build-agent.sh 已修：显式 JDK11（AGENT_JDK 可覆盖）+ --release 11
    [已踩坑] 环境变量 JAVA_HOME 指向 JDK21 时，脚本若用 ${JAVA_HOME:-默认} 会静默用错版本
2.10 实验补充 —— ★（新增）
    改完还原（removeTransformer / 再次 retransform 回原样）、
    观察 deopt（-XX:+PrintCompilation）、
    agentmain sleep 复现 attach 排队（与 1.9 联动）

第 2 组关键结论（待验证）：
- "装 agent 不停世界"与"改字节码停世界"的对照会在实验里再次出现

────────────────────────
第 3 组：JVMTI 本体（概念必须，写 native 可选）
────────────────────────
3.1 JVMTI 是什么：C 接口 + capabilities + 事件回调模型 —— ★（概念）
3.2 核心能力：GetLoadedClasses / GetStackTrace / ClassFileLoadHook / RetransformClasses —— ★（概念）
3.3 Agent_OnLoad vs Agent_OnAttach：与 premain/agentmain 一一对应 —— ★
3.4 实验（可选）：最小 native agent（-agentlib:xxx）打印一句话 —— ○  ✅ 已提前完成（05-native-agent/）
    nativeagent.c：Agent_OnLoad + Agent_OnAttach + GetEnv(JVMTI) + GetLoadedClasses + AddCapabilities
    实测：-agentpath 启动期 → Agent_OnLoad；loadAgentPath 运行期 → Agent_OnAttach（AttachLoadNative 客户端）
    /proc/<pid>/maps 里可见 libnativeagent.so → 证明 JVM 直接 dlopen（不经过 libinstrument）
    [坑] C 的 fprintf stdout 有缓冲，必须 fflush（JVM 自身打印绕开 libc stdout 缓冲，所以对比明显）
3.5 与 Instrumentation 的关系：Java 的 inst 背后调的就是 JVMTI —— ★
3.6 事件清单（重点几个）—— ★（新增）
    ClassFileLoadHook（增强的源头）/ VMInit / ClassPrepare / ClassLoad /
    MethodEntry / MethodExit / Exception / ThreadStart-End / GC 开始结束
3.7 capabilities 协商 —— ◐（新增）
    AddCapabilities / GetPotentialCapabilities：native agent 必须的"能力申请"步骤
3.8 JVMTI vs SA：两种"读 JVM 内部"的方式 —— ○（新增）
    JVMTI 走 VM 正式接口（要配合）；SA 直接读内存（core/离线）

────────────────────────
第 4 组：启动期装载三兄弟
────────────────────────
4.1 -javaagent:xxx.jar[=opts]（Java agent）—— ★
4.2 -agentlib:name（native，按名字找，走 java.library.path）—— ◐
4.3 -agentpath:/abs/path.so（native，全路径）—— ◐
4.4 与 load 的对照：启动期 vs 运行期，同一件事的两个时机 —— ★

────────────────────────
第 5 组：相邻概念（辅助，不阻塞 Arthas）
────────────────────────
5.1 JMX 轻量版：MXBean 家族 / PlatformMBeanServer / 远程 JMX —— ◐
5.2 JFR 概念：事件模型 / 录制生命周期 —— ○
5.3 async-profiler 的装载方式（native agent；profiler 命令底座）—— ○
5.4 perfdata / HPROF —— ★ 已掌握
5.5 DiagnosticCommandMBean（jcmd 的 JMX 面）—— ◐（新增）
    jcmd 命令同时也是 MBean 操作：com.sun.management:type=DiagnosticCommand
5.6 jconsole 本地连接 = 先 attach 拿 JMX 地址 —— ◐（新增，把通道串起来）
    本地 JMX 连接依赖 attach 机制发现 connector 地址
5.7 JFR 启动四方式 + 版本差异 —— ◐（新增）
    -XX:StartFlightRecording / jcmd JFR.* / JFR API / jfr 工具离线读取
    版本：JDK11 无 streaming（14+ 才有）；JDK8 无 JFR（开源版）
5.8 JDWP 简版（jdb / 远程调试）—— ○（新增，路线图第 8 项）

────────────────────────
第 6 组：横切 —— 版本差异与安全（新增）
────────────────────────
6.1 JDK 8 / 11 / 21 的 agent 装载差异 —— ★
    JDK8：tools.jar 里有 attach API；模块化前
    JDK9+：jdk.attach 模块；访问内部 API 需要 --add-opens（如 confucius 的 Unsafe 课遇到）
    JDK21+：动态 agent 加载有警告/开关（EnableDynamicAgentLoading）
6.2 安全边界与攻击面 —— ★
    attach = 同用户即可注入代码（socket 0600 + 属主校验）
    agent 加载开关、telnet/端口暴露（Arthas 场景）
    为什么生产上要谨慎开 attach / 限制 jdk.attach 能力

────────────────────────
依赖关系（谁真正阻塞 Arthas）
────────────────────────
Arthas 启动链路          ← 第 1 组（含 1.6/1.7）+ 第 4 组
Arthas watch/trace/tt    ← 第 2 组（核心，含 2.6-2.8）+ 第 3 组概念
Arthas profiler          ← 5.3
Arthas jvm/dashboard     ← 5.1 / 5.5
必须优先级：1 > 2 > 3(概念) > 4 > 其他

────────────────────────
攻克顺序
────────────────────────
阶段 1：第 1 组（Java agent 本体，含 1.6-1.9）
阶段 2：第 2 组（字节码增强，含 2.6-2.10）
阶段 3：第 3 组（JVMTI 概念）
阶段 4：第 4 组 + 第 5 组补盲 + 第 6 组横切 → 进入 Arthas

────────────────────────
自测标准（进入 Arthas 的判定）
────────────────────────
1. 能画：agent 被 load 后发生了什么（JVM 调 agentmain → 拿 Instrumentation → 装 transformer）
2. 能写：十行 agent + 打 jar + 用 loadAgent 装进一个运行中的进程
3. 能改：用 transformer 给一个方法插记时代码，验证生效
4. 能说：retransform 为什么不能加方法；Spy 织入与它是什么关系
5. 能说：-javaagent 和 load 的区别（时机 / 入口 / 通道）
6. 能说：agent 的类加载在哪、为什么会污染（隔离的必要性）（新增）
7. 能说：改完字节码什么时候生效（JIT 去优化/重编译）（新增）
8. 能说：agentmain 阻塞会堵住什么（attach 单线程队列）（新增）

────────────────────────
已完成实验产物（03-java-agent/）
────────────────────────
MyAgent.java           空投代码：agentmain 打印 args/已加载类数/当前线程
MANIFEST.MF            约定声明：Agent-Class: MyAgent
AttachLoadAgent.java   客户端：attach 35091 + loadAgent(myagent.jar, options)
myagent.jar            包裹（Agent-Class + MyAgent.class）
观测日志               playground/app.log 里 [MyAgent] 行（两次：demo-args / second-run）
复现命令（注意：旧 PID 均已失效；目标进程已退出，重跑先启动）：
  # 1) 先启动目标（示例 RandomLoop；PID 每次都不同）
  cd /data/workspace/source-code/experiments/jvm-diagnostics/playground
  setsid nohup /data/workspace/openjdk11/bin/java -cp out RandomLoop > app.log 2>&1 &
  # 2) 动态取 PID 再装载
  PID=$(/data/workspace/openjdk11/bin/jps -l | grep RandomLoop | awk '{print $1}')
  /data/workspace/openjdk11/bin/java -cp ../03-java-agent AttachLoadAgent $PID /data/workspace/source-code/experiments/jvm-diagnostics/03-java-agent/myagent.jar hello
  tail -2 app.log

────────────────────────
下一步（按顺序）
────────────────────────
1. ✅ addTransformer 观察器实验（ClassWatchAgent + watch.jar；含内部类打包踩坑）
2. ✅ 1.6 类加载与隔离（agent 类 + 应用类同为 AppClassLoader）
3. ✅ 1.9 agentmain 阻塞 → attach 排队（sleep 10s → jcmd 排队 8.2s）
4. ✅ 1.1 补：premain 侧实验（-javaagent；thread=main；抓到 HelloApp 等首批类）
5. ✅ 工具：build-agent.sh（编译全量+自动 MANIFEST）+ maven-agent-demo（Maven manifestEntries 实测装载成功）
6. ✅ 第 2 组主体（织入/撤销/加方法被拒/打印入参/JIT 生效窗口：hotloop.log 35字节×19 + 速率16M→8.2M）
7. ✅ 第 3/4 组主体（native 双入口/capabilities/hook 事件/JPLIS 链；-agentpath 启动期）
8. 待补（进 Arthas 前建议做，均为 15-30 分钟小项）：
   a. ✅ 1.3 appendToBootstrap 实测（06-bootstrap/：追加前 CNFE → 追加后 loader=null + 可调用；重复装载幂等）
   b. ✅ 1.7 停止/还原设计（06-bootstrap/StopDemoAgent：start 织入+心跳 → stop 移除+还原+停线程；失败-修复完整记录：ASM 漏打→102→目标日志定位）
   c. ✅ 5.1 JMX 轻量（07-jmx/JmxReader：attach→startLocalManagementAgent→本地连接器→读 MXBean 全家；5.5 顺带：经 DiagnosticCommand 调通 threadPrint）
9. 可延后：1.8、Can-Redefine 实测、5.2/5.5-5.8、6.x、SA/JDWP、redefine 路由精确性补验证（可选）
10. 然后：进入 Arthas（math-game + attach + dashboard/watch）
11. ✅ 2026-10-09 deep review：产物/证据/计划三维核查通过；发现 1 个证据缺口 + 3 个待做项（见下）
12. ✅ JMX 输出落盘（07-jmx/jmx-output.log：本地连接器/uptime/heap/线程/类/GC/threadPrint 全量输出；target.log 为靶子日志）
13. ✅ Arthas ③部分 + ④ + ②快速过：retransform -l/-d 循环 ✅；profiler cpu/alloc ✅；
    monitor/sm/classloader/logger/heapdump ✅；mc+redefine 热替换 ✅（TickApp tick→HOTFIX）；
    TickApp agent stop ✅（math-game 的 stop 待收尾时做）
14. ✅ G1-G5 生产实践小结（见本次输出：安全三层边界/开销分层数字/会话管理/清理对照表/工具对照口诀）
15. ✅ stop math-game 收尾（端口释放、线程消失、应用继续跑；stop 异步 + grep 计数坑已记录）
16. ✅ arthas-02 已追加 stop/G1-G5 → 本项打勾
17. ✅ Arthas track 宣布完成 🎉（动手闭环见 arthas-02；源码地图见 arthas-01）
18. ✅ 5.7 JFR 实测完成（jcmd JFR.start/check/dump/stop + FLR 魔数 + JDK17 jfr summary/print 读取；
    产物持久目录 /data/workspace/arthas-output/jfr-demo.jfr；注意 /tmp/opencode 曾被系统清理→持久产物别放 /tmp）
19. SA 处置（2026-10-10 确认）：**不单独规划** → 降为一行知识点：gcore/core dump →
    `jhsdb jstack --exe <java> --core <file>`（attach 全废/进程死时的最后手段，日常极少用）
20. JDWP/jconsole 降级（2026-10-10 确认）：同样不单独规划 → 一行知识点：
    JDWP = IDEA 远程调试的底层协议（jdb 命令行冷门）；jconsole 本地连接 = attach 拿 JMX 地址
21. 现状：路线图第 8 项与 5.x/6.x 的剩余全部为"一行知识点"级 → **"JVM 诊断 + Agent"整条轨道可收官**
22. ✅ 综合演练收官（08-drill/IncidentApp 三问题：jstat 趋势 → dashboard/thread CPU 定位 →
    profiler 交叉验证 → trace/watch 抓异常 → ognl 读根因 → clear 白清 → OOM×2 →
    OOME 自动 dump 515MB → 解析器 [B] 473MB/97% 铁证；完整记录见 arthas-03-排障实战.md）
23. 🎉 "JVM 诊断 + Agent"整条轨道正式收官（docs/jvm 15 篇 + 9 个实验目录 + agent-lab 模块）
24. ✅ JMX/JFR 深入轨道完成 🎉（5 Session：A 自定义MBean/远程客户端 → B 通知+远程认证双文件 →
    C 事件模型+自定义settings → D 自定义事件API+streaming → E 分工决策；
    docs/jmx-jfr-01~03 + 09-jmx-jfr 实验目录）
25. ✅ P9 域1（对象与内存）完成（2026-10-10）：5 实验（对象布局 JOL/TLAB/逃逸分析/压缩指针/humongous）+
    deep review 通过 + 查漏补缺补 2 实验（mark word 位布局/数组布局）+ 2 补充（分配链路/栈上分配误区）；
    文档 p9-domain1-对象与内存.md（考点速查 10 行）
26. ✅ P9 域2（类加载）完成（2026-10-10）：4 实验（层级双亲委派/打破委派/热部署/初始化时机）+
    Tomcat 隔离叙事 + deep review 通过（修 N1 独立证明缺口→InitTimingDemo2、修 N2 因果表述）；
    文档 p9-domain2-类加载.md（考点速查 7 行）
27. ✅ P9 域3（GC 与 G1）完成（2026-10-10）：初版 5 实验 + deep review → 补 Mixed GC 实证
    （G1HeapWastePercent 阈值决策、Prepare Mixed→Mixed 序列、CSet 57-203 regions）+
    Full GC 触发条件 + Card Table vs RSet 对比 + 停顿预测 + G1 vs ZGC 对比；
    文档 p9-domain3-GC与G1.md（考点速查 15 行）
28. ✅ P9 域4（JIT 与去优化）完成（2026-10-10）：4 实验（分层编译/OSR/内联/去优化触发）；
    文档 p9-domain4-JIT与去优化.md（考点速查 9 行）
29. ✅ P9 域5（线程/安全点/锁）完成（2026-10-10）：5 实验（线程状态/锁膨胀/死锁检测/虚拟线程JDK21/
    安全点排查）+ deep review 通过（修 N1 TIMED_WAITING 检查时机、修 N2 轻量级锁持锁时读）；
    文档 p9-domain5-线程与锁.md（考点速查 11 行）
30. ✅ P9 并发编程 JUC（独立轨道，Java SE 层非 JVM）完成（2026-10-10）：3 实验（CAS+ABA+并发工具/
    volatile+ThreadLocal/线程池流程）+ AQS 源码；文档 p9-juc-并发编程.md（11 考点）
    ⚠️ 标注：JVM 轨道域5 = 线程模型/SafePoint/锁膨胀/JMM（HotSpot 层）；JUC = java.util.concurrent 库
31. ✅ P9 域6（诊断调优方法论）完成（2026-10-10）：以"工具失效"为核心命题——
    jstack 依赖 attach→VMThread→SafePoint（源码实证），VMThread 卡死时 jstack 也卡死；
    逃生通道 kill -3（信号）→gdb（OS 级最后防线）→/proc+perf（内核态）；
    +6 个 P9 盲区（SafePoint卡死JNI/CodeCache满/DirectByteBuffer泄漏/Classloader泄漏/
    Native Crash/多进程attach）+4 案例叙事；文档 p9-domain6-诊断调优方法论.md（12 考点）
32. ✅ P9 全部 6 域完成 🎉（对象内存/类加载/GC与G1/JIT与去优化/线程与锁/诊断调优方法论）
    + JUC 独立轨道（AQS/CAS/ThreadLocal/线程池等 Java SE 层）
33. ✅ P9 阶段1（基础闭环 #2+#1）完成（2026-10-10）：解释器执行引擎（javap 字节码/栈执行追踪/
    HotSpot 模板解释器源码）+ JVM 启动流程（Launcher→JNI_CreateJavaVM→main）；
    文档 p9-domain7-解释器与启动流程.md（8 考点）
34. ✅ P9 阶段1 补充（#51-#60）完成（2026-10-10）：方法调用5种/异常字节码/线程全景/Shutdown/
    vtable/invokedynamic；文档 p9-domain7-补充.md（10 考点）
35. ✅ P9 阶段2（#3 ZGC/Shenandoah + #4 JNI）完成（2026-10-10）：ZGC 亚毫秒停顿实测（JDK21 0.01-0.03ms）+
    染色指针/读屏障/全并发转移；JNI 引用类型/Critical 源码；文档 p9-domain8-ZGC与JNI.md（8 考点）
36. ✅ P9 阶段2 补充（#61-#70）完成（2026-10-10）：ZGC分代实测(JDK21)/颜色位布局/ZGC vs Shenandoah/
    JNI注册/缓存/线程安全/Panama趋势；文档 p9-domain8-补充.md（10 考点）
37. ✅ P9 阶段2 二轮补（#71/#73）完成（2026-10-10）：ZGC Allocation Stall（分配停顿非STW）+
    JNI 异常处理（ExceptionCheck/ExceptionClear，源码）；域8 收住（20 考点，边际效益递减点）
38. ✅ P9 阶段3（#7 NMT + #5 容器化）完成（2026-10-10）：NMT summary 实测（6 区域堆外内存）+
    容器化 flag（UseContainerSupport/MaxRAMPercentage）+ 容器内存构成/OOM Kill 分析；
    文档 p9-domain9-NMT与容器化.md（8 考点）
39. ✅ P9 阶段3 二轮补（#78/#79）完成（2026-10-10）：NMT baseline 对比工作流实测+
    cgroup v1/v2 差异实测；域9 收住（10 考点）
40. ✅ P9 阶段4（#6 参数系统 + #8 趋势）完成（2026-10-10）：参数 8 大类速查（内存/GC/JIT/线程/
    诊断/容器/ZGC/安全）+ CDS/AOT/GraalVM flag 实测 + 趋势总结；文档 p9-domain10-参数与趋势.md
41. 🎉 P9 JVM 轨道全部完成（10 域 + JUC 独立轨道）：
    域1对象内存/域2类加载/域3 GC与G1/域4 JIT/域5线程锁/域6诊断调优/域7解释器启动/
    域8 ZGC与JNI/域9 NMT容器化/域10参数趋势 + JUC独立轨道
42. ✅ 网络轨道启动（2026-10-10）：以 `成长之路/02-网络协议-知识点规划.md` 为基准（13本书/5子领域），
    去重重组为 10 域（实验驱动，像 JVM 轨道）；四轮查漏补缺覆盖子领域1+2+3+5 核心考点
43. ✅ 网络域1（网络基础+分层模型）完成：tcpdump 抓包看分层/ARP 请求应答/路由最长前缀匹配/
    ICMP/MTU分片实测（1472成功/1500失败）/VLAN/IPsec/延迟拆解；文档 p9-net-domain1-网络基础.md
44. ✅ 网络域1 补漏（#90-#95）完成（2026-10-10）：OSI vs TCP/IP 对比/网络设备/MAC表学习/
    IPv4格式/CIDR/NAT实测（iptables SNAT/DNAT+Docker MASQUERADE）；域1 收住（14 考点）
45. ✅ 网络域2（TCP 连接管理）完成（2026-10-10）：三次握手/四次挥手 tcpdump 抓包实测/
    11种状态机/ss实测/TIME_WAIT vs CLOSE_WAIT排障/SYN cookies+内核参数实测；
    文档 p9-net-domain2-TCP连接管理.md（8 考点）
46. ✅ 网络域2 补漏（#96-#101）完成（2026-10-10）：TCP头部格式/序列号确认号/可靠传输基础/
    RST复位/TCP四元组/MSS-SACK-wscale（抓包options实测）；域2 收住（14 考点）
47. ✅ 网络域3（TCP 流控+拥塞控制）完成（2026-10-10）：cubic 默认算法 sysctl 实测/
    滑动窗口/拥塞控制四阶段/Cubic vs BBR/零窗口/TFO；文档 p9-net-domain3.md（8 考点）
48. ✅ 网络域3 补漏（#102-#104）+ BGP 补充完成（2026-10-10）：BBR 状态机/Cubic 算法/BDP +
    域1 补 BGP（OSPF vs BGP/AS Path/Calico）；域3 收住（11 考点），域1 收住（15 考点）
49. ✅ 网络域4（UDP+应用层协议）完成（2026-10-11）：UDP对比/DNS dig实测/HTTP curl实测/
    TLS握手实测/HTTP2 ALPN实测/WebSocket + HTTP3-QUIC趋势；文档 p9-net-domain4.md（8 考点）
50. ✅ 网络域4 补漏（#106-#109）完成（2026-10-11）：HTTP 缓存（强+协商）/状态码方法语义/
    HTTP1.1 队头阻塞/DNS 缓存层级；域4 收住（12 考点）
51. ✅ 网络域5（内核收发包全链路）完成（2026-10-11）：TSO/GRO ethtool 实测/RPS-RFS/
    收发包路径叙事+源码锚点/socket缓冲 sysctl 实测；文档 p9-net-domain5.md（8 考点）
52. ✅ 网络域5 补漏（#110-#113）完成（2026-10-11）：NAPI-softirq 机制/sk_buff 结构/
    qdisc 排队/netfilter 钩子（衔接域10）；域5 收住（12 考点）
53. ✅ 网络域5 二轮补（#114-#117）完成（2026-10-11）：中断绑核/XPS/TCP队列/邻居子系统；
    域5 收住（16 考点）
54. ✅ 网络域6（I/O 模型+epoll/Reactor）完成（2026-10-11）：阻塞非阻塞 Java NIO 实测/
    epoll Selector 实测/select-poll-epoll 对比/LT-ET/惊群/Reactor；文档 p9-net-domain6.md（8 考点）
55. ✅ 网络域6 补漏（#118-#120）完成（2026-10-11）：Proactor vs Reactor/epoll 源码级/
    Netty 线程模型；域6 收住（11 考点）
56. ✅ 网络域7（Socket 编程+调优）完成（2026-10-11）：Socket 选项/域套接字/多播广播/原始套接字/内核调优参数 sysctl 全套；文档 p9-net-domain7.md（8 考点）
57. ✅ 网络域7 补漏（#121-#123）完成（2026-10-11）：粘包拆包（P9高频）/优雅关闭/服务器模型；域7 收住（11 考点）
58. ✅ 网络域8（零拷贝+高性能）完成（2026-10-11）：传统vs mmap vs sendfile 拷贝路径/DPDK-RDMA-XDP对比/TCP内存开销/fd端口约束 sysctl 实测；文档 p9-net-domain8.md（8 考点）
59. ✅ 网络域8 补漏（#124-#126）完成（2026-10-11）：Java 零拷贝 API/splice 原理/sendfile 限制/io_uring；域8 收住（11 考点）
60. ✅ 网络域9（网络排障方法论）完成（2026-10-11）：tcpdump BPF 过滤器/ss 排障实测/监控指标/工具失效逃生（/proc/net 最后防线）；文档 p9-net-domain9.md（8 考点）
61. ✅ 网络域9 补漏（#127-#128）完成（2026-10-11）：重传零窗口分析/ping-mtr 延迟实测；域9 收住（10 考点）
62. ✅ 网络域10（容器网络+K8s）完成（2026-10-11）：Docker 网络 veth-bridge 实测/K8s Service-kube-proxy/CNI 对比（Flannel-Calico-Cilium）/负载均衡 L4-L7-一致性哈希；文档 p9-net-domain10.md（8 考点）
63. ✅ 网络域11（SDN+VPC 控制面）完成（2026-10-11）：SDN 架构/OpenFlow 流表/VPC 模型/BGP EVPN/Neutron 架构/安全组实现（iptables 实测）；文档 p9-net-domain11.md（8 考点）
64. ✅ 网络域11 补漏（#129-#131）完成（2026-10-11）：OVS 架构/VXLAN 封装/Spine-Leaf 架构；域11 收住（11 考点）
65. 🎉 网络轨道全部完成（11 域，130 考点）！

66. ✅ OS 轨道启动（2026-10-11）：9 域结构（内核用户态/内存管理/进程线程/同步原语/IPC/文件系统/中断IO/多核NUMA/虚拟化容器）；概念原理级（不死扣源码），实验驱动；三轮查漏补缺（OOM Killer/cgroups/namespace/信号/大页/Swap/COW/DemandPaging/kswapd）
67. ✅ OS域1（内核与用户态）完成（2026-10-11）：strace 系统调用实测（22次）/vDSO (/proc/self/maps [vdso])/proc 文件系统（meminfo/status 实测）/sysfs（net/cpu 实测）/dmesg；文档 p9-os-domain1.md（7 考点）
68. 下一域：OS域2（内存管理：虚拟内存/分页/页表/TLB/缺页/COW/DemandPaging/伙伴系统/slab/mmap/OOM Killer/大页/Swap/kswapd）
69. ✅ OS域1 补漏完成（2026-10-11）：Linux 启动流程/sysctl 系统讲；域1 收住（9 考点）
70. 下一域：OS域2（内存管理：虚拟内存/分页/页表/TLB/缺页/COW/DemandPaging/伙伴系统/slab/mmap/OOM Killer/大页/Swap/kswapd）
71. ✅ OS域2（内存管理）完成（2026-10-11）：11 实验（meminfo/buddyinfo/slabinfo/swaps/hugepages/stat缺页/kswapd 全实测）+ COW/DemandPaging/mmap/OOM Killer 叙事；文档 p9-os-domain2.md（12 考点）
72. 下一域：OS域3（进程与线程：task_struct/fork/上下文切换/CFS 调度/信号）
73. ✅ OS域2 补漏完成（2026-10-11）：四级页表结构（PGD-P4D-PUD-PMD-PTE）/THP 透明大页/页回收算法（LRU-Clock）；域2 收住（15 考点）
74. 下一域：OS域3（进程与线程：task_struct/fork/上下文切换/CFS 调度/信号）
75. ✅ OS域3（进程与线程）完成（2026-10-11）：/proc status 实测（进程状态/线程数/上下文切换）/vmstat cs/nice CFS/信号；文档 p9-os-domain3.md（8 考点）
76. 下一域：OS域4（同步原语：锁/内存屏障/RCU/死锁）
77. ✅ OS域3 补漏完成（2026-10-11）：实时调度（SCHED_FIFO-RR vs CFS）/进程组会话（信号传播）；域3 收住（10 考点）
78. 下一域：OS域4（同步原语：锁/内存屏障/RCU/死锁）
79. ✅ OS域4（同步原语）完成（2026-10-11）：自旋锁/互斥锁/futex/内存屏障（x86 TSO+JVM volatile 衔接）/RCU/死锁四条件；文档 p9-os-domain4.md（7 考点）
80. 下一域：OS域5（进程间通信：管道/共享内存/信号量/eventfd）
81. ✅ OS域4 补漏完成（2026-10-11）：读写锁（读共享写独占）/活锁饥饿（死锁三态对比）；域4 收住（10 考点）
82. 下一域：OS域5（进程间通信：管道/共享内存/信号量/eventfd）
83. ✅ OS域5（进程间通信）完成（2026-10-11）：ipcs 实测（共享内存/信号量/消息队列）/mkfifo 命名管道/共享内存-信号量-eventfd 叙事/8 种 IPC 对比；文档 p9-os-domain5.md（6 考点）
84. 下一域：OS域6（文件系统：VFS/inode/日志/COW/崩溃一致性）
85. ✅ OS域6（文件系统）完成（2026-10-11）：df/mount 实测（ext4/overlay/tmpfs/inode）/VFS/日志模式/COW/崩溃一致性；文档 p9-os-domain6.md（7 考点）
86. 下一域：OS域7（中断与 I/O：top half/bottom half/中断上下文）
87. ✅ OS域7（中断与 I/O）完成（2026-10-11）：/proc interrupts 实测（ens33 中断 107万）/softirqs 实测（NET_RX=100万）/top half-bottom half/中断上下文；文档 p9-os-domain7.md（6 考点）
88. 下一域：OS域8（多核与 NUMA：缓存一致性 MESI/内存屏障/per-CPU）
89. ✅ OS域7 补漏完成（2026-10-11）：workqueue（内核延迟工作三件套 softirq-tasklet-workqueue）；域7 收住（8 考点）
90. 下一域：OS域8（多核与 NUMA：缓存一致性 MESI/内存屏障/per-CPU）
91. ✅ OS域8（多核与 NUMA）完成（2026-10-11）：numactl 实测（单节点）/CPU 缓存层次（L1-L3 实测）/MESI/内存屏障/per-CPU；文档 p9-os-domain8.md（7 考点）
92. 下一域：OS域9（虚拟化与容器：Namespace/cgroups v1 vs v2/KVM）
93. ✅ OS域9（虚拟化与容器）完成（2026-10-12）：Namespace 8种实测+容器对比/cgroup v2 实测/KVM/容器原理（Namespace+cgroups+OverlayFS）；文档 p9-os-domain9.md（7 考点）
94. 🎉 OS 轨道全部完成（9 域，~75 考点）！

95. ✅ 目录重组完成（2026-10-12）：35 篇文档按轨道分目录（jvm/13、network/11、os/9）
96. ✅ MySQL 轨道启动（2026-10-12）：9 域结构（InnoDB架构/事务并发/索引/redo-undo/查询优化/复制高可用/监控/连接中间件/分布式选型）；Docker mysql:8.0 实验环境
97. ✅ MySQL域1（InnoDB架构与线程模型）完成（2026-10-12）：线程模型对比 Tomcat（每连接一线程）/InnoDB 后台线程 26 个实测（P_S.threads）/Buffer Pool/Change Buffer/redo vs binlog；文档 p9-mysql-domain1.md（7 考点）
98. 下一域：MySQL域2（事务与并发控制：ACID/MVCC/行锁/普通读vs当前读/死锁/MDL）
98. ✅ MySQL域1 补漏完成（2026-10-12）：BP LRU 改进（young-old 区中点插入）/Double Write（防撕裂写）/redo 刷盘策略/连接+查询完整流程；域1 收住（11 考点）
99. 下一域：MySQL域2（事务与并发控制：ACID/MVCC/行锁/普通读vs当前读/死锁/MDL）
100. ✅ MySQL域2（事务与并发控制）完成（2026-10-12）：隔离级别实测（REPEATABLE-READ）/MVCC/行锁（data_locks X,REC_NOT_GAP 实测）/死锁（SHOW ENGINE INNODB STATUS 实测）/MDL；文档 p9-mysql-domain2.md（8 考点）
101. 下一域：MySQL域3（索引原理：B+Tree/数据页/聚簇vs二级/回表/索引维护/FULLTEXT）
101. ✅ MySQL域2 补漏完成（2026-10-12）：间隙锁 Next-Key Lock（RR 防幻读）/死锁预防（等待图）/XA 分布式事务/乐观悲观锁；域2 收住（12 考点）
102. 下一域：MySQL域3（索引原理：B+Tree/数据页/聚簇vs二级/回表/索引维护/FULLTEXT）
102. ✅ MySQL域2 二轮补完成（2026-10-12）：Read View（m_ids/min_trx_id/max_trx_id 可见性判断）/插入意向锁/自增锁模式；域2 收住（15 考点）
103. 下一域：MySQL域3（索引原理：B+Tree/数据页/聚簇vs二级/回表/索引维护/FULLTEXT）
103. ✅ MySQL域3（索引原理）完成（2026-10-12）：EXPLAIN 实测（type=ref/ALL/Using index）/覆盖索引/索引失效 4 场景实测/B+Tree/聚簇vs二级/回表；文档 p9-mysql-domain3.md（8 考点）
104. 下一域：MySQL域4（Redo/Undo/WAL 与崩溃恢复）
104. ✅ MySQL域3 补漏完成（2026-10-12）：索引下推 ICP（Using index condition）/索引基数区分度；域3 收住（10 考点）
105. 下一域：MySQL域4（Redo/Undo/WAL 与崩溃恢复）
105. ✅ MySQL域3 三轮补完成（2026-10-12）：B+Tree vs B-Tree vs Hash（P9经典题）/叶子链表范围查询/联合索引排序最左前缀；域3 收住（13 考点）
106. 下一域：MySQL域4（Redo/Undo/WAL 与崩溃恢复）
106. ✅ MySQL域4（Redo/Undo/WAL 与崩溃恢复）完成（2026-10-12）：SHOW ENGINE INNODB STATUS 实测（LOG/LSN/sync_binlog=1）/WAL/Group Commit/Undo/ARIES/两阶段提交；文档 p9-mysql-domain4.md（8 考点）
107. 下一域：MySQL域5（查询优化：优化器/EXPLAIN/慢查询/子查询优化）
107. ✅ MySQL域4 补漏完成（2026-10-12）：Binlog 格式 ROW-STATEMENT-MIXED（P9经典题）/Redo 物理格式/崩溃恢复 prepare-commit 场景；域4 收住（11 考点）
108. 下一域：MySQL域5（查询优化：优化器/EXPLAIN/慢查询/子查询优化）
108. ✅ MySQL域5（查询优化）完成（2026-10-12）：EXPLAIN FORMAT=TREE 实测（cost=0.35）/慢查询日志实测（ON）/子查询 EXISTS-JOIN/执行计划漂移；文档 p9-mysql-domain5.md（6 考点）
109. 下一域：MySQL域6（复制与高可用：binlog/GTID/主从/并行复制/故障切换/备份）
109. ✅ MySQL域5 补漏完成（2026-10-12）：联接算法（Nested Loop/Hash Join/Block NL，P9高频）+ 参考来源章节（官方文档/经典书籍/Percona）；域5 收住（7 考点）
110. ⚠️ MySQL 质量修复待办：给域1-5 补参考来源；用官方文档核对关键声明；引用 ARIES 原论文
111. 下一域：MySQL域6（复制与高可用：binlog/GTID/主从/并行复制/故障切换/备份）
111. ✅ MySQL域6（复制与高可用）完成（2026-10-12）：binlog/GTID 实测/并行复制 4 workers 实测/mysqldump 实测/故障切换/备份恢复/Group Replication；文档 p9-mysql-domain6.md（8 考点）
112. 下一域：MySQL域7（性能监控与诊断：P_S/sys/锁监控/慢日志）
112. ✅ MySQL域6 补漏完成（2026-10-12）：半同步复制（异步-半同步-组复制对比）/复制监控（Seconds_Behind_Master）/大事务问题；域6 收住（11 考点）
113. 下一域：MySQL域7（性能监控与诊断：P_S/sys/锁监控/慢日志）
113. ✅ MySQL域7（性能监控与诊断）完成（2026-10-12）：P_S 实测（71条语句）/sys 视图实测（95th percentile）/data_locks 锁监控/慢日志分析流程；文档 p9-mysql-domain7.md（6 考点）
114. 下一域：MySQL域8（连接管理与中间件：连接池/ProxySQL/连接数优化）
114. ✅ MySQL域7 补漏完成（2026-10-12）：慢 SQL 实战（P_S 排行 ALTER 16.79s 实测）/EXPLAIN ANALYZE（actual time 实测）/锁等待实战/参考来源；域7 收住（8 考点）
115. 下一域：MySQL域8（连接管理与中间件：连接池/ProxySQL/连接数优化）
115. ✅ MySQL域8（连接管理与中间件）完成（2026-10-12）：连接数参数实测（max_connections=151/连接使用率）/连接池原理/ProxySQL/连接数优化；文档 p9-mysql-domain8.md（6 考点）
116. 下一域：MySQL域9（存储引擎对比与分布式：LSM vs B+Tree/CDC/HTAP/CAP/分库分表/选型）
116. ✅ MySQL域9（存储引擎对比与分布式）完成（2026-10-12）：LSM vs B+Tree/CDC/HTAP/CAP/分库分表/选型决策框架；文档 p9-mysql-domain9.md（6 考点）
117. 🎉 MySQL 轨道全部完成（9 域，~75 考点）！
117. ✅ MySQL 质量修复完成（2026-10-12）：域1-9 全部补参考来源（官方文档+经典书籍+权威博客）；域2/4 补 ARIES 论文引用（Mohan et al., 1992）；核对官方默认值（thread_cache=9/max_connections=151/REPEATABLE-READ/sync_binlog=1/gtid_mode=OFF/replica_parallel_workers=4/wait_timeout=28800）
118. 🎉 MySQL 轨道正式收官（9 域，~88 考点，质量修复完成）！
119. ✅ MySQL域4 WAL 深度补充完成（2026-10-12）：Mini-Transaction（MTR）/checkpoint 触发机制/WAL 通用视角（MySQL-PostgreSQL-LevelDB-Raft 对比）/WAL 放大；域4 收住（15 考点）
