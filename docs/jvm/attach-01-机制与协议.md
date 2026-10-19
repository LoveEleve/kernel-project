# attach-01 · Attach 机制与协议

## 一、Attach 是什么（一句话）

一个**运行中的 JVM** 暴露的本地管理通道：Unix socket + JVM 内的 Attach Listener 线程。
例子：`jcmd 275230 VM.uptime` → 连上 → 返回 `18393.255 s`。

## 二、socket 文件与懒启动

- 路径：`/tmp/.java_pid<pid>`；创建方式 bind `.tmp` → chmod 0600 → chown → 原子 rename
- **懒启动**：Attach Listener 线程默认不存在；客户端建 `.attach_pid<pid>` 信物 + 发 SIGQUIT 触发
- 服务端校验（`is_init_trigger`）：相对路径 stat + 属主校验
- 客户端：`attach()` 只做预检连接；`detach()` 仅客户端置空；无会话概念

## 三、命名空间注意

- `/proc/<pid>/root`：跨 mount namespace 时 socket 视角不同
- `NSpid`：pid namespace 里取最内层（客户端 `getNamespacePid`）

## 四、协议格式

- 请求：`"1\0<cmd>\0<arg0>\0<arg1>\0<arg2>\0"`（5 个 NUL 分隔字段，固定 3 参数）
- 响应：`"<状态码>\n"` + 数据 + EOF；版本不匹配回 `101`
- 实验：`/tmp/opencode/proto-demo.sh`（正常 / 版本错 101 / 未知命令 -1 三例）

## 五、命令表（`funcs[]`，共 10 条）

| 命令 | 处理函数 | 参数 | 工具映射 |
|---|---|---|---|
| properties | get_system_properties | — | jinfo -sysprops |
| agentProperties | get_agent_properties | — | — |
| datadump | data_dump | — | SA 系 |
| threaddump | thread_dump | arg0: -l/-e（逐字符扫描） | jstack |
| inspectheap | heap_inspection | -live/-all，-parallel | jmap -histo |
| dumpheap | dump_heap | arg0 文件名，arg1 -live/-all（缺省 live=true） | jmap -dump |
| setflag | set_flag | 标志名/值 | jinfo -flag |
| printflag | print_flag | 标志名 | jinfo -flag |
| jcmd | jcmd | 整行子命令（`DCmd::parse_and_execute`） | jcmd |
| load | load_agent | arg0 agent 库，arg1 是否绝对路径，arg2 参数 | attach API loadAgent |

**load 命令**：`JvmtiExport::load_agent_library(agent, absParam, options)` → dlopen + 调 `Agent_OnAttach`；
`agent == "instrument"` 时先 `Modules.loadModule("java.instrument")`；
JDK11.0.32 源码新增闸门 `-XX:+EnableDynamicAgentLoading`（关闭时 `load` 被拒；11.0.2 二进制可能无此 flag）。

## 六、客户端 API ↔ 命令映射（jdk.attach）

`loadAgentLibrary/loadAgentPath/loadAgent` → load；`remoteDataDump` → threaddump；
`heapHisto` → inspectheap；`dumpHeap` → dumpheap；
`getSystemProperties/getAgentProperties` → properties/agentProperties；
`getFlag(s)/setFlag` → printflag/setflag；`executeJCmd` → jcmd。

客户端 `load` 实证（`HotSpotVirtualMachine.java:95-98`）：
```java
InputStream in = execute("load", agentLibrary,
                         isAbsolute ? "true" : "false", options);
```

## 七、源码锚点（HotSpot 11.0.32）

- `hotspot/os/linux/attachListener_linux.cpp`：init 181-241、dequeue 346+、complete 408-425、is_init_trigger 530+
- `hotspot/share/runtime/os.cpp:339, 362-400`
- `hotspot/share/services/attachListener.cpp`：funcs[] 表、listener 线程启动 422-475、dump_heap 220-241
- `jdk.attach`：`VirtualMachineImpl.java`、`HotSpotVirtualMachine.java`、`libattach/VirtualMachineImpl.c`

## 八、实验

- `experiments/jvm-diagnostics/01-attach-basics/`（AttachTarget/AttachDemo/run.sh：socket + 懒创建实测）
- `experiments/jvm-diagnostics/02-commands/`（Target/CmdDemo/run.sh：8 命令；需 `--add-exports jdk.attach/sun.tools.attach=ALL-UNNAMED`）
- `/tmp/opencode/mnt-demo2.sh`（`/proc/<pid>/root` 语义）、`/tmp/opencode/proto-demo.sh`（协议三例）
- 常驻目标：`playground/`（RandomLoop；注意 PID 会变，用 `jps -l` 复核）

## 九、可复制命令

```bash
J=/data/workspace/openjdk11/bin
$J/jps -l
$J/jcmd <pid> VM.uptime
$J/jstack <pid> | head -20
```
