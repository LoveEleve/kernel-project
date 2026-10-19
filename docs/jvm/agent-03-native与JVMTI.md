# agent-03 · native agent 与 JVMTI

## 一、libinstrument 到底是什么

- **同一个东西的两个名字**：JPLIS（机制名：Java Programming Language Instrumentation Services）
  = `libinstrument.so`（实现文件，JDK 自带；JDK11 在 `$JAVA_HOME/lib/`，JDK8 在 `jre/lib/<arch>/`；
  源码 `src/java.instrument/share/native/libinstrument/`）
- 它自己就是一个 **JVMTI native agent**（JDK 内置、名字叫 "instrument"），职责是实现 java.lang.instrument：
  注册 VMInit（启动期调 premain）/ 注册 ClassFileLoadHook（分发 transformer）/
  创建 `InstrumentationImpl` / 实现 retransformClasses0 等 native 方法
- 关键函数：`DEF_Agent_OnLoad` / `DEF_Agent_OnAttach` / `eventHandlerVMInit` /
  `eventHandlerClassFileLoadHook` / `transformClassFile` / `startJavaAgent` /
  `loadClassAndCallAgentmain` / `createNewJPLISAgent` / `createInstrumentationImpl`
- 每次 attach 新建一个 JPLIS agent + 新的 InstrumentationImpl
  （实测两次 attach 的 inst hash 不同；transformer 列表挂在实例上）

## 二、自己的 .so：不走 libinstrument

- `Agent_OnLoad` / `Agent_OnAttach` 是**任何 native agent 的必备入口**
  （启动期用前者，运行期用后者；libinstrument 有它们，只因它自己也是 native agent）
- JVM 直接处理：dlopen → dlsym("Agent_OnAttach") → 调用
  （源码：`JvmtiExport::load_agent_library` → `os::find_agent_function` →
  `(*on_attach_entry)(&main_vm, options, NULL)`；缺符号则打印 "not available" 并卸载）
- "instrument" 只是内置 agent 的名字（`os::find_builtin_agent` 优先解析）；
  自己的 so 用绝对路径（loadAgentPath）或名字（loadAgentLibrary）

## 三、jar 与 so 对照

| | Java agent（jar） | native agent（so） |
|---|---|---|
| 入口 | premain / agentmain（Java 方法） | Agent_OnLoad / Agent_OnAttach（C 符号） |
| 谁翻译/管理 | libinstrument.so（JPLIS） | JVM 直接管，无翻译层 |
| 启动期参数 | -javaagent:xxx.jar[=opts] | -agentlib:name / -agentpath:/abs/xxx.so[=opts] |
| 运行期命令 | load instrument false `<jar>`=`<opts>` | load `<路径>` true `<opts>` |
| 客户端 API | vm.loadAgent(jar, opts) | vm.loadAgentPath(path, opts) |
| 能力对象 | Instrumentation（JVM 递给你） | jvmtiEnv（入口里自己 GetEnv 拿） |

## 四、最小 native agent 实测（`05-native-agent/nativeagent.c`）

```c
JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *options, void *reserved)   // 启动期
JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *vm, char *options, void *reserved) // 运行期
{
    (*vm)->GetEnv(vm, (void**)&jvmti, JVMTI_VERSION_1_2);
    (*jvmti)->GetLoadedClasses(jvmti, &count, &classes);   // 用 JVMTI 查类
    caps.can_tag_objects = 1;
    (*jvmti)->AddCapabilities(jvmti, &caps);               // 能力协商
}
```
- 编译：`gcc -shared -fPIC -I$JAVA_HOME/include -I$JAVA_HOME/include/linux -o libnativeagent.so`
- 启动期：`java -agentpath:…/libnativeagent.so=startup-opt …` → Agent_OnLoad
- 运行期：`AttachLoadNative <pid> <so> hello-from-so`（vm.loadAgentPath）→ Agent_OnAttach：
  `options=hello-from-so` / `GetEnv rc=0` / `GetLoadedClasses count=740` / `AddCapabilities err=0`；
  `/proc/<pid>/maps` 里出现该 so（dlopen 实证）
- 坑：C 的 `fprintf(stdout)` 有缓冲，必须 `fflush`（JVM 自身打印绕开 libc 缓冲，对比迷惑）

## 五、JVMTI 概念（计划内，native 之上的封装关系）

```
native agent（.so）= 直接和 JVM 打交道：dlopen + Agent_OnLoad/OnAttach + jvmtiEnv
Java agent（jar） = 由 libinstrument 代管：翻译成 premain/agentmain + Instrumentation
两条路底层是同一套 JVMTI，只是入口语言和"管家"不同
```
- Instrumentation 是 JVMTI 的 Java 封装（你在 Java 层调 inst，底层变 JVMTI 调用）
- capabilities（如 can_tag_objects）是"能力申请制"；事件（MethodEntry/Exit、Exception、GC…）是可选订阅
- 能力边界实例：Arthas（Java agent）做字节码增强够用，采样必须捆 async-profiler 的 .so（native）

## 六、一句话

**两者是"同一种物种（native agent）的两个角色"**：
libinstrument 是 JDK 提供的"Java agent 运行时管家"（免写 C），
自己的 so 是"下场干活的工人"（要 JVMTI 全能力时）。
