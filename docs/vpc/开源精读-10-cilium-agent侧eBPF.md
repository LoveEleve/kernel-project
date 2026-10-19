# 开源精读 10 · cilium：agent 侧 eBPF——"策略编译进内核"的另一种做法

> 课次：第二批第 1 课（agent 侧 eBPF；把"agent×数据面"矩阵的最后一格收满）
> 对象：`refs/cilium`（commit `721c7923`，巨仓——只读"一个 Pod 策略的一生"关键路径）
> 对照：我们的 **DFW agent**（安全组：Watcher-Builder / iptables+ipset / DPDK 双路径——材料 05）；与第 9 课 Katran 同用 eBPF，但那是 LB 数据面，这里是"**每 Pod 策略**"数据面。
> 纪律：路径与行号来自实仓核验。

---

## 0. 术语速查

| 术语 | 含义 |
|---|---|
| **Endpoint** | cilium 眼中的"一个网络端点"（≈一个 Pod 的 veth 侧）：有 ID、identity、全生命周期 |
| **identity（安全身份）** | label 集合 →**数字身份**；策略按身份表达——运行时 map 里存"身份"而非 IP 列表 |
| **selectorPolicy** | 策略求值产物：某个 identity 的"允许什么"的中间表示（每 endpoint 求值一次） |
| **Regenerate（再生）** | 把"策略/配置变化"变成"该 Endpoint 的 BPF 配置与程序更新"的管线 |
| **tail call / callsmap** | BPF 程序间跳转机制 / 每 endpoint 的"跳转目标表"——程序=骨架+槽位 |
| **ctmap** | eBPF conntrack（连接跟踪 map）——有状态转发的最小号形态 |

## ① 它干了什么——一个 Pod 策略的一生

给一个带 label 的 Pod 上策略，cilium 的十秒：

1. agent 发现新 Pod → 创建 **Endpoint** → 由 label 解析出 **identity**（数字安全身份）；
2. `Repository.resolvePolicyLocked(identity)`——按身份**求值策略**，产出 `selectorPolicy`；
3. **再生（Regenerate）**：为该 Endpoint 生成 BPF 配置（`WriteEndpointConfig`）并把该 Pod 的策略执行段装载进**它的尾调用表（callsmap）**；
4. 程序 attach 到 veth——首包进 `cil_from_container` → 尾调用链（CT 查询 → **策略** → 服务处理）→ 放行/丢弃；
5. 流状态记进 **ctmap**。

**一句话**：策略不是"抄进 iptables 规则表"，而是**"按 Pod 编译成挂在它程序链上的执行段，数据（身份/策略结果）进 map"**。

## ② 链路走查（file:line 级）

### 跳 1：地图——agent 侧与内核侧

**agent（Go）**：`pkg/endpoint/`（Endpoint 与再生：`regenerator.go`、`regeneration/`）、`pkg/policy/`（求值）、`pkg/datapath/loader/`（BPF 编译与装载：`base.go`/`cache.go`/`compile.go`）、`pkg/datapath/linux/config/`（配置写入）、`pkg/maps/`（**33 个条目**：ctmap / callsmap / ipcache / configmap / lxcmap / egressmap / fragmap / policymap / nodemap / srv6map …）。

**内核侧（C）**：`bpf/bpf_lxc.c`（容器主程序 **2652 行**）、`bpf/bpf_host.c`（主机侧 2026 行）、`bpf_overlay.c` / `bpf_sock.c` / `bpf_xdp.c` / `bpf_wireguard.c` + `bpf/lib/`（公共库）。

### 跳 2：入口函数与尾调用网——"骨架 + 槽位"

两个入口（`bpf/bpf_lxc.c`）：`cil_from_container@1701`、`cil_to_container@2496`。

整个程序族不是"一条汇编长龙"，而是**用尾调用缝起来的网**——全文件 **46 处** `tail_call*` / `__declare_tail`（grep -c 实证），节选（跨多个函数）：

```c
	ret = tail_call_policy(ctx, CONFIG(host_ep_id));   // :68（lxc_deliver_to_host 里；原注释："bpf_lxc can be loaded before bpf_host"）
	...
	return tail_call_internal(ctx, CILIUM_CALL_IPV4_CT_EGRESS, ext_err);   // :271  CT 查询段
	...
__declare_tail(CILIUM_CALL_IPV6_FROM_LXC_CONT)                            // :1009 —— 声明"尾调用目标段"
TAIL_CT_LOOKUP6(CILIUM_CALL_IPV6_CT_EGRESS, tail_ipv6_ct_egress, ...)     // :1025
```

`__declare_tail` 一族是"骨架+槽位"的声明侧；跳转目标的装载在 `pkg/maps/callsmap/doc.go`："**the internal calls map for endpoint and host datapaths … primarily managed from the datapath**"——**每个 Endpoint 有自己的跳转目标表**：热更新 = 换槽位 ≪ 重编译。

### 跳 3：策略求值——按"身份"而不是"IP"

`pkg/policy/repository.go:337`：

```go
func (p *Repository) resolvePolicyLocked(securityIdentity *identity.Identity) (*selectorPolicy, error) {
```

求值的**单位是身份**：同一 identity 的 Endpoint 共享同一份策略集（`selectorPolicy`）。**identity 把"label 集合"收敛成"数字"，把"策略匹配对象"从 IP 列表变成身份**——map 里存身份（小而稳）；Pod 重建换 IP，身份不变，**策略零重编译**。对照我们的 ipset（IP 集合下发）：这就是"身份模型 vs IP 模型"的分水岭。

### 跳 4：再生管线——策略变化后的"收尾"

`pkg/endpoint/policy.go:912/934`：`RegenerateIfAlive` / `Regenerate(regenMetadata)`；配套 `regenerator.go` 与 `regeneration/`（`ExternalRegenerationMetadata`）。配置落点在 `pkg/datapath/linux/config/config.go:322-323`：

```go
// WriteEndpointConfig writes the BPF configuration for the endpoint to a writer.
func (h *HeaderfileWriter) WriteEndpointConfig(w io.Writer, e endpoint.Config) error {
```

装载层是 `pkg/datapath/loader/`（`base.go`/`cache.go`/`compile.go`——**编译有缓存**）。"再生"一词的含义：**Endpoint 的数据面描述（配置 + 程序槽位 + 数据）是一个可整体重建的产物**——变化即再生成，而不是补丁式改规则。

### 跳 5：数据面状态——ctmap

`pkg/maps/ctmap/`（eBPF conntrack map 族）：首包建流、后续包按流短路——**"状态放哪"的又一答卷**（总章三维表：cilium 把状态做小——身份 + CT + 尾调用表；把程序做厚——编译期检查）。与第 7/8/9 课（flow entry / conn 表 / LRU）同题异构。

## ③ 设计推导（问题树）

**Q1：为什么"每 endpoint 再生"，而不是"全局一套程序 + 运行时全查表"？**
**编译期优化**：策略在生成时成为程序结构（分支/跳转），数据（身份）进 map——每个 Pod 走的是为它剪裁过的执行路径，数据面判定更便宜。代价是复杂度搬到了 agent：变化时要走 Regenerate 管线（Q5）。

**Q2：tail call + callsmap 同时解决哪两个约束？**
① eBPF 的硬约束（程序大小上限 + verifier 复杂度）——大逻辑拆成小段串联；② **热更新**——换"槽位指向"即可替换执行段，不必重挂整个程序。第 9 课见过同一族约束的另一面（5.2/5.6 verifier 差异把代码塑形）——**eBPF 世界的代码是被 verifier 和地图共同塑造的**。

**Q3：identity 模型的价值与代价？**
价值：策略表达与 IP 解耦、map 尺寸小、Pod 重建不引发策略风暴。代价：多出一个"身份分配/回收 + ipcache 同步"的子系统——**一致性面变宽**了（对照我们：规则直接绑 IP/ipset——简单，但重建即重下发）。

**Q4：为什么要在"来源格式"与"执行格式"之间隔一层？**
分层是：来源（CRD）→ **求值**（Repository → selectorPolicy）→ **再生**（Endpoint）→ **装载**（loader）。求值这层把"策略来源"归一化成"身份的允许集"，再往下的每一层都只面对中间表示（IR）——来源可以多样，执行面单一。对照我们：安全组 → Watcher-Builder 构建规则集 → iptables/ipset——同构的一句话是"**都有一层 IR**"。

**Q5：与我们 DFW agent 的工程对照？**
同构：期望态 → 编译 → 执行面。差异三处：**介质**（iptables/ipset vs eBPF maps）、**判定位置**（netfilter 钩子 vs veth/TC/XDP）、**更新粒度**（规则级 vs endpoint 级）。取舍得看约束：eBPF 性能上限高但受内核版本与 verifier 约束；iptables 直观、全版本可用但大规则集退化（ipset 是它的补丁）——我们"iptables+ipset / DPDK 双路径"正是这个取舍的中间答卷。

## ④ 对照卡

| 维度 | cilium | 我们 DFW agent（材料 05 级） | Katran（第 9 课） |
|---|---|---|---|
| 数据面介质 | veth/TC/XDP eBPF | iptables+ipset / DPDK 双路径 | XDP eBPF |
| 策略表达 | **identity 数字** | 安全组规则（IP/IPset） | 无（LB） |
| 更新粒度 | **Endpoint 再生**（配置+槽位+map） | 规则集构建下发 | 无状态 |
| 状态 | ctmap | 无 / DPDK 侧 | per-CPU LRU |
| 硬约束 | verifier / 内核版本 | 无（netfilter 全版本） | verifier |
| 一句话 | **把策略编译进每个 Pod 的程序链** | 把规则翻译进内核表 | 把状态搬进 map |

## ⑤ 验收题

1. identity 把什么收敛成了什么？为什么"Pod 重建"不触发策略重编译？
2. tail call + callsmap 同时解决哪两个约束？为什么说"eBPF 代码被 verifier 和地图共同塑造"？
3. Regenerate 的"产物"到底是什么（提示：配置+槽位+数据三者组合），与 iptables"改表即生效"的更新模型差在哪？
4. cilium 与 Watcher-Builder 的同构点是什么？三处差异（介质/判定位置/粒度）各带来什么代价？
5. 用"解耦"一词串起本课：identity 解耦了什么、selectorPolicy 解耦了什么、callsmap 解耦了什么？

---

*本课行号清单（供追问对质）：`bpf/bpf_lxc.c` cil_from_container@1701、tail_call_policy@68、CT_EGRESS 尾调用@271、FROM_LXC_CONT@1009、CT_LOOKUP6@1025；`pkg/policy/repository.go` resolvePolicyLocked@337；`pkg/endpoint/policy.go` RegenerateIfAlive@912、Regenerate@934；`pkg/datapath/linux/config/config.go` WriteEndpointConfig@322-323；`pkg/maps/callsmap/doc.go`（calls map 自述）；目录学：pkg/{endpoint,policy,datapath/loader,datapath/linux/config,maps}、bpf/（bpf_lxc 2652 / bpf_host 2026 行）、daemon/cmd/daemon_main.go（1271 行）。*

*审查记录（首轮深审）：修正 2 处——① **尾调用引码的框架错位**："入口之后…尾调用"不成立于 :68——那一行在 `lxc_deliver_to_host`（文件前部）里，且 `cil_to_container@2496` 在 from 入口之后；已改为"两个入口 + 跨函数尾调用网"，并补 `__declare_tail` 声明侧与全文件 **46 处** 的 grep -c 实证；② maps 计数"二十余种"→实为 **33 个条目**（并补 policymap/nodemap/srv6map 例）。其余引用（1701/337/912/934/322-323、doc.go 自述、体量数）逐条命中。*

*审查记录（二次深审）：R1 两处修正自查通过（33 条目@37、两入口@43、46 处@45 均在位）——**零尾差、零新错**（本课 R1 的修正面小，尾差风险天然低；对照走查件的"修正尾差"教训，此处无同义表述残留）。*
