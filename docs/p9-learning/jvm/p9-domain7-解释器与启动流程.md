# p9-domain7 · 解释器执行引擎 + JVM 启动流程（阶段1 基础闭环）

> 补 JVM 基础闭环：#2 解释器执行引擎 + #1 JVM 启动流程。
> 实验目录：`experiments/jvm-diagnostics/15-p9-exec-engine/`

---

# 第一部分：解释器执行引擎

## 实验 1：字节码反编译（javap -c）

### calc(a,b) = a + b*2

```
static int calc(int, int);
  0: iload_0      ← 局部变量表[0]（a）压操作数栈
  1: iload_1      ← 局部变量表[1]（b）压栈
  2: iconst_2     ← 常量 2 压栈
  3: imul         ← 弹 2 和 b，压 b*2
  4: iadd         ← 弹 b*2 和 a，压 a+b*2
  5: ireturn      ← 弹结果返回
```

### callChain（方法调用）

```
  0: iload_0
  1: iconst_3
  2: invokestatic #2   // Method calc:(II)I   ← 静态调用（编译期确定）
  5: istore_1
  6: iload_1
  7: iconst_1
  8: iadd
  9: ireturn
```

## 实验 2：手动栈执行追踪

以 calc(5,7) 为例的操作数栈变化：

```
步骤   指令      操作数栈           局部变量表
初始            []                 [5, 7]
0     iload_0   [5]                [5, 7]
1     iload_1   [5, 7]             [5, 7]
2     iconst_2  [5, 7, 2]          [5, 7]
3     imul      [5, 14]   ←7*2     [5, 7]
4     iadd      [19]     ←5+14     [5, 7]
5     ireturn   []        → 返回19
```

### 栈帧结构（Stack Frame）

```
栈帧（每个方法调用一个）
├── 局部变量表（Local Variables）：方法参数+局部变量，按索引访问
├── 操作数栈（Operand Stack）：字节码运算的中转栈（push/pop）
├── 动态链接（Dynamic Linking）：指向运行时常量池的方法引用
└── 返回地址（Return Address）：方法返回后继续执行的位置
```

### 教学点

- 字节码是**基于栈的指令集**（操作数先压栈再运算），非寄存器指令集
- 栈帧 = 局部变量表 + 操作数栈 + 动态链接 + 返回地址
- `invokestatic` = 编译期确定目标（无虚分派）

## 实验 3：HotSpot 模板解释器（源码实证）

### 源码（interpreter.hpp:120）

```cpp
typedef CC_INTERP_ONLY(CppInterpreter) NOT_CC_INTERP(TemplateInterpreter) Interpreter;
//                  ↑ Zero/CPP 解释器        ↑ HotSpot 默认（x86/ARM）
```

```cpp
// templateTable_x86.cpp:2426
__ dispatch_next(vtos, 0, true);   // 字节码分发
```

### 模板解释器机制

```
HotSpot 默认 = TemplateInterpreter（非逐条解释）
  → 启动时为每个字节码生成一段机器码模板
  → 执行时 dispatch_next：
      1. 读字节码流下一字节（opcode）
      2. 跳转 dispatch_table[opcode]（机器码模板地址）
      3. 执行该字节码机器码（压栈/运算/跳转）
      4. 跳回 1（继续下一字节码）

对比：
  BytecodeInterpreter（Zero 平台）：逐条解码执行（慢）
  TemplateInterpreter（HotSpot）：机器码模板跳转（快）
  JIT（C1/C2）：编译成原生代码（最快）
```

### P9 追问链

- **Q：HotSpot 解释器是逐条解释吗？** → 不是，是模板解释器（机器码模板+dispatch 跳转）
- **Q：栈帧包含什么？** → 局部变量表+操作数栈+动态链接+返回地址
- **Q：字节码为什么基于栈？** → 跨平台（不依赖寄存器）、编译简单；代价是指令数多
- **Q：invoke 五种？** → virtual/interface/special/static/dynamic（invokeDynamic 为 lambda/动态语言）

---

# 第二部分：JVM 启动流程

## 全链路（java 命令 → main 执行）

```
1. 用户输入：java -Xmx512m -cp out com.drill.exec.BytecodeDemo
2. OS 启动 java 可执行文件（Launcher）
   → java → libjli.so/jli（JVM Launcher）
3. Launcher 解析命令行参数（-X/-XX/-cp）
4. 创建 JVM：JNI_CreateJavaVM（加载 libjvm.so）
   → 初始化子系统：类加载器/解释器/JIT编译器/GC/线程
5. 加载主类：BootstrapLoader 加载 mainClass
   → 触发 <clinit>（静态初始化）
6. 调用 main 方法（解释器执行，热点后 JIT 编译）
7. main 返回 → JVM 关闭（Shutdown Hook 执行）
```

### 源码锚点（已验证）

```
Launcher：openjdk11u/src/java.base/share/native/libjli/java.c:242（JLI_Launch 入口）
         java.c:124 LoadMainClass（加载主类）
JVM 创建：openjdk11u/src/hotspot/share/prims/jni.cpp:4098（JNI_CreateJavaVM）
初始化：  openjdk11u/src/hotspot/share/runtime/thread.cpp:3702（Threads::create_vm）
```

### 关键机制

```
-Xms/-Xmx 解析：Launcher 传给 JVM → Arguments::parse → Heap 分配
类加载时机：Launcher 调 LoadMainClass → BootstrapLoader.loadClass
  → 触发 <clinit>（域2 实验4 的初始化时机）
main 线程：主线程 = "main" 线程（JavaThread），在其中调 main
```

### P9 追问链

- **Q：java 命令做了什么？** → Launcher 解析参数→JNI_CreateJavaVM 创建 JVM→加载主类→调 main
- **Q：JVM 怎么创建的？** → JNI_CreateJavaVM（加载 libjvm.so），初始化类加载器/解释器/GC 等
- **Q：main 线程是什么？** → 主线程（JavaThread "main"），Launcher 创建 JVM 后调 main
- **Q：-Xmx 何时生效？** → Launcher 解析→Arguments::parse→JVM 创建时分配堆

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| 字节码指令集 | 基于栈（压栈再运算），非寄存器 | 实验 1（javap -c） |
| 栈帧结构 | 局部变量表+操作数栈+动态链接+返回地址 | 实验 2（栈追踪） |
| 操作数栈 | calc(5,7) 手动执行：[5,7,2]→imul→[5,14]→iadd→[19] | 实验 2 |
| 模板解释器 | HotSpot 用机器码模板+dispatch_next（非逐条解释） | 实验 3（源码） |
| 解释器对比 | Bytecode(Zero 逐条) vs Template(HotSpot 模板) vs JIT | 实验 3 |
| invoke 五种 | virtual/interface/special/static/dynamic | 实验 1（invokestatic） |
| JVM 启动链路 | Launcher→JNI_CreateJavaVM→加载主类→调 main | 第二部分（叙事） |
| main 线程 | 主线程 JavaThread "main"，Launcher 创建 JVM 后调 | 第二部分 |

## 相关文件

- `15-p9-exec-engine/BytecodeDemo.java`：字节码+栈执行靶子
- 源码：`openjdk11u/src/hotspot/share/interpreter/{interpreter,templateTable}.*`
- 源码：`openjdk11u/src/java.base/share/native/libjli/java.c`（Launcher）
