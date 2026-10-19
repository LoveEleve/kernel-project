# p9-domain7-补充 · 解释器与启动流程深挖 #51-#60

> 域7 查漏补缺。实验目录：`experiments/jvm-diagnostics/15-p9-exec-engine/`

---

## #51 方法调用 5 种字节码（实测）

### javap 证据（InvokeExceptionDemo）

```
invokespecial   // Method Object."<init>"          ← 构造器/private/父类 super()
invokevirtual   // Method Animal.speak              ← 虚方法（运行时分派）
invokeinterface // InterfaceMethod Greeter.greet    ← 接口
invokestatic    // Method tryCatch                  ← 静态（编译期确定）
invokedynamic   // InvokeDynamic #0:run             ← lambda/字符串拼接（运行时生成）
```

### 5 种对比

| 字节码 | 场景 | 分派方式 | P9 深度 |
|---|---|---|---|
| invokestatic | 静态方法 | 编译期确定 | 无分派 |
| invokespecial | 构造器/private/super | 编译期确定 | 无分派 |
| invokevirtual | 实例虚方法 | **vtable 虚方法表** | #55 |
| invokeinterface | 接口方法 | **itable 接口表** | #55 |
| invokedynamic | lambda/动态语言 | **BootstrapMethod 运行时生成** | #60 |

### P9 追问链

- **Q：5 种 invoke 区别？** → static/special 编译期确定；virtual/interface 运行时分派；dynamic 运行时生成
- **Q：private 方法用哪个？** → invokespecial（编译期绑定，非虚分派）

---

## #52 异常处理字节码层（实测）

### javap 证据（tryCatch 的 exception table）

```
Exception table:
   from    to  target type
       0    19    29   Class IllegalArgumentException   ← try [0,19) 异常→29(catch)
       0    19    42   any                             ← finally 覆盖
      29    32    42   any                             ← catch 块也 finally
```

### 机制

```
try-catch 不是指令，是"异常表"（Exception Table）
  → 每个方法一个异常表：(from, to, target, type) 区间映射
  → athrow 抛出 → 查表：异常类型匹配且 pc 在 [from,to) → 跳 target
  → finally = 复制 finally 代码到每个出口（return/athrow 前）
```

### P9 追问链

- **Q：try-catch 字节码怎么实现？** → 异常表（区间映射），非指令；athrow 后查表跳转
- **Q：finally 怎么实现？** → javac 复制 finally 代码到所有出口（return/athrow/catch 前）
- **Q：异常表影响性能吗？** → 不影响正常路径（只在异常时查表）

---

## #53 JVM 启动后线程全景（实测）

### jcmd Thread.print 证据（新进程）

```
Java 线程（daemon）：
  main                     ← 主线程（执行 main）
  Reference Handler        ← 引用处理
  Finalizer                ← finalize 执行
  Signal Dispatcher        ← 信号分发（kill -3 等）
  C2 CompilerThread0       ← C2 JIT 编译
  C1 CompilerThread0/1     ← C1 JIT 编译
  Sweeper thread           ← Code Cache 清理
  Service Thread           ← 服务线程
  Common-Cleaner           ← Cleaner（JDK9+ 替代 finalize）
  Attach Listener          ← attach 命令（jstack/jcmd）

VM 线程（非 daemon，OS 线程）：
  VM Thread                ← VM 操作执行（GC/JIT 等）
  GC Thread#0              ← G1 GC 工作线程
  G1 Main Marker           ← G1 并发标记主线程
  G1 Conc#0                ← G1 并发标记工作线程
  G1 Refine#0              ← G1 并发精化（RSet 处理）
  G1 Young RemSet Sampling ← G1 RSet 采样
  VM Periodic Task Thread  ← 周期任务（JFR 等）
```

### 教学点

- JVM 启动即创建一批守护线程（JIT/GC/Signal/Attach 等）
- `VM Thread` 是单线程（执行所有 VM 操作，GC/JIT 请求都排队给它）
- G1 有多个专用线程（Marker/Conc/Refine/RemSet Sampling）

### P9 追问链

- **Q：JVM 启动了哪些线程？** → main+Reference+Finalizer+Signal+Compiler+C1/C2+Sweeper+Service+Attach + VM Thread+GC+G1 系列
- **Q：VM Thread 是什么？** → 单线程 VM 操作执行者（GC/JIT/Heap 操作都经它）
- **Q：C1/C2 CompilerThread 区别？** → C1=客户端编译（快），C2=服务端编译（慢但好）

---

## #54 Shutdown 流程（实测）

### 实测（ShutdownDemo）

```
main 即将返回...
Hook2 执行（逆序）
Hook1 执行（main 返回后/JVM 关闭前）
```

### 流程

```
main 返回
  → 执行 Shutdown Hook（注册的线程，逆序）
  → JVM_Halt（停止所有线程）
  → 进程退出
触发 Shutdown 的方式：
  1. main 返回（正常）
  2. System.exit(n)
  3. 最后一个非 daemon 线程结束
  4. SIGTERM/SIGINT（kill 默认信号）
```

### P9 追问链

- **Q：Shutdown Hook 什么时候执行？** → main 返回后/System.exit/非 daemon 结束/kill 信号
- **Q：Hook 顺序？** → 逆序（后注册先执行）
- **Q：Hook 里能做什么？** → 关资源/刷日志；不能调 System.exit（会卡死）

---

## #55 vtable/itable 实现（源码级，P9 加分）

### invokevirtual → vtable（虚方法表）

```
每个类有一个 vtable（虚方法表，数组）
  → 按"方法签名"预先排好（编译期计算）
  → invokevirtual：从对象头拿 Klass → Klass 里有 vtable → 按索引取方法
  → 子类覆盖 → vtable 对应槽位指向子类方法（多态）
  → private/static/final 不进 vtable（编译期绑定）
```

### invokeinterface → itable（接口表）

```
itable = 多个接口的 vtable 合并（更复杂）
  → 接口方法可能来自多个接口
  → 比 invokevirtual 慢（多一次 itable 查找）
```

### P9 答法

"invokevirtual 用 vtable（类的虚方法表，编译期排好，子类覆盖改槽位）；invokeinterface 用 itable（接口表，多接口合并）。private/static/final 不进表（编译期绑定）。"

---

## #56 字节码指令分类全景（了解级）

```
加载/存储：iload/aload/istore/astore（局部变量↔操作数栈）
运算：     iadd/isub/imul/idiv（栈上运算）
控制：     goto/if*/tableswitch/lookupswitch（跳转）
对象：     new/getfield/putfield/getstatic/putstatic
方法调用： invoke{static,special,virtual,interface,dynamic}
异常：     athrow（查异常表跳转）
其他：     arraylength/checkcast/instanceof/monitor（同步）
```

---

## #57 操作数栈类型系统（了解级）

```
每个槽位 32bit；long/double 占两格（64bit）
  → iload/fload/aload：32bit（int/float/reference）
  → lload/dload：64bit（long/double，占两槽）
类型转换：i2l/i2f/d2i 等（显式指令）
```

---

## #58 栈帧大小编译期确定（了解级）

```
javac 编译时计算：
  局部变量表大小 = 参数数 + 局部变量数（按最大活跃数）
  操作数栈深度 = 编译期模拟执行的最大栈深
  → 写入 class 文件 Code 属性（max_stack / max_locals）
  → JVM 创建栈帧时直接按此分配（无需运行时计算）
```

---

## #59 启动参数生效机制（了解级）

```
Launcher 解析 → JNI_CreateJavaVM(args) → Arguments::parse(args)
  → 逐个解析 -X/-XX/-D → 设置 Flag / Heap/ Metaspace 等
  → 部分参数在 Heap 分配前生效（-Xms/-Xmx）
  → 部分在子系统初始化时生效（GC 参数/JIT 参数）
```

---

## #60 invokedynamic 与 lambda（实测）

### javap 证据（makeLambda）

```
makeLambda:
  0: invokedynamic #8  // InvokeDynamic #0:run  ← 运行时生成 lambda
  5: areturn

lambda$makeLambda$0:            ← lambda 体编译成私有静态方法
  0: getstatic System.out
  3: ldc "lambda"
  5: invokevirtual println
  8: return
```

### 机制

```
lambda 编译：
  1. lambda 体 → 私有静态方法（lambda$xxx$0）
  2. 调用点 → invokedynamic + BootstrapMethod
  3. 首次调用 → BootstrapMethod 生成"工厂"（LambdaMetafactory）
  4. 工厂创建 lambda 实例（实现目标接口）
  5. 后续调用 → 直接用工厂（缓存）
```

### P9 追问链

- **Q：lambda 字节码怎么实现？** → invokedynamic + BootstrapMethod + 私有静态方法（lambda$xxx）
- **Q：为什么用 invokedynamic？** → 运行时决定调用目标（可生成/缓存最优实现）
- **Q：字符串拼接也用 invokedynamic？** → JDK9+ 是（makeConcatWithConstants，比 StringBuilder 快）

---

## 补充考点速查（#51-#60）

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **方法调用 5 种** | static/special 编译期；virtual/interface 运行时分派；dynamic 运行时生成 | #51（javap 5 种 invoke） |
| **异常字节码** | try-catch = 异常表（区间映射），非指令；finally 复制到出口 | #52（exception table） |
| **启动线程全景** | main+Reference+Finalizer+Signal+Compiler+C1/C2+Sweeper+Attach + VM Thread+GC+G1 系列 | #53（jcmd Thread.print） |
| **Shutdown 流程** | main 返回→Hook 逆序→JVM_Halt；触发：main/System.exit/非daemon/kill | #54（实测 Hook 逆序） |
| vtable/itable | invokevirtual=vtable（类方法表）；invokeinterface=itable（接口表） | #55（叙事） |
| 字节码分类 | 加载存储/运算/控制/对象/调用/异常/同步 7 类 | #56（叙事） |
| 操作数栈类型 | 32bit/槽；long/double 占两格 | #57（叙事） |
| 栈帧大小 | 编译期算好（max_stack/max_locals）写 class | #58（叙事） |
| 参数生效 | Launcher→JNI→Arguments::parse→Flag/Heap | #59（叙事） |
| **invokedynamic/lambda** | lambda 体=私有静态方法；调用点=invokedynamic+BootstrapMethod | #60（javap 实测） |

## 相关文件

- `15-p9-exec-engine/InvokeExceptionDemo.java`：方法调用/异常/lambda 字节码
- `15-p9-exec-engine/ShutdownDemo.java`：Shutdown Hook
- `15-p9-exec-engine/ThreadPanorama.java`：线程全景（长驻）
- 源码：`openjdk11u/src/hotspot/share/interpreter/`（解释器）
