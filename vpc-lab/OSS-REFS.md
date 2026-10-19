# OSS-REFS —— vpc-lab 开源对照索引（R3 建立）

> 纪律：**每个复现文件头的"对照点"必须可核验**——形式为「本地材料路径」或「`refs/<repo>:<file>:<line>`」。
> 禁止凭记忆写对照（R3 前的问题：本地对照材料零引用 + 错误对照点未被发现）。
> 学习主线排课（9 课 + 进度看板）：`docs/vpc/开源精读路线.md`。

## 一、本地对照文档（材料自带，此前被漏用）

| 材料 | 路径 | 用途（对照哪些批次） |
|---|---|---|
| vs K8s Controller | `vpc/test-good/06-open-source-vs/vs-k8s-controller.md` | L3-1 fusion（reconcile 报告制 vs Reconcile Loop；快照/事件源） |
| vs Calico / OVN | `vpc/test-good/06-open-source-vs/vs-calico-ovn.md` | L1-5 DFW（Felix/iptables-ipset）；Overlay 对比 |
| vs 业界实践 | `vpc/test-good/06-open-source-vs/vs-industry-practices.md` | 全局（Andromeda/Hyperplane/VFP：Source of Truth 与对账存在的理由） |
| 论文 9 篇 | `vpc/papers/`（01-Andromeda / 02-B4 / **03-Saga-SIGMOD1987** / 04-CRDTs / 05-Omega / 06-Onix / **07-ZooKeeper-ATC2010** / 08-B4AndAfter / 09-PID） | sdn-oss（Saga/Lock）、nfv-proxy（ZK）、fusion（一致性） |
| 论文↔架构映射 | `vpc/docs/论文-VPC架构映射.md` | 论文选读路线 |
| 精要卡 | `vpc/docs/精要卡-43-Calico与OVN对比.md`、`-44-业界控制面实践.md`、`-45-VPC与K8s控制面对比.md` | 同上三份 vs 文档的压缩版 |
| **ZK 学习线材料** | `vpc/test-good/01-zk-distributed/`（7 份：zk-knowledge / zk-learning-path / db_dlock / kafka_consumer / syncer_skippath_fix / zxid_counter_extend / architecture-evolution） | ZK 本体精读（配 `refs/zookeeper`）；L1-1/L1-2 的锁与消费故事 |

## 二、克隆开源代码（`refs/`，shallow clone，已 gitignore、可再生）

| 仓库 | commit | 用途（对照点） |
|---|---|---|
| `go-mysql-org/go-mysql` | `56e3844` | **L1-2 go_proxy**：`StartFromGTID`（canal/canal.go:213）、`SyncedGTIDSet`（:558）；**无自动降级**（MergeFromDb 为自研上层） |
| `python-zk/kazoo` | `f6a78f7` | **L1-2 lock/zkconn**：`recipe/lock.py`（ephemeral sequential + 前驱监听 + is_acquired；LockLost 由业务处置） |
| `twmb/franz-go` | `7b6cac3` | **L3-1 consumer**：`pkg/kgo/consumer.go:522 PollFetches`；`examples/group_committing`（手动提交） |
| `segmentio/kafka-go` | `2e0b396` | R3 核实：**无 `for range reader` API**（修正错误对照点的依据） |
| `IBM/sarama` | `557e059` | **反例模型**：channel 消费（consumer.go:403 `Messages() <-chan`；config.go:615 ChannelBufferSize 反压） |
| `openstack/taskflow` | `571351c` | **L1-1 Saga 工程化**：`RevertAll`（engines/action_engine/completer.py:72）；回滚失败 → REVERT_FAILURE（actions/task.py:153） |
| `kubernetes-sigs/controller-runtime` | `168e1ca` | **L3-1 reconcile**：`Reconciler` + `Result{RequeueAfter}`（pkg/reconcile/reconcile.go:44，converge 语义） |
| `kubeovn/kube-ovn` | `524119b` | **L3-1 对照**：leaderelection（pkg/controller/config.go:18）；集中式 OVN NB DB（pkg/ovs/ovn-nb-*.go）；cmd 多二进制分发（cmd/cmdmain.go；cmd/controller/cmdmain.go 29 行薄入口） |
| `projectcalico/calico`（**felix 独立仓已归档**，代码并入 monorepo） | raw 定向核验（不克隆整仓） | **L3-1 对照**：felix/dataplane/linux/int_dataplane.go:2320 full resync（"will wait for the full resync"） |

## 三、R3 核验结果（对照点修正/补证）

| 文件 | 结论 |
|---|---|
| vpc-fusion/consumer.go | 🔴修正：kafka-go 无 channel-range API → 反例改为 sarama（channel 模型 + 缓冲反压） |
| vpc-fusion/binlog_service.go | 🟡修正："franz-go upsert 示例"不存在 → 应用层 upsert + 手动提交对照；R1 遗留（fusion-position）解除：材料在 `test-good/02-vpc5-gen3/vpc-fusion-position.md` |
| vpc-fusion/worker_service.go | 🟡修正："Kube-OVN 快照推送"无此特性 → 集中式 OVN NB DB 对照 |
| vpc-fusion/reconcile.go | ✅补证：controller-runtime RequeueAfter + 本地 vs-k8s-controller.md |
| vpc-fusion/leader_vote_service.go | ✅补证：kube-ovn leaderelection 实锤 |
| nfv-proxy/go_proxy/{server,event_from_db}.go | ✅补证：canal 位点 API 存在、**降级补偿为自研** |
| nfv-proxy/lock.py | ✅补证：kazoo recipe 行为（丢锁告知是异常，自杀是业务策略） |
| sdn-oss/tvpc_handle.py | ✅补证：Saga 1987 论文（本地 PDF）+ TaskFlow RevertAll/REVERT_FAILURE |

### R4 追加（对照点全量审计：34 条逐一过筛）

| 文件 | 结论 |
|---|---|
| vpc-fusion/main.go | ✅核验：kube-ovn cmd 多二进制分发 + cmd/controller/cmdmain.go **29 行**薄入口实锤 |
| vpc-fusion/server.go | 🟡修正两处："TF 各进程独立生命周期"无据 → 改引本地 `docs-by-topic/01 附录 A`（Control Node/vRouter Agent；TF 无 Saga/Outbox、仅内核数据面）；"felix 健康降级思路"无据 → full resync（raw 核验） |
| vpc-fusion/reconcile.go | ✅补证：felix full resync raw 引用（int_dataplane.go:2320） |
| sdn-oss/decorator.py | ✅补证：scanner 精确路径（`sdn_oss/skills/vpc5-object-api-scanner/`，23 文件，24 清单:506） |
| 全库 26 条概念级对照 | 登记入 §四（模式名级纪律）；R3 已核验的 8 条经全线复核无一失准 |

## 四、概念级对照登记（模式名级；**未做代码核验**，禁止附加具体断言）

以下对照点保留在文件头，但按纪律登记为"概念级"——引用的是公开模式/产品（名字+语义），不是代码事实：

| 文件 | 概念对照 |
|---|---|
| vpc-fusion/task_service.go / task_dao_impl.go | SQS visibility timeout（租约+重投递）、Temporal（心跳+activity 重试） |
| vpc-fusion/leader_vote_dao_impl.go | Redis 官方分布式锁模式（SET NX EX+续约校验）、etcd lease |
| vpc-fusion/main.go | k8s component-base（options→run） |
| sdn-oss/tvpc_transaction_db_client.py / bin/* | Transactional Outbox（Chris Richardson）、Debezium Outbox Event Router |
| sdn-oss/kafkab_proxy.py | librdkafka sync/async、Kafka 幂等生产者 |
| sdn-oss/tvpc_token_bucket_redis.py | Redis INCR+EXPIRE、redisson RRateLimiter |
| sdn-oss/tvpc_db_client.py | SQLAlchemy Core / Django QuerySet |
| sdn-oss/tvpc.py / cgi_base_tvpc.py | WSGI PEP 3333、API Gateway 信封/限流、sentry 异常收口 |
| sdn-oss/decorator.py | Django middleware、Facebook MySQL→TAO 迁移 |
| sdn-oss/zoob_proxy.py | WAL 思路（ZK 官方 recipe 无此层——自研） |
| nfv-proxy/file_differ.py | coreutils sort/comm 流式差分 |

未克隆且暂无本地材料：**Curator / Debezium**（DPVS/Katran/gobgp 已于 R5 拉取）；
TF 有本地材料（`docs-by-topic/01 附录 A`，含"TF 无 Saga/Outbox"等逐项对照）；
felix 特例：独立仓已归档（实测 clone 仅剩弃用页），改用 calico monorepo sparse 拉取（felix+typha）。

## 五、开源学习仓全量清单（R5：38 仓 / ~2.3G，`refs/` 已 gitignore）

按"映射本系统哪个组件"分组（对照用户提供的参照系表）：

| 分组 | 仓库（体积 / 用途） |
|---|---|
| **整栈对照（最接近）** | `tf-controller`（107M，config/control/XMPP/IF-MAP）；`tf-vrouter`（51M，dp-core/dpdk/freebsd 三转发形态） |
| **VPC 栈标准开源** | `neutron`（58M）；`ovn`（20M，northd/ovn-controller）；`kube-ovn`（24M，Go 全链，已用于 R3/R4 核验） |
| **K8s CNI / agent 对照** | `calico`（20M，sparse：felix delta 处理 + typha fan-out）；`cilium`（478M，eBPF 数据面）；`antrea`（39M）；`terway`（14M） |
| **网关 / 数据面** | `dpvs`（19M，FNAT/SNAT——对照 LBNAT）；`katran`（10M，XDP L4LB）；`vpp`（97M）；`ovs`（31M）；`f-stack`（536M，含 vendored）；`snabb`（67M）；`bess`（4.2M） |
| **路由协议** | `frr`（111M）；`gobgp`（9.7M，Go 好读）；`behavioral-model`（11M，P4） |
| **控制面工程** | `controller-runtime`（5.6M，已用于 R3/R4）；`etcd`（27M，lease/watch——对照 ZK 角色）；`consul`（144M） |
| **分布式底座（我们系统的直接依赖物）** | `zookeeper`（33M，**ZK 本体**：ZAB/会话/watch——配 test-good/01-zk-distributed 学习线）；`dpdk`（152M，数据面底座：dpdk 分支/DPVS/vpp 共基座） |
| **vpc5.0 协议/存储对照** | `trpc-go`（26M，**vpc5.0 真实使用的框架**）；`grpc-go`（23M，流式语义对照）；`goleveldb`（1.3M，fusion 存储对照）；`btree`（360K，broker BTree 对照） |
| **内核工具** | `iproute2`（9M，vpc_agent/tvpc.py 的 `ip` 命令底层） |
| **Overlay"全身"（小而可读）** | `ZeroTierOne`（67M）；`tailscale`（39M）；`netmaker`（5.9M） |
| **对照核验专用（R3/R4 引入）** | `go-mysql`、`kazoo`、`franz-go`、`kafka-go`、`sarama`、`taskflow` |

> 未拉（**刻意**）：`kubernetes/kubernetes` 本体（"声明式 controller"学习位由 controller-runtime + kube-ovn 的 client-go 依赖覆盖）；
> `linux` 内核本体（过大——netlink/GRE/veth 语义走 `iproute2` + 本地材料）；`Curator`/`Debezium`（按需补）。
> 全部为 `--depth 1` 浅克隆（calico 为 blobless+sparse）；**可再生**：按本表逐仓 `git clone --depth 1` 即可。
