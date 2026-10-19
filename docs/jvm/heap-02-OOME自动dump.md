# heap-02 · OOME 自动 dump 生产配置

## 一、它是什么

**JVM 因 OOM 崩溃的那一刻，自动把堆现场写成 hprof**——否则进程一挂，证据全丢。

实测（`-Xmx32m` 的 OomDemo，三种跑法）：

| 跑法 | 结果 |
|---|---|
| 不带参数 | `OutOfMemoryError: Java heap space`，无文件 |
| `+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/.../oom` | `Dumping heap to .../java_pid404877.hprof ... Heap dump file created [17884996 bytes in 0.343 secs]`（18MB，0.34s） |
| 再 + `+ExitOnOutOfMemoryError` | 同样留 dump，且进程**退出码=3** |

## 二、配置三件套

```bash
-XX:+HeapDumpOnOutOfMemoryError          # 开关（默认关）
-XX:HeapDumpPath=/data/dumps             # 目录（或具体文件名）
-XX:+ExitOnOutOfMemoryError              # 常用搭配：dump 后退出（码 3），让编排重启
```

源码（`debug.cpp:318-345`）：
```cpp
void report_java_out_of_memory(const char* message) {
  static int out_of_memory_reported = 0;   // 只处理第一次（atomic 守卫）
  if (Atomic::cmpxchg(1, &out_of_memory_reported, 0) == 0) {
    if (HeapDumpOnOutOfMemoryError) { ...; HeapDumper::dump_heap_from_oome(); }
    if (OnOutOfMemoryError) { ... }        // 可配"执行个脚本"
    if (CrashOnOutOfMemoryError) { ... }
    if (ExitOnOutOfMemoryError) { os::exit(3); }
  }
}
```
- 触发面：Java heap space、GC overhead limit exceeded（`memAllocator.cpp:125/135`）、Metaspace（`metaspace.cpp:1449`）
- OOME dump **不先 GC**（`HeapDumper dumper(false, oome)`）→ 现场原样（含垃圾）
- 命名：HeapDumpPath 是目录 → `java_pid<pid>.hprof`；不配 → 进程 **cwd**；再次 dump → `.1`、`.2` 后缀

## 三、生产坑

1. 磁盘：dump ≈ 堆量级（32MB 堆→18MB；8G 堆→数 G）→ 容器挂 volume 留足空间；
   写失败只警告不中断（你以为有 dump 其实没有）
2. 容器路径：写的是**容器内**路径，Pod 重启即丢 → 持久卷（或 emptyDir + sidecar 捞走）
3. 停顿：大堆 dump 几秒~几十秒，OOM 后"再卡一阵"
4. 频率：单进程只自动 dump 一次（源码守卫）；滚动重启每次都 dump → 清理/轮转
5. 别混淆：`-XX:+HeapDumpBeforeFullGC/AfterFullGC` 是每次 full GC 都 dump，生产禁用

## 四、一句话

**OOME 自动 dump = 崩溃瞬间的自动遗照**；dump 只是"报告"路径（不保证退出），
要配 `ExitOnOutOfMemoryError`（码 3）或 `OnOutOfMemoryError` 脚本让编排接管。
