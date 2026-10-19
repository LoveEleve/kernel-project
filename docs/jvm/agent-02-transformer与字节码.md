# agent-02 · transformer 与字节码

## 一、addTransformer 是什么

注册的是**一个实现 ClassFileTransformer 接口的回调对象**，不是文件配置。
官方定义："An agent registers an implementation of this interface so that the
transform method is invoked when classes are loaded, redefined, or retransformed."
→ 语义 = **把对象登记进"类定义时要通知的名单"**。

为什么必须"注册"：类何时加载由 JVM 决定、定义后不可变 →
你唯一能站的位置是"类定义那一刻" → 登记回调让 JVM 叫你。
**retransform 不是独立机制，是"让已加载类重走这条管线"**（re-让管线再跑一遍）。

## 二、完整调用链（源码验证）

类加载：HotSpot `KlassFactory::create_from_stream`（klassFactory.cpp:55，
恰好也是 `defined_classes` 计数的位置）→ `post_class_file_load_hook`
→ libinstrument native 回调 `eventHandlerClassFileLoadHook`（InvocationAdapter.c:626）
→ `transformClassFile`（JPLISAgent.c:798）→ JNI 调 Java 侧
`InstrumentationImpl.transform(...)`（540+）→ 按 isRetransformer 选 `TransformerManager`
→ 遍历 transformer 链表 → 返回字节码给 VM → VM 用它定义类。

实现细节（源码钉死）：
- 每个 agent 注册 normal/retransform **两个 JVMTI env**（`mIsRetransformer` 标记，
  JPLISAgent.c:261/1050）；Hook 回调带标记，Java 侧据此选 `mTransformerManager` /
  `mRetransfomableTransformerManager`
- 所以：`addTransformer(t)` = canRetransform false（只对新加载）；
  `addTransformer(t, true)` = 参与 retransform；内部是两个独立链表
- `removeTransformer` 只删"本 Instrumentation 实例"列表里的（跨实例无效 → 撤销坑）
- 链式：前一个的返回值是后一个的输入

## 三、transform() 五参数（javadoc 原文对照）

1. loader：defining loader；**null = bootstrap**
2. className：**内部格式**（`java/util/UUID` 斜杠）
3. classBeingRedefined：**null = 新加载**；非 null = 对已存在类的操作（redefine/retransform 参数上不可区分）
4. protectionDomain：保护域，一般不用
5. classfileBuffer：原始字节码，**must not be modified**（要改拷贝）
- 返回：null = 不改；well-formed byte[] = 替换；抛异常不中断类加载
- 调用线程：谁触发类加载就在谁身上（agentmain 里 Class.forName → Attach Listener；业务加载 → 业务线程）

## 四、字节码基础（看懂 javap 就够）

- class 文件：CAFEBABE + 版本（55=Java 11）+ 常量池 + 类信息 + 字段表 + 方法表 + 属性表
- 方法 = 名字索引 + 描述符索引 + **Code 属性**
  （max_stack + max_locals + code_length + 指令字节 + 异常表 + 子属性）
- 指令 = 1 字节操作码 + 定长操作数；长度不同 → "偏移量"（tick 的 0/3/4/9/12 就是这么数出来的）
- 描述符：I=int V=void J=long；`L...;` 对象；`[I` 数组；`(I)V` 方法
- 执行模型：**操作数栈**（运算，max_stack）+ **局部变量表**（变量，max_locals）；
  static 方法 0 号槽=第 1 参数，实例方法 0 号槽=this；long/double 占 2 槽；
  调用约定栈形如 [receiver, 参数…]
- 指令分类：ldc/iload/getstatic | istore/putstatic | iadd | goto/if | invoke*5 | return/i*return | new/checkcast
- LineNumberTable：偏移↔源码行（异常栈行号来源）；StackMapTable：校验用
- 需要记住的最小清单：文件六件套、属性套娃、Code 布局、指令=操作码+定长操作数、两工作台、描述符表
- 工具：`javap -c -p`（反汇编，无 hex）、`xxd`（hex，无结构）、IDEA Show Bytecode/jclasslib；
  自写 `04-bytecode/classdump.py`（原始字节 + 反汇编并排）

## 五、ASM 模型（事件 = 解析时的回调通知）

- 官方定义（asm.ow2.io）："all purpose Java bytecode manipulation and analysis framework…
  small and fast… suited for use in dynamic systems"
- 三件套 + 事件流：ClassReader（读，产生事件）→ Visitor（改，透传/修改/丢弃/补充）
  → ClassWriter（写，收集事件生成字节）
- 事件清单：类级（visit/visitSource/visitField/visitMethod/visitInnerClass/visitEnd）；
  方法体级（visitCode/visitLabel/visitLineNumber/visitXxxInsn/visitMaxs/visitEnd 等）
- **Label** = 偏移量占位符（插字节后偏移会变，ClassWriter 写回时才计算并同步更新跳转/行号/异常表）
- 事件流 vs 树（Core vs Tree API）≈ SAX vs DOM
- 每个 ASM 方法的主题都是计算密集型，无需单独测试类
- ASM 与格式的对应：visitMethod↔方法表项、visitCode↔Code 开始、visitXxxInsn↔指令、
  visitLdcInsn↔常量、COMPUTE_MAXS↔重算 stack/locals、COMPUTE_FRAMES↔重算 StackMapTable
- 学习范围：ASM 主学（底层）；ByteBuddy 对比学（封装）；Javassist 知道即可
- 生态：OpenJDK lambda、Groovy/Kotlin 编译器、JaCoCo、ByteBuddy(Mockito)、Gradle、Arthas

## 六、实验结论（TickApp 靶子，Tick 方法）

```java
// 织入：tick 入口插  System.out.println("[Weaver] 织入的代码: tick 被调用了")
// 实际是三条指令：GETSTATIC out / LDC "..." / INVOKEVIRTUAL println(String)V
```
- 织入生效：后续调用都先打织入行；之前已完成的调用不受影响
  （RandomLoop 一类"永不退出的 main 循环"天生看不到效果——坑）
- 参数打印：`visitVarInsn(ILOAD, 0)` 读 0 号槽（watch 雏形；实例方法参数从 1 号起）
- 撤销：同一 Instrumentation 上 removeTransformer + 再 retransform → 回原版
  （用错实例就删不掉、旧回调还会重新织入）
- 2.3 边界实验：在 visitEnd 里加新方法 → `UnsupportedOperationException:
  class redefinition failed: attempted to add a method`（失败原子，类不变）
- 边界全表（JavaExceptions.c）：add method / schema / hierarchy / delete method /
  class modifiers / NestHost-Members / method modifiers —— **结构冻结，方法体可换**
- 共同点：都会 STW（VM_RedefineClasses）、只影响后续调用
- `isModifiableClass` 先检查；JIT 已编译旧代码会被去优化后按新版重编

## 七、redefine vs retransform（最终版）

| | redefineClasses | retransformClasses |
|---|---|---|
| 新字节码谁提供 | 你（ClassDefinition 里带 byte[]） | JVM 重跑 transformer 管线产出 |
| transformer 跑吗 | 跑（输入=你提交的字节码） | 跑（输入=初始字节码+incapable 结果复用） |
| 哪些 transformer | 两组都跑 | 只有 capable 组 |
| 典型用途 | 热修复（手里有完整新类；Arthas mc+redefine） | 增强（不知道/不关心原字节码；watch/trace/APM） |
| 撤销 | 自己再交一份原字节码 | 移除 transformer 再跑一次退回原版 |
- 底层同一台机器：`VM_RedefineClasses`（VM op，默认 safepoint），jvmtiEnv.cpp:448-460
- 记忆：redefine="我带全文来换"，retransform="让管线再跑一遍"

## 八、实操坑（已踩）

- 匿名内部类要一起打进 jar（漏 `$1.class` → 102 错误）
- 类不可替换：改代码要换类名/换进程/或 retransform
- JDK 版本：class 65 装不进 55 目标（BUILD 用显式 JDK11 + `--release 11`）
- MANIFEST 三件套缺一不可：`Can-Retransform-Classes` + `addTransformer(t,true)` + `retransformClasses`
- `classfileBuffer` 只读；链式 composition（前一个输出=后一个输入）
