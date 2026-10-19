# arthas-03 · 综合排障实战（Drill）

> 场景：一个"应用变慢"的进程，从零走完整条诊断链。
> 靶子：`experiments/jvm-diagnostics/08-drill/IncidentApp.java`（package com.drill）
> 结局比剧本更真实：两次 OOM、一次白清、一次失败修复、一次 OOME dump 兜底。

## 一、靶子设计（三个真实问题）

```java
public static volatile boolean RUN = true;                    // 修复开关（ognl 用）
public static final List<byte[]> LEAK = new ArrayList<>();     // 问题1：慢泄漏（静态持有只增）
// hot-burner 线程：忙 100ms 睡 100ms 循环烧 CPU             // 问题2：CPU 热点
// work 每 7 次抛 IllegalArgumentException、每 20 次 sleep 400ms  // 问题3：偶发异常+偶发慢
```
启动：`java -Xmx512m -cp out com.drill.IncidentApp`（第二次加 `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/data/workspace/arthas-output/drill-oom`）

**踩坑（Step 0 就学了）**：初版异常没 catch → 把 main 打死、应用秒退 → 修复后异常在 step 内捕获计数但方法内仍抛出（保证 trace/watch 能抓）。

## 二、Step 1：jstat / jcmd —— 定方向（内存 or CPU）

```
启动 4 秒后：YGC=0 FGC=0 GCC=No GC   ← 看起来"很健康"
             E(Eden) 69%→77%→80%→84% ← 但 Eden 匀速爬升 = 分配速率高
30 秒后：     O=99.09%  YGC=16 CGC=12
jcmd GC.heap_info: total 256000K → 303104K（G1 持续扩容）
```
**教学点：别看单点看趋势**。4 秒时的"无 GC"会骗人；30 秒后 Old 满 + 堆扩容 → 定方向【内存泄漏】。

## 三、Step 2：dashboard / thread —— 锁定 CPU

```
dashboard: hot-burner TIME=0:32.4（累计 32 秒 CPU）
           %CPU=0.0 ← 陷阱：瞬时值！采样点它正好在 sleep(100) 的睡里
thread -n 3: "hot-burner" cpuUsage=16.23% time=32761ms RUNNABLE
             at com.drill.IncidentApp.lambda$main$0(IncidentApp.java:24)
```
**教学点**：周期性热点要看 TIME/cpuUsage，别被瞬时 %CPU 骗；thread 直接给源码行（实时栈，比采样准）。

## 四、Step 3：profiler —— 交叉验证 + 意外收获

collapsed Top（10 秒采样）：
```
134  Thread.run → IncidentApp$$Lambda$3.run → lambda$main$0    ← ★ burner 占 134/157
 17  GangWorker → G1FullGCCompactTask → memcpy                 ← ★ Full GC 的压缩在烧 CPU
  3  GangWorker → G1FullGCMarkTask
  1  VMThread → VM_G1CollectForAllocation → do_collection_pause_at_safepoint
  1  arthas netty 栈（观察者效应，诊断工具自身流量）
```
**教学点**：① 交叉验证（thread 与 profiler 互证）；② 火焰图数据里藏了第二个问题（FullGC 频繁=泄漏的第二张嘴）；③ 看图先排除工具/框架自身栈。

## 五、Step 4：trace / watch —— 抓偶发异常与慢方法

```
trace step -n 3 → 12.8ms / 0.014ms / 0.021ms（普通 vs 慢）
watch riskyCalc 默认观察点 -n 3：
  location=AtExit          正常那次（入参 2499）
  location=AtExceptionExit 异常那次：入参 2506 + 完整堆栈（IncidentApp.java:65）
```
**教学点**：偶发异常不用 `-e`，默认观察点就带异常出口（math-game 那次同款）；输出带入参+堆栈=直接可修。

## 六、Step 5：修复三幕（本 drill 最有价值的部分）

**幕一 clear=治标**：`ognl '@com.drill.IncidentApp@LEAK.clear()'` → 1711→27 → 但 feeder 不停 → **3 分钟填回 7541** → 白清
**幕二 断源失败**：
- `@...@RUN=false` → `InappropriateExpressionException`（Arthas 不支持该静态赋值写法）
- 且**命令含 `=` 时 `-c` 传参会被截** → 改用 `-f` 文件才送到服务端（`echo "ognl ..." > cmd.txt; arthas-boot <pid> -f cmd.txt`）
- 反射 `getField("RUN").set(null,false)` 同样未生效
**幕三 OOM 二次发生 + dump 兜底**：
```
java.lang.OutOfMemoryError（main 线程 UncaughtExceptionHandler）
-drill-oom 自动 dump 生效 → 515,481,701 字节（≈512M 满）
```

## 七、现场分析（hprof 解析器打开 515MB dump）

```
顶层: UTF8 113636 / LOAD_CLASS 6293 / HEAP_DUMP_SEGMENT 1
子记录: INSTANCE 97378 / PRIM_ARRAY 43327 / OBJ_ARRAY 11280 / CLASS 5785
roots: STICKY_CLASS 2822 / JAVA_FRAME 191 / JNI_GLOBAL 66 / THREAD_OBJ 22
直方图: [B 39702 个 / 496,683,653 字节（473MB，占堆 97%）  ← ★ 铁证
        [Ljava/lang/Object; 5267 个（ArrayList 内部数组）
对象总数 151985，总 481MB
```
根因链：`byte[] 473MB` ↔ 64KB 泄漏数组 ↔ GC root = `com.drill.IncidentApp` 类静态字段 `LEAK`
（MAT 中 Path to GC Roots → Class static field → ArrayList → byte[]；解析器里对应 CLASS_DUMP 的静态值 + OBJ_ARRAY 引用链）

## 八、完整决策树（这条轨道工具的实战串联）

```
症状"应用变慢"
 1. jstat -gcutil/-gccause   → Eden 爬/O99/堆扩容/FullGC 频 → 【内存方向】
 2. jcmd GC.heap_info        → 堆规模与 G1 状态
 3. dashboard/thread -n 3    → CPU Top(hot-burner time=32s) + 源码行(:24) → 【CPU 方向】
 4. profiler collapsed       → 交叉验证（134/157 样本）+ G1FullGC 栈（第二问题暴露）
 5. trace step + watch       → 偶发异常(入参+行号65)、慢方法(12.8ms)
 6. jmap -histo(:live)       → byte[] 大户；:live 对照分活/死
 7. 根因                     → ognl 读静态 LEAK（1711→7541 持续涨）
 8. 修复                     → clear=治标白清；真修复=断源(改代码/热修复)
 9. 兜底                     → OOME 自动 dump → 解析器 → [B] 97% 铁证
```

## 九、教训清单（新增）

1. **修复必须断源**：不停泄漏源的清理是安慰剂
2. **Arthas ognl 写静态字段**：`@类@字段=值` 被 OGNL 拒；命令含 `=` 用 `-f` 文件传（`-c` 有截断风险）
3. **OOME dump 要提前配**：故障时再配已晚
4. **/tmp 产物会被夜间清理**：持久产物放 /data/workspace/...
5. **靶子代码先自测**：异常没 catch 打死 main（Step 0 的教训）

## 十、可复制命令

```bash
# 靶子
cd /data/workspace/source-code/experiments/jvm-diagnostics/08-drill
javac -d out IncidentApp.java
setsid nohup java -Xmx512m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/data/workspace/arthas-output/drill-oom \
  -cp out com.drill.IncidentApp > /data/workspace/arthas-output/drill.log 2>&1 &
PID=$(jps -l | grep IncidentApp | awk '{print $1}')

# 观察链
jstat -gcutil $PID 1000 4; jstat -gccause $PID; jcmd $PID GC.heap_info
java -jar arthas-boot.jar $PID -c 'dashboard -n 1'
java -jar arthas-boot.jar $PID -c 'thread -n 3'
java -jar arthas-boot.jar $PID -c 'trace com.drill.IncidentApp step -n 3'
java -jar arthas-boot.jar $PID -c "watch com.drill.IncidentApp riskyCalc '{params,throwExp}' -n 3 -x 2"
jmap -histo $PID | head; jmap -histo:live $PID | head
echo "ognl '@com.drill.IncidentApp@LEAK.size()'" > /tmp/cmd.txt
java -jar arthas-boot.jar $PID -f /tmp/cmd.txt

# 现场解析
python3 /data/workspace/source-code/experiments/jvm-diagnostics/02-commands/hprof_summary.py /data/workspace/arthas-output/drill-oom
```

产物：`08-drill/IncidentApp.java`、`/data/workspace/arthas-output/drill-oom`（515MB 现场，不分析了可删）、`drill.log/drill2.log`。
