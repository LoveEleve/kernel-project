# attach-02 · VM 操作 / STW / 卡死排查

## 一、为什么需要 VM 操作

jstack 要"一致的线程快照"、jmap 要"稳定的对象图"、GC 要移动对象——
这些不能在 Java 线程乱跑时做 → 由专职线程 **VMThread** 执行 **VM_Operation**。

```cpp
// vmThread.cpp:535-548（主循环）
if (_cur_vm_operation->evaluate_at_safepoint()) {
    SafepointSynchronize::begin();        // 停世界
    evaluate_operation(_cur_vm_operation);// op->evaluate() = doit()
    SafepointSynchronize::end();          // 放行
}
```
`VM_Operation::evaluation_mode()` 默认 `_safepoint`（`vmOperations.hpp:195`）。

## 二、attach 命令如何搭上这条流程

```
客户端 ──socket──> Attach Listener（单线程，接单）
   │ 构造 VM_Operation → VMThread::execute(&op)
   ▼
VMThread::execute：入队 _vm_queue + notify，然后阻塞等结果
   // vmThread.cpp:712-717
   while (t->vm_operation_completed_count() < ticket)
       VMOperationRequest_lock->wait(...);
   ▼
VMThread：safepoint → doit() → end() → 计数 +1 + notify_all（vmThread.cpp:426-429, 622-624）
   ▼
Attach Listener 醒 → doit_epilogue → op->complete() 写 socket
```
注意：`VMThread::execute` 是静态方法，调用者（Attach Listener）才是等待者；
副作用是 JNI/内部线程也能用同一条路（如 kill-3 的 Signal Dispatcher）。

## 三、jstack 的底层：3 个 safepoint 操作

服务端 `thread_dump`：`arg(0)` 逐字符扫描 'l'/'e'，然后依次执行
`VM_PrintThreads`（栈）+ `VM_PrintJNI`（尾部统计）+ `VM_FindDeadlocks`（死锁段）。
每个都是默认 safepoint → **jstack = 3 次 STW + 全量栈打印**（毫秒级起，线程多则更长）。

## 四、jmap 各参数的代价（分档）

| 命令 | 机制 | STW 结构 |
|---|---|---|
| -histo | attach inspectheap（默认 -all）→ VM_GC_HeapInspection；并行统计（默认 3/8 CPU，-parallel 可调） | 1 次 safepoint，不 GC |
| -histo:live | 同上 + 先 full GC（cause=_heap_inspection） | GC + 统计 |
| -dump[:live] | attach dumpheap → VM_HeapDumper（VM_GC_Operation + 并行 GangTask）：遍历+写文件全程在 safepoint；:live 再先 full GC（cause=_heap_dump） | 全程 STW |
| -clstats | jcmd GC.class_stats（遍历元数据） | 1 次 safepoint，偏重 |
| -finalizerinfo | jcmd GC.finalizer_info | 很轻 |
| -F / -heap | JDK11 已移除（SA 时代产物；-heap 改用 jcmd GC.heap_info） | — |

线上原则：**先轻后重（histo → dump）、低峰执行、能自动就别手动**。

## 五、写响应的是谁（两段分工）

- **内容**由 VM 线程写进**内存缓冲区**（`bufferedStream`，分发时传 `&st` 给 handler → VM op 写 `st`；11.0.32 可增长，bufmax=10MB）
- **socket 响应**由 Attach Listener 线程写（`op->complete(res, &st)` →
  `LinuxAttachOperation::complete`：先写 `"<res>\n"`，再 `write_fully(socket, st->base(), st->size())`，
  最后 shutdown/close）
- 设计原因：safepoint 内不能做可能阻塞的 socket IO；所以"VM 线程写内存 → listener 写 socket"。
- 推论：listener 必须等 VM 操作完成（缓冲区填完）才有的可发。

## 六、Attach Listener 单线程 → 排队

- Linux 实现注释原文：single threaded server（accept → read → execute → respond 全在同一循环）
- `execute()` 阻塞期间不 accept、不 read → 后来者在内核 listen backlog + 客户端 read 无限等
- 客户端响应读取**没有超时**；`sun.tools.attach.attachTimeout`（默认 10000ms）只用于
  "发完 SIGQUIT 后轮询等 socket 文件出现"的懒启动阶段（`VirtualMachineImpl.java:75-97`）
- 时间线证据：大 dump 期间的 jstack 会排到 dump 完（实测 jcmd 排队 8.2s）

## 七、"卡死"三层次

1. **Attach Listener 忙/排队**（前一条慢命令占着；会恢复）
2. **VM 操作做不了**：(a) doit 卡住（如 dump 写 NFS，持着 safepoint，最危险）；
   (b) safepoint 进不去（TTSP ∞，某线程卡在无 poll 的长代码）
3. **进程整体僵死**：VM 内部死锁 / native OOM / 内核 D 状态 / cgroup freezer / 命名空间错位

## 八、排查流程

```
timeout 10 jstack 卡住
├─ 进程没了 → 崩了（hs_err/dmesg）
├─ T / D / Z → 被停 / 卡内核 IO / 僵尸 → 对应处理
├─ 有别的 attach 在跑 → 排队，等
├─ kill -3 有输出 → attach 链路问题（listener/连接层）
└─ kill -3 也没输出 → VM 操作层
     ├─ vmoperation "begin 无 end" → doit 卡住（查 dump 路径/NFS/磁盘）
     ├─ safepoint 无结束 → TTSP
     └─ 都没有 → gdb/eu-stack 看 VMThread 栈
```
- `kill -3` 走 Signal Dispatcher → 同样 3 个 VM 操作 + 堆信息（`Universe::print_heap_at_SIGBREAK()`），
  打到目标进程 stdout，不走 socket；但懒启动期可能被 `.attach_pid` 信物"吃掉"。
- jstat 不走 attach（mmap 读 `/tmp/hsperfdata_<user>/<pid>`）→ 卡死时也能读（但计数器停更）。
- OS：`cat /proc/<pid>/status | grep State`、`ps -o pid,stat,wchan -p <pid>`、
  `/proc/<pid>/task/<tid>/wchan`、`gcore` + `jhsdb jstack --core`；
  口诀：S=正常睡、R=在跑、D=卡内核（杀不动）、T=被停（先 CONT）、Z=已死没埋。
- 平时开：`-Xlog:safepoint=info -Xlog:vmoperation=info`；
  可选启动 `-XX:+StartAttachListener` 绕开懒启动。
