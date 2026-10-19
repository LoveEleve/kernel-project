# p9-domain8-补充 · ZGC/Shenandoah + JNI 深挖 #61-#70

> 域8 查漏补缺。实验目录：`experiments/jvm-diagnostics/12-p9-gc/`（ZGC）+ JNI 源码。

---

## #61 ZGC 分代（JDK21，实测）

### 实测（JDK21 `-XX:+ZGenerational`）

```
GC(0) Major Collection (Warmup)  56M(11%)->124M(24%)  0.033s
GC(1) Major Collection (Warmup)  124M(24%)->124M(24%) 0.020s
```

### 演进

```
JDK11-20：ZGC 非分代（单代，全并发标记+转移）
JDK21+：  ZGC 分代（-XX:+ZGenerational）
  → Young + Old 分代（类似 G1）
  → Young GC：只回收 Young（更频繁但停顿更短）
  → Old GC：并发标记+转移（类似 G1 并发周期）
  → 好处：Young 对象早死多，分代减少并发标记开销
```

### P9 追问链

- **Q：ZGC 分代了吗？** → JDK21+ 分代（`-XX:+ZGenerational`），之前非分代
- **Q：为什么分代？** → Young 对象早死多，分代减少并发标记开销（分代假说）
- **Q：分代 ZGC 停顿？** → 仍亚毫秒（Young GC 更短，Old GC 并发）

---

## #62 ZGC 指针颜色位布局（源码级，P9 加分）

```
ZGC 64 位指针（64-bit 染色指针）：
  位 0-41：对象实际地址（42 位，支持 4TB 堆）
  位 42：Finalizable（可终结）
  位 43：Remapped（已重映射）
  位 44：Marked0（标记位 0）
  位 45：Marked1（标记位 1）
  位 46-63：保留

颜色位作用：
  → 转移对象时不改地址，只翻转 Marked/Remapped 位
  → 读屏障检查颜色位：未 Remapped → 自愈（remap）
  → Multi-Mapping：同一物理内存多虚拟映射，颜色位在不同映射间区分
```

**P9 答法**："ZGC 染色指针：64 位指针高位做状态位（Finalizable/Remapped/Marked0/Marked1）；转移只翻转颜色位不改地址；读屏障检查颜色位自愈。堆上限 4TB（42 位地址）。"

---

## #63 ZGC vs Shenandoah 对比

| | ZGC | Shenandoah |
|---|---|---|
| 停顿 | 亚毫秒 | 亚毫秒 |
| 屏障 | 读屏障 | 读屏障 |
| 指针 | **染色指针**（64 位高位状态位） | **转发指针**（对象头存新地址） |
| 转移 | 全并发（颜色位翻转） | 全并发（转发指针） |
| 内存开销 | Multi-Mapping（虚拟地址 3 倍） | 无（普通指针） |
| 分代 | JDK21+ 分代 | JDK12+ 非分代（实验中） |
| JDK | 15+ 生产就绪 | 12+ |
| 选择 | 大堆+超低停顿 | 大堆+低停顿（无 Multi-Mapping 开销） |

**P9 答法**："ZGC 用染色指针（64 位高位状态位+Multi-Mapping）；Shenandoah 用转发指针（对象头存新地址）。都读屏障+全并发转移。ZGC 内存开销大（虚拟地址 3 倍）但更简单；Shenandoah 无此开销但对象头多 4-8 字节。"

---

## #64 ZGC 调优参数（了解级）

```
-XX:ZCollectionInterval=0        ← Major GC 间隔（秒，0=基于分配速率）
-XX:ZAllocationSpikeTolerance=1  ← 分配尖峰容忍度
-XX:ZProactive= true             ← 主动 GC（空闲时提前回收）
-XX:ZUncommitDelay=300           ← 空闲内存归还 OS 延迟（秒）
-XX:+ZGenerational              ← JDK21+ 分代 ZGC
```

---

## #65 Shenandoah 4 种模式（了解级）

```
Normal      → 正常并发回收
Traversal   → 并发标记+转移
Passive     → 不并发（退化为 STW）
Degenerated → 并发失败退化（类似 G1 Full GC）
```

---

## #66 JNI 方法注册（源码实证）

### 两种注册方式

```
1. 命名约定（自动查找）：
   Java_com_example_MyClass_nativeMethod
   → JNI 自动按命名规则找 so 里的符号

2. RegisterNatives（显式注册）★ 推荐：
   JNINativeMethod methods[] = {{"nativeMethod", "()V", (void*)my_func}};
   env->RegisterNatives(clazz, methods, 1);
   → 显式绑定 Java 方法名→C 函数指针
   → 好处：名字可混淆、启动快（无符号查找）、类型安全
```

### 源码（jni.cpp:3013 RegisterNatives）

```cpp
// RegisterNatives 注册方法绑定
```

**P9 答法**："JNI 方法注册两种：命名约定（Java_类名_方法名，自动查找）和 RegisterNatives（显式绑定方法名→函数指针）。生产推荐 RegisterNatives（可混淆、启动快、类型安全）。"

---

## #67 JNI 缓存（实用）

```
问题：每次调用 FindClass/GetMethodID 开销大（查符号表）
解法：缓存 jclass/jmethodID/jfieldID（Global Ref 保存）
  → 首次 FindClass/GetMethodID → 缓存
  → 后续直接用缓存 ID
  → 注意：类卸载时缓存失效（Classloader 场景）
```

**P9 答法**："JNI 性能优化：缓存 jclass/jmethodID/jfieldID（NewGlobalRef 保存），避免重复 FindClass/GetMethodID。类卸载时缓存失效（需重新查找）。"

---

## #68 JNI 线程安全（源码实证）

### JNIEnv 是线程本地的（jni.h:192/787）

```cpp
struct JNIEnv_;                    // 每线程一个 JNIEnv
typedef JNIEnv_ JNIEnv;
// JNIEnv 包含函数表指针（JNINativeInterface_）
```

### 规则

```
JNIEnv 是线程本地的（每线程独立）
  → 不能跨线程传 JNIEnv（其他线程要用自己线程的）
  → JavaVM 是全局的（可跨线程，AttachCurrentThread 获取 JNIEnv）
  → native 线程要调 Java → AttachCurrentThread → 拿 JNIEnv → 用完 DetachCurrentThread
```

**P9 答法**："JNIEnv 是线程本地的（每线程独立），不能跨线程传；JavaVM 全局可跨线程。native 线程调 Java 需 AttachCurrentThread 拿 JNIEnv，用完 Detach。"

---

## #69 JNI 性能（了解级）

```
JNI 调用开销：
  Java→native：~10-50ns（函数指针调用）
  native→Java：~50-200ns（JNIEnv 函数表+检查）
  GetPrimitiveArrayCritical：~10ns（无拷贝，但阻止 SafePoint）
优化：
  1. 批量操作（一次 JNI 调用处理多数据，减少跨界次数）
  2. 缓存 ID（#67）
  3. Critical（#69，注意 SafePoint 风险）
```

---

## #70 Panama/FFM 替代 JNI（趋势，P9 问趋势）

```
问题：JNI 复杂（引用管理/线程安全/编译 so）
解法演进：
  JDK9：  incubator Panama（初版）
  JDK11： incubator Panama（改进）
  JDK17： Foreign Function & Memory API（预览）★
  JDK22： FFM API 正式（JEP 454，替代 JNI）

FFM 优势：
  - 纯 Java API（无 C 代码/无 so）
  - 内存安全（MemorySegment 边界检查）
  - 零拷贝（MemorySegment 直接访问）
  - 无 JNIEnv/引用管理负担

FFM 局限：
  - 不支持回调 Java（JNI 可以）
  - 复杂 native 库仍需 JNI
```

**P9 答法**："Panama/FFM（JDK22 正式）是 JNI 的现代替代：纯 Java API、内存安全、零拷贝。但回调 Java/复杂 native 库仍需 JNI。P9 要知道趋势：新代码优先 FFM，遗留 JNI 逐步迁移。"

---

## #71 ZGC Allocation Stall（分配停顿，生产问题）

### 与 GC 停顿的区别

```
GC 停顿（Pause）：STW 阶段（Mark Start/End/Relocate Start），亚毫秒
Allocation Stall：分配速率 > 回收速率时，应用线程阻塞等回收完成
  → 不是 STW，是"分配线程等内存"
  → 日志：allocation stall / OutOfMemoryError
```

### 触发场景

```
1. 分配速率突增（如流量高峰）
2. 堆太小（回收跟不上）
3. 大对象频繁分配（humongous 类似问题）
```

### 调优

```
-XX:ZAllocationSpikeTolerance=1   ← 分配尖峰容忍度（调大更激进回收）
-XX:ZCollectionInterval=0         ← Major GC 间隔（0=基于分配速率）
-XX:ZProactive=true               ← 主动 GC（空闲时提前回收）
增大堆（根本解法）
```

**P9 答法**："ZGC Allocation Stall = 分配速率 > 回收速率时分配线程阻塞等内存（不是 STW GC 停顿）。调优：调大 ZAllocationSpikeTolerance/ZProactive/增大堆。生产监控 allocation stall 日志。"

---

## #73 JNI 异常处理（源码实证）

### API（jni.h:246-754）

```cpp
jint       ThrowNew(jclass, const char* msg);      // 抛新异常
jthrowable ExceptionOccurred();                    // 获取当前异常
void       ExceptionClear();                       // 清除异常
jboolean   ExceptionCheck();                       // 检查是否有异常（轻量）
```

### JNI 异常处理规则

```
native 方法抛 Java 异常后：
  1. JNIEnv 进入"异常待处理"状态
  2. 后续 JNI 调用（除 ExceptionXxx）结果未定义
  3. 必须 ExceptionCheck/ExceptionOccurred 检查
  4. 处理：ExceptionClear（清除）或直接返回（让 Java 层接）
```

### 标准模式

```c
JNIEXPORT void JNICALL my_native(JNIEnv* env, jobject obj) {
    // 调用可能抛异常的 JNI 方法
    jclass cls = (*env)->FindClass(env, "java/lang/Exception");
    if ((*env)->ExceptionCheck(env)) {   // ★ 检查
        (*env)->ExceptionDescribe(env);  // 打印（调试）
        (*env)->ExceptionClear(env);     // 清除
        return;                           // 或重新抛出
    }
    // 正常逻辑...
}
```

**P9 答法**："JNI 异常处理：ExceptionCheck/ExceptionOccurred 检查，ExceptionClear 清除。native 抛异常后后续 JNI 调用结果未定义，必须先检查异常。标准模式：调用后 ExceptionCheck→处理→clear/return。"

---

## 补充考点速查（#61-#70 + #71/#73）

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **ZGC 分代** | JDK21+ `-XX:+ZGenerational`（Young+Old，减少并发标记开销） | #61（实测） |
| ZGC 颜色位 | 64 位高位状态位（Finalizable/Remapped/Marked0/1），转移翻位不改地址 | #62（源码级） |
| **ZGC vs Shenandoah** | ZGC=染色指针+Multi-Mapping；Shenandoah=转发指针（无内存开销） | #63（对比表） |
| ZGC 调优 | ZCollectionInterval/ZAllocationSpikeTolerance/ZProactive/ZUncommitDelay | #64（叙事） |
| Shenandoah 模式 | Normal/Traversal/Passive/Degenerated | #65（叙事） |
| **JNI 方法注册** | 命名约定 vs RegisterNatives（推荐：显式注册） | #66（源码） |
| **JNI 缓存** | 缓存 jclass/jmethodID 避免重复查找；类卸载时失效 | #67（叙事） |
| **JNI 线程安全** | JNIEnv 线程本地（不能跨线程）；JavaVM 全局可跨线程 | #68（源码 jni.h） |
| JNI 性能 | Java→native ~10-50ns；批量+缓存+Critical 优化 | #69（叙事） |
| **Panama/FFM** | JDK22 正式，替代 JNI（纯 Java/内存安全/零拷贝）；回调仍需 JNI | #70（趋势） |
| **Allocation Stall** | 分配速率>回收速率时分配线程阻塞（非 STW）；调 ZAllocationSpikeTolerance | #71（叙事） |
| **JNI 异常处理** | ExceptionCheck/ExceptionOccurred 检查，ExceptionClear 清除；后续 JNI 调用结果未定义 | #73（源码 jni.h） |

## 相关文件

- ZGC 分代实测：JDK21 `-XX:+UseZGC -XX:+ZGenerational`
- JNI 源码：`openjdk11u/src/java.base/share/native/include/jni.h`（JNIEnv/RegisterNatives）
- FFM：JDK22+ `java.lang.foreign`（本环境 JDK21 预览版）
