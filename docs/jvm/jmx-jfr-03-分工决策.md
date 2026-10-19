# jmx-jfr-03 · 分工决策（JMX vs JFR vs Arthas）

> Session E 收口。JMX/JFR 深入轨道收官文档。

## 一、三者本质（一句话各）

| 工具 | 本质 | 定位 |
|---|---|---|
| **JMX** | 远程管理接口（PlatformMBeanServer 注册中心 + 属性/操作/通知） | 常态监控面板、运维对接 |
| **JFR** | 低开销飞行记录器（事件采样 + 业务埋点） | 趋势分析、事后取证 |
| **Arthas** | 动态诊断探针（attach 进去，实时 watch/trace/redefine） | 线上临时排查、热修复 |

## 二、决策表（按场景选）

| 你想干什么 | 首选 | 理由 |
|---|---|---|
| 常态监控指标（堆/线程/GC） | JMX | MXBean 天然提供，Prometheus/监控系统对接 |
| 业务语义埋点（订单超时/缓存击穿） | JFR 自定义事件 | 代码里 `commit()`，不改运维工具 |
| 事故后取证（OOM/慢请求） | JFR 录制 + OOME dump | 低开销连续录，事后回放 |
| 线上实时排查（谁调用/传了什么参数） | Arthas `watch/trace` | 不用重启，即时出结果 |
| 热修复（改逻辑不重启） | Arthas `redefine/mc` | 字节码替换，风险高需评估 |
| GC 停顿归因 | JFR `GCPhasePause` / JMX GC 通知 | 停顿时长 + 原因推送 |
| 锁竞争/线程等待 | JFR `JavaMonitorEnter` / Arthas `thread` | 阈值触发，带栈 |
| 远程连接带认证（生产） | JMX 双文件（password + access） | 认证/授权分离 |

## 三、决策树

```
线上出问题了，你想干什么？
├── 看"当前状态"（堆/线程/CPU 数字）→ jcmd / Arthas dashboard / JMX 轮询
├── 看"趋势/历史"（过去 10 分钟发生了什么）→ JFR 录制 + jfr view
├── 看"谁调用的/传了什么参数"（实时）→ Arthas watch/trace
├── 修"逻辑但不重启"（热修复）→ Arthas redefine/mc
└── 修"配置/参数"（如调堆大小）→ JMX setAttribute / jinfo（部分可动态）
```

## 四、本轨道 Session 总账

| Session | 内容 | 实战产物 |
|---|---|---|
| A | JMX 自定义 MBean + 远程客户端 | `CustomMBeanDemo` + `JmxClientDemo` |
| B | JMX 通知 + 远程认证双文件 | `GcNotifyClientDemo` + `JmxAuthClientDemo` |
| C | JFR 事件模型 + 自定义 settings | `custom-minimal.jfc` + 录制 → `jfr view` |
| D | JFR 自定义事件 API + streaming | `CustomEventDemo` + `JfrStreamDemo` |
| E | 三工具分工决策表 | 本文档 |

实验目录：`experiments/jvm-diagnostics/09-jmx-jfr/`
