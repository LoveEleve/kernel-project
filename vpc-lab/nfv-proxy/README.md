# nfv-proxy（复现）—— 一代分发代理 · L1-2

> 真实系统：`nfv-proxy`（Python 3.6 主体 + Go 1.22 `go_proxy` 子项目；自带 python3.6 打包环境）
> 真实规模：根目录 40+ 手写模块；常驻进程模块 14 个（§4.1 映射，含 proxy1 双份）；
>           init_* 快照 21 个文件（19 类数据 + 编排 init_vpc + 基类 init_vpc_common）；
>           tools_* 运维工具 22 个；change_log/ 22 个版本脚本；
>           bin/ 20 个文件（17 个 .sh + include 函数库 + 2 个建库 SQL）；go_proxy 21 个文件条目（17 个 .go）。
> 本复现为**教学切片**：覆盖"binlog 下发链 + 快照体系 + 守护模型 + Go 旁路"四条主线，路径与真实同名。
> 上游：L1-1 sdn_oss（产 binlog）；下游：分区 ZK / CKV（vpc_agent 消费）。

## 一、它是什么（一句话）

**nfv-proxy 是一代控制面到网关宿主机之间的"数据快照同步与下发代理"**（对账/同步系统）：
把 vpcoss 的变更（Ckafka binlog）写进**分区 ZooKeeper**给 vpc_agent watch；
把全量数据做成**快照压缩进 CKV**（redis 协议 KV）并维护版本；
另有 Go 旁路（canal）直订 CDB binlog 补一条 MySQL→ZK 的独立通道。

## 二、四条主线（本复现覆盖）

```
① 增量链（实时）：Ckafka(vpcoss binlog) → proxy_binlog → 分区 ZK /BINLOG/{vpcId}/{资源组}/...
                                  （路径实锤：tools_clean_expire_group.py "+ /BINLOG/{vpcId}/VM 下 group 子树"）
                                  双份进程 proxy_binlog / proxy1_binlog（材料未展开语义；分载+冗余为推导）
② 全量链（快照）：vpcoss 只读 DB → init_vpc(19 类 Syn) → CKV fragments + 版本列表(ZK)
                                  增量折叠：proxy_merge / lazymerge（MERGE_CLASS 6 类公共数据）
                                  调度：proxy_merge_master（pending 管道 + 计数限流）
                                  进程粒度快照：recovery_vpcgw_snapshot（vpcgw/hbgw 每 worker 一份）
③ 旁路链（Go）：CDB binlog → go_proxy(canal) → Dispatcher(hash 队列) → ZkSyncer → 分区 ZK
                                  位点：GTIDSet|date 提交 CommitPath；GTID 失效 → MergeFromDb 全量补偿
④ 守护面：daemon(watchdog/suicide) + proxy_conf(子进程守护) + check_ppid(孤儿检测)
          + proxy_monitor(健康) + monitor_scan(scan_ 进程数比对期望)
```

## 三、问题矩阵（本目录代码 ↔ 它表达的问题）

| 文件 | 业务问题 | 分布式问题 | 工程问题 |
|---|---|---|---|
| `proxy_binlog.py` | binlog → /BINLOG 写入 | master_zk 拓扑变更 → 自杀重建 | ForeverThread 自检 / 双份进程 |
| `lock.py` | — | **ZK 锁雪崩**（LOST→suicide→全量雪崩）→ DB 锁迁移动因 | 会话事件不绑进程生死 |
| `daemon.py` | — | **随机自杀防惊群**（多进程同时重启） | 双 fork / watchdog / cond_wait |
| `zkconn.py` | — | 域名→IP 解析 + 重试超限自杀 | 连接自举 |
| `proxy_meta_info.py` | 分区/拓扑自举 | 元信息 ZK 注册 + watch | 一切进程从这里起步 |
| `init_vpc_common.py` | 快照正确性 | 分片/版本/断点续传/交集缺失 | 大 value 治理（CKV fragments） |
| `proxy_merge*.py` | 快照+binlog 两级读取 | 合并调度限流（防 vm_prop 拖死队列） | pending 管道 / 惰性合并 |
| `recovery_vpcgw_snapshot.py` | 进程粒度快照 | 每 worker 版本表 | gevent 入口约定（禁止被 import） |
| `proxy_monitor.py` | 分区覆盖完整性 | scan_ 进程数 vs 期望比对 | TCP 连接数/DB 可达探活 |
| `go_proxy/*`（Go） | CDB→ZK 旁路 | **GTID 位点 + 失效降级全量补偿** | ZK 抢 worker 序号 + 分布式锁 |

## 四、省略清单（切片边界，防"以为覆盖了"）

未复现的模块（真实存在，本切片以注释/README 提及）：
`syn_op.py`（~1100 行快照操作封装）、`tools_*`（22 个运维工具）、`change_log/`（22 个升级脚本）、
`bin/`（20 个脚本）、`thirdpkg/`（北极星 sidecar）、`models.py`（~830 行 ORM）、
`tvpc_oss.py`/`tvpc_polaris.py`（HTTP 客户端）、`proxy_cl5`/`proxy_init_zk`/`proxy_init_vpc`/`append_snapshot`
（进程存在、链路中以注释提及）、`vpc_ckv.proto`（快照 protobuf 定义）。

## 五、使用纪律

1. 文件头 `【复现】` 注明真实出处 + 证据等级；关键参数（分区 ZK 路径、fragments、25000、6 类 MERGE_CLASS、
   proxyWorkerNum、GTIDSet|date…）全部来自材料口径（docs-by-topic/23 + 08 + 17）；
2. Python 侧真实运行环境为 3.6（自带打包），切片用现代可读语法（行为等价）；
3. Go 侧（go_proxy）真实依赖 go-mysql/canal、samuel/go-zookeeper、polaris-go、go-base——
   切片用标准库等价桩保持**可编译**，对应关系在文件头标注；
4. 开源对照不参与口径（Kafka consumer / Kazoo / go-mysql canal / Redis 大 key 治理）。

## 六、建议阅读顺序

```
main.py                      ← 总控：module → fork（ALL_IN_ONE 多地域）
  → daemon.py                ← 守护面：watchdog / suicide(随机) / cond_wait
  → zkconn.py                ← 连接自举：域名→IP + 重试超限自杀
  → proxy_meta_info.py       ← 元信息自举：region/zone/partition/master_zk/ckv/topic
  → proxy_binlog.py          ← ★ 增量链：消费 → 写 /BINLOG；拓扑变更→自杀重建
  → lock.py                  ← ★ ZK 锁雪崩教训（迁移 DB 锁的完整因果）
  → init_vpc_common.py       ← ★ 快照基类：分片/版本/断点续传/交集缺失
  → init_vpc_route_table.py  ← 一个具体快照类（vmIp 维度 V4/V6）
  → proxy_merge.py           ← 增量折叠：binlog 分组 → 新快照 → CKV
  → proxy_merge_master.py    ← 合并调度：pending 管道 + 计数限流（vm_prop 问题）
  → recovery_vpcgw_snapshot.py ← 进程粒度快照
  → proxy_monitor.py         ← 健康检查 + scan_ 进程数比对
  → go_proxy/                ← 旁路链（Go）：server → dispacher → event_2_zk → event_from_db
```
