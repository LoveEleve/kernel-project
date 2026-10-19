# p9-domain8 · ZGC/Shenandoah + JNI（阶段2 深度扩展）

> 补 JVM 深度扩展：#3 ZGC/Shenandoah 深入 + #4 JNI 深入。
> 实验目录：`experiments/jvm-diagnostics/12-p9-gc/`（ZGC）+ `15-p9-exec-engine/`（JNI 源码）

---

# 第一部分：ZGC/Shenandoah（低延迟 GC）

## 与 G1 的本质区别

| | G1 | ZGC | Shenandoah |
|---|---|---|---|
| 停顿 | 毫秒级 | **亚毫秒** | **亚毫秒** |
| 屏障 | SATB 写屏障 | **Load Barrier（读屏障）** | **Load Barrier（读屏障）** |
| 指针 | 普通指针 | **Colored Pointer（染色指针）** | **Forwarding Pointer（转发指针）** |
| 回收 | 部分（Mixed） | **全并发转移** | **全并发转移** |
| JDK | 9+ 默认 | 15+ 生产就绪（JDK11 实验性） | 12+ |

## ZGC 实测（JDK21 + GcDemo3）

### 停顿证据（亚毫秒实锤）

```
GC(0) Pause Mark Start     0.033ms   ← 亚毫秒！
GC(0) Pause Mark End       0.025ms
GC(0) Pause Relocate Start 0.012ms
GC(1) Pause Mark Start     0.028ms
GC(2) Pause Mark Start     0.030ms
```

**对比 G1**：G1 Pause Young 3-19ms；ZGC 全程 **0.01-0.03ms**（快 100-1000 倍）。

### ZGC 三阶段（每次 GC）

```
Pause Mark Start     → STW（极短）：开始标记
Concurrent Mark      → 并发：三色标记
Pause Mark End       → STW（极短）：结束标记
Concurrent Process   → 并发：处理弱引用/引用
Pause Relocate Start → STW（极短）：开始转移
Concurrent Relocate  → 并发：转移对象（染色指针改指针）
```

## ZGC 核心机制（P9 深度）

### Colored Pointer（染色指针）

```
ZGC 指针 = 64 位，高位用作"颜色标记位"
  → Finalizable/Remapped/Marked0/Marked1 等状态位
  → 对象转移时只改"颜色位"，不改实际地址（Multi-Mapping）
  → 读屏障检查颜色位 → 不一致则"自愈"（remap）
```

### Load Barrier（读屏障）

```
每次从堆读引用时：
  1. 检查指针颜色位
  2. 若"未 remap"（对象已转移但指针未更新）→ 修正指针（自愈）
  3. 返回正确指针
→ 写屏障几乎无（只在标记时记 SATB），读屏障负责"自愈"
```

### 全并发转移

```
G1：Young GC STW 复制存活对象；Mixed GC STW 复制部分 Old
ZGC：转移全程并发（读屏障保证一致性），STW 只有 3 个极短 Pause
```

### P9 追问链

- **Q：ZGC 怎么做到亚毫秒？** → 转移全程并发（读屏障自愈），STW 只有 3 个极短 Pause（<0.1ms）
- **Q：染色指针原理？** → 64 位指针高位做状态位；转移只改颜色位不改地址
- **Q：读屏障干什么？** → 检查颜色位，对象已转移则自愈（remap 指针）
- **Q：ZGC 内存开销？** → Multi-Mapping（同一物理内存多虚拟映射），虚拟地址消耗 3 倍

## Shenandoah（了解级）

```
与 ZGC 类似（读屏障+并发转移），但用 Forwarding Pointer（转发指针）
  → 对象头部存"转发指针"指向新地址
  → 读屏障检查转发指针 → 已转移则跟过去
  → JDK12+；JDK11 需 -XX:+UnlockExperimentalVMOptions
本环境 JDK11 不可用（Option not supported）；JDK21 可用
```

---

# 第二部分：JNI 深入

## JNI 引用类型（源码实证）

```cpp
// jni.h:67-95
class _jobject {};
class _jclass : public _jobject {};      // 类引用
class _jstring : public _jobject {};     // 字符串引用
class _jarray : public _jobject {};      // 数组引用
typedef _jobject *jobject;
typedef _jclass *jclass;
typedef _jstring *jstring;
```

## JNI 三种引用

| 引用类型 | 生命周期 | 用途 | 创建方式 |
|---|---|---|---|
| **Local Ref** | 当前 native 方法返回前 | 方法内临时引用 | 自动（FindClass/NewObject 等） |
| **Global Ref** | 手动 DeleteGlobalRef 前 | 跨方法保存引用 | NewGlobalRef |
| **Weak Global Ref** | GC 可回收 | 缓存（不阻止 GC） | NewWeakGlobalRef |

### P9 追问链

- **Q：Local Ref 什么时候释放？** → native 方法返回时自动释放（DeleteLocalRef 可提前）
- **Q：Global Ref 忘了会怎样？** → 内存泄漏（永不释放）
- **Q：Weak Global Ref 用途？** → 缓存对象（GC 可回收，GetWeakGlobalRef 后检查是否 null）

## JNI Critical（源码实证 + 域6 盲区衔接）

```cpp
// jni.h:739-744
void* GetPrimitiveArrayCritical(jarray array, jboolean* isCopy);
void  ReleasePrimitiveArrayCritical(jarray array, void* carray, jint mode);
const jchar* GetStringCritical(jstring string, jboolean* isCopy);
```

### Critical 区域（域6 盲区 1 的机制）

```
GetPrimitiveArrayCritical → 进入"临界区"
  → 该线程无法到达 SafePoint（阻止 GC）
  → 直接返回数组指针（无拷贝，最快访问）
  → ReleasePrimitiveArrayCritical → 退出临界区

风险：Critical 区域内不能做耗时操作/调其他 Java 方法
  → 否则 SafePoint 卡死（域6 盲区 1：整个 JVM 冻结）
```

### P9 追问链

- **Q：GetPrimitiveArrayCritical 干什么？** → 直接返回数组指针（无拷贝），但进入临界区（阻止 GC）
- **Q：Critical 区域限制？** → 不能调其他 Java 方法/不能做耗时操作（SafePoint 卡死风险）
- **Q：和域6 SafePoint 卡死什么关系？** → Critical 区域线程无法到达 SafePoint → 所有 SafePoint 操作（GC/jstack）卡住

## JNI 方法调用（5 种对应）

```cpp
// JNI 中对应字节码 5 种 invoke
CallVoidMethod(...)       // invokevirtual（虚方法）
CallStaticVoidMethod(...) // invokestatic
CallNonvirtualVoidMethod(...) // invokespecial（super/private）
// 接口调用：通过对象引用（实际还是 invokevirtual）
// invokedynamic：JNI 不直接支持（需 BootstrapMethod）
```

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **ZGC 停顿** | 亚毫秒（实测 0.01-0.03ms），转移全程并发 | ZGC 实测（JDK21） |
| **ZGC 染色指针** | 64 位指针高位做状态位；转移只改颜色不改地址 | 叙事 |
| **ZGC 读屏障** | 检查颜色位，对象已转移则自愈（remap） | 叙事 |
| **ZGC vs G1** | G1=写屏障+STW 复制；ZGC=读屏障+全并发转移 | 对比表 |
| Shenandoah | 读屏障+转发指针；JDK12+；JDK11 实验性 | 叙事（本环境不可用） |
| **JNI 引用类型** | Local（方法内）/Global（手动删）/Weak Global（GC 可回收） | jni.h 源码 |
| **JNI Critical** | 直接返回数组指针（无拷贝），但阻止 SafePoint（域6 盲区1） | jni.h:739 源码 |
| JNI 方法调用 | CallVoidMethod 等对应 5 种 invoke | 叙事 |

## 相关文件

- ZGC 实测日志：`/data/workspace/arthas-output/zgc.log`
- JNI 源码：`openjdk11u/src/java.base/share/native/include/jni.h`
- ZGC 靶子：`12-p9-gc/GcDemo3.java`（JDK21 + UseZGC）
