# arthas-02 · math-game 实操记录

> 说明：本篇收录 Arthas track 全部动手输出（math-game 靶子，JDK11，arthas 4.3.5）。
> PID 均为示例（如 395259），复现时一律先 `jps -l` 复核。
> 批量模式：`java -jar arthas-boot.jar <pid> -c '命令'`；交互式直接进 shell。

## 一、attach（A1）

```bash
java -jar arthas-boot.jar 395259 -c 'jvm'
```
- 连上即装 agent：目标进程多出 arthas-* 线程（jstack 可见：
  `arthas-timer`、`arthas-NettyHttpTelnetBootstrap`、`arthas-shell-server`…）
- 端口 owner 是目标进程本体（`ss` 实证）：
  `[::ffff:127.0.0.1]:8563 … users:(("java",pid=395259…))`
  `[::ffff:127.0.0.1]:3658 … users:(("java",pid=395259…))`
- 第二次连显示 `already listen port 3658, skip attach`（防重复装载守卫）
- 多目标要换端口：`--telnet-port 3659 --http-port 8564`；
  不带端口连错目标会被明确拒绝（"port 3658 is used by process 395259…"）

## 二、jvm / thread / sc（B3/C1-C4）

- `jvm`：内存（堆 32M/250M）/ OS（Linux/16 核/load）/ 线程（COUNT/DAEMON/死锁数）/ fd，全景一页
- `thread -n 3`：按 %CPU 排序的 Top；列 ID/NAME/GROUP/STATE/%CPU/DELTA/TIME/DAEMON
- `sc -d demo.MathGame`：类元信息（code-source、isInterface 系列全 false、classloader 链）

## 三、dashboard（C1）

`dashboard -n 2 -i 3000`：线程表（%CPU 找热点）+ 内存（heap 32M/250M、eden/old/survivor、nonheap 52M）
+ GC（次数/耗时）+ Runtime。每块对应一批 MXBean（Thread/Memory/MemoryPool/GC/Runtime/OS），
即"JMX 指标的实时排版"；和 jstack/jstat 是同一批数据的不同呈现。

## 四、watch / trace / tt（D1/D2/D5）

```bash
watch demo.MathGame primeFactors '{params, returnObj}' -n 2 -x 2
```
```
Affect(class count: 1 , method count: 1) cost in 78 ms, listenerId: 1
method=...primeFactors location=AtExceptionExit
ts=...; [cost=1.16ms] result=@ArrayList[ @Object[[ @Integer[-58922], ], null, ]
```
- `Affect(...)` = 本次织入发生（listenerId 每次递增：watch=1 → trace=2 → tt=3，各自独立 listener）
- `AtExceptionExit` + returnObj=null：这次调用抛了 IllegalArgumentException（负数入参，和 math-game 日志的 illegalArgumentCount 对得上）

```bash
trace demo.MathGame primeFactors -n 3
```
调用树 + 耗时（0.09ms 正常 / 0.42ms 异常那次）+ 异常对象 + TCCL；watch 看单次输入输出，trace 看耗时。

```bash
tt -t demo.MathGame primeFactors -n 3   # 录
tt -l                                    # 查（跨会话还在：1000/1001/1002）
tt -i 1002 -x 2                          # 回放：PARAMETERS/THROW-EXCEPTION 完整堆栈（MathGame.java:46）
```
- watch=直播，tt=录播回放（偶发问题专用）

## 五、retransform 还原循环（E3）

- watch 挂着时 `jad`：方法体里出现 `SpyAPI.atEnter/atExit/atExceptionExit`（增强态铁证，形态与手写 TickWeaver 一致）
- kill watch 后 `jad`：0 处 Spy 痕迹（命令退出自动还原）
- `retransform -l`：watch/trace 的增强**不建 entry**（一直是空）；`retransform --classPattern` 只触发重跑也不建
- 建 entry 的唯一方式：`retransform <class文件>`（先 `dump -d` 导出）→ `-l` 显示 Id=1 → `retransform -d 1` 删除 → `-l` 空
- 结论：watch 式增强随命令生死；file 式增强靠 entry 手动管理；字节码层面都可逆

## 六、profiler（F1/F2）

```bash
profiler start / profiler status / profiler stop --format collapsed
profiler start --event alloc [--include 'demo/*']
```
- CPU 模式 22 秒：**空文件**——math-game 全程 sleep，采样只采 running 线程（idle 无 CPU 可采）
- alloc 模式：6 行；业务只有 1 行（MathGame.run → StringBuilder.append → Arrays.copyOf → byte[]）；
  其余 5 行全是 arthas 自身 telnet/netty I/O —— **观察者效应**：诊断工具自己的流量也会被采进去
- `--include` 过滤要写对；collapsed 格式：`栈;栈;…;类型_[次数]`，`_[i]` = 内联帧；火焰图就是按宽度（次数）画这些行
- 输出路径坑：`profiler output file: /data/workspace/arthas-output/*.txt` 是**目标视角**路径（容器场景要拷出来）
- 引擎：async-profiler（native agent，捆在 arthas-bin 里）；采样开销远低于 JVMTI MethodEntry/Exit 事件

## 七、mc + redefine 热替换（E：TickApp，另端口 3659/8564）

```bash
mc -d /tmp/opencode/mc-out /tmp/opencode/TickApp.java
# Memory compiler output: /tmp/opencode/mc-out/TickApp.class / Affect(row-cnt:1) cost in 2765 ms.
redefine /tmp/opencode/mc-out/TickApp.class
# redefine success, size: 1, classes: TickApp
```
- 日志：`[TickApp] tick 544/545` → `[HOTFIX] tick 546/547…`（**没重启就换了方法体**）
- `mc` = 目标 JVM 内调 javac 编译；`redefine` = 交完整新字节码替换（和 retransform 同一 VM_RedefineClasses，输入来源不同）
- 限制同 2.3（只改方法体；加方法照样被拒）

## 八、monitor / sm / classloader / logger / heapdump（快速过）

- `monitor -c 5 demo.MathGame primeFactors`：total=5 success=1 fail=4 avg=21ms fail-rate=80%（和异常输出对得上）；
  注意输出是 `\r` 刷新的，转存后 `tr '\r' '\n'` 还原；和 watch/trace 的关系：单次→耗时→聚合
- `sm demo.MathGame`：5 个方法 + 描述符（sm 看方法，sc 看类；都是"查已加载"）
- `classloader`：ArthasClassloader 3723 个类（隔离活证据）、Bootstrap 3492、Platform 45、App 4、
  DelegatingClassLoader ×20（反射 inflation 相关）
- `logger`：空（math-game 无日志框架）；有 logback/log4j2 的应用可查/改级别
- `heapdump --live /tmp/opencode/arthas-heap.hprof`：38M；和 `jmap -dump:live` 同一条 HeapDumper 路；路径是目标视角

## 九、stop 收尾

```
[arthas@694388]$ stop | plaintext
Resetting all enhanced classes ...
Affect(class count: 0 , method count: 0)
Arthas Server is going to shutdown...
```
- 验证：3659/8564 端口释放、arthas 线程数 0、应用继续跑
- **关键**：`redefine` 进去的 HOTFIX 留下来了（stop 还原不了 redefine——retransform 可逆，redefine 不可逆）
- agent 类本身不可卸载（第 1 组结论）；stop 卸的是"服务 + 临时增强"

## 十、常用命令速查

```
dashboard [-n 次数] [-i 间隔ms]     实时仪表盘
thread [-n Top] [-b]                热点线程 / 死锁
jvm / sysprop / sysenv / vmoption   配置档案
sc -d <类> / sm <类> / jad <类>      类信息 / 方法 / 反编译
watch <类> <方法> '{params,returnObj}' [-n N] [-x 2] [-c 条件]
trace <类> <方法> [-n N]            耗时路径
tt -t <类> <方法> -n N / tt -l / tt -i <id>   录制回放
monitor -c <秒> <类> <方法>          调用统计
retransform -l / -d <id> / --classPattern / <class文件>   增强管理
mc -d <dir> <源码> + redefine <class文件>    热替换
profiler start [--event cpu|alloc|lock] / status / stop --format collapsed|html
heapdump [--live] <file>            堆转储
classloader / logger                类加载器 / 日志级别
stop                                卸 agent（收尾动作）
```

## 十一、stop 收尾（math-game，395259）

```
[arthas@395259]$ stop | plaintext
Resetting all enhanced classes ...
Affect(class count: 1 , method count: 0) cost in 21441 ms, listenerId: 0
Arthas Server is going to shutdown...
```

- 与 TickApp 那次的异同：同是"关 server + 清增强 + 放端口"；异是这里有 1 个类要还原，
  花了 **21 秒**（TickApp 那次 0 个，秒停）——还原因类而异，大应用上 stop 要有耐心
- **stop 是异步的**："going to shutdown" 返回时还没完事；+3s 查端口/线程还在，
  +12s 后复查三项全过：3658/8563 端口释放、arthas 线程 0、应用继续跑（math-game 日志正常输出）
- 测量乌龙：`jstack | grep -c arthas` 数出 287——数的是含 arthas 的**栈帧行**
  （`at com.taobao.arthas...`），不是线程数；数线程用 `grep -c '^"arthas'`
- 结论：stop 卸的是"服务 + 临时增强"；redefine 进去的字节码、agent 类本身留存（第 1 组结论）

## 十二、G1-G5 生产实践小结

G1 安全（三层边界）：
- JVM 层：attach 同用户（不同用户连 socket 直接拒绝）
- 绑定层：默认 127.0.0.1（实测 ss），外网够不着
- 应用层：本地免鉴权（默认）；远程开 username/password 或 tunnel-server；
  `--disabled-commands` 可禁高危命令（stop/redefine）

G2 开销（实测数字）：
- 织入瞬间：Affect cost（mc 2765ms；watch/trace 几十 ms）+ STW 一次
- 常开增强才贵：HotLoop 插 nanoTime → 16M→8.2M 腰斩
- profiler 采样开销低；dashboard 定时采样可调 -i
- 纪律：高频方法先加条件/-n；trace 慎用通配符；profiler 采完就停（status 确认）

G3 会话：
- sessionTimeout（默认 3h 量级）；-c 批量适合脚本；交互式 Ctrl+C/Q 取消
- 多客户端可同时连（各回各的输出；增强是全局的）

G4 清理（全验证过）：
- watch 退出自动还原（jad 验证）；retransform -d 手动删；stop 关 server+还原+放端口
- redefine 不可逆（HOTFIX 留存）；agent 类不可卸载

G5 工具对照口诀：
- 要脚本化/自动化/取全量 → JDK 工具（jstack/jmap/jstat）
- 要交互式/方法级/不重启 → Arthas
- 底层很多同一条路（HeapDumper、MXBean、threaddump），选哪个看"人还是脚本"
