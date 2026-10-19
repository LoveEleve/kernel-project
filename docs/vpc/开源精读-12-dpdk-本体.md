# 开源精读 12 · DPDK 本体：数据面底座——"把网卡从内核手里接过来"

> 课次：第二批第 3 课（底座课——九课里反复引用 DPDK，本课收束它）
> 对象：`refs/dpdk`（commit `2fda788`，巨仓——只读"五大件 + 两条路线"）
> 对照：九课的 DPDK 消费者（DPVS 第 8 课 / tf-vrouter dpdk 壳第 7 课 / vpp / **我们的 PAL 通道**——材料 28 "TCP 127.0.0.1:6666 + protobuf"）；与 Katran/XDP（第 9 课）构成"两条绕开内核的路线"。
> 纪律：路径与行号来自实仓核验；不逐驱动展开（PMD 家族几十个 NIC 不是本课重点）。

---

## 0. 术语速查

| 术语 | 含义 |
|---|---|
| **EAL** | Environment Abstraction Layer：初始化（大页内存/核绑定/PCI 探测）——一切 DPDK 程序的第 0 行 |
| **PMD** | Poll Mode Driver：轮询模式驱动——**没有中断**，应用主动问网卡"有包吗" |
| **mbuf / mempool** | 包缓冲 / 缓冲池：**预分配、DMA 可达**——零拷贝的地基 |
| **rte_ring** | 无锁环形队列：核间传包的通道（无锁 = 按场景选的实现，不是魔法） |
| **hugepage** | 大页内存：TLB 友好 + DMA 可锁——DPDK 的"内存从哪来" |
| **rte_flow** | 通用流 API：把"匹配→动作"下沉到网卡（可编程数据面的统一词汇） |
| **af_xdp** | DPDK 的 AF_XDP PMD——**"用户态框架"跑在"内核 XDP"上**（两条路线互相借道） |

## ① 它干了什么——一个包的两条路径

**内核路径**：网卡收包 → 中断 → NAPI 轮询 → 分配 sk_buff → 协议栈逐层处理 → socket 排队 → 应用 `read()`。

**DPDK 路径**：EAL 初始化（大页 + 核绑定 + 网卡解绑内核驱动、绑到 PMD）→ **`rte_eth_rx_burst` 轮询收包**（拿到预分配的 mbuf，DMA 直达）→ 应用转发逻辑（如 `l3fwd`）→ `rte_eth_tx_burst` 直接发回。**全程无中断、无系统调用、无拷贝**。

一句话：**"把网卡从内核手里接过来，自己当协议栈"**——代价见 ③。

## ② 链路走查（file:line 级）

### 跳 1：仓库地图

- **`lib/`：59 个库**（`ls -d lib/*/ | wc -l` 计数实证）——五大件 `eal / ethdev / mbuf / mempool / ring` ＋ 一屋子"协议栈零件"：`lpm / fib / rib / hash`（查表）、`sched`（QoS）、`vhost`（virtio 后端）、`bpf`（DPDK 自己也有 BPF 库）、`graph / node`（流水线）、`telemetry`…
- **`drivers/net/`：PMD 家族——58 个驱动目录**（计数实证），注意其中有 **`af_xdp`**（见 Q5）；
- **`examples/`**：`l3fwd`（本课的路标）、`helloworld`、`ipsec-secgw`…；
- **`kernel/`**：`linux/` 下**只剩 `uapi`**（find 实证）——而 `freebsd/` 侧仍带 `contigmem` / `nic_uio`（**两个内核驱动目录**）——同一仓库两边的"内核依赖度"已经不同：**Linux 侧的内核模块年代彻底谢幕，BSD 侧尚未跟上**（演化活标本，不展开）。

### 跳 2：五大件的两个"物理事实"

**mbuf**（`lib/mbuf/rte_mbuf.h`，**2113 行**；结构定义在 `rte_mbuf_core.h:475`——`struct __rte_cache_aligned rte_mbuf`，**对齐属性写进声明**，DPDK 的"缓存行即性能"在类型系统里的样子）：`rte_mbuf.h:28` 的注释开门见山——"To understand the concepts of **packet buffers or mbufs**, you…"——**包 = 预分配的 buffer**（不是按需生成的 sk_buff）。

**hugepage**（内存从哪来）：`lib/eal/common/eal_common_memory.c:309`（`ms->hugepage_sz = page_sz`）——DPDK 的内存全从**预映射的大页**里切：TLB 友好、可锁页、**物理地址稳定**——DMA 描述符可以指向它。"零拷贝"不是省一次 memcpy，是**整套"预分配 + 地址稳定"生态**（Q2）。

### 跳 3：rte_ring——"无锁"是选出来的

`lib/ring/rte_ring.h`（842 行）：

```c
 * - Multi- or single-consumer dequeue.          // :24
 *        is "single-consumer". Otherwise, it is "multi-consumers".   // :101
```

**无锁不是魔法，是按生产者/消费者模型选实现**：SP/SC（单产单消）走最轻路径，MP/MC 则要付出原子操作与内存序的代价——`rte_ring_dequeue` 的注释直说 "calls the multi-consumers or the single-consumer" 分支（`:414` 起）。第 8 课 DPVS 的"share-nothing"、第 9 课 Katran 的 "per-CPU LRU"，用的都是同一套纪律。

### 跳 4：编程接口的两代——端口级与流级

**端口级**：`lib/ethdev/rte_ethdev.c`（**7505 行**）的 burst 语义——`rte_eth_rx_burst` / `tx_burst` 一次问"一小把包"（批量本身就是性能设计：摊销轮询与描述符成本）。

**流级**：`lib/ethdev/rte_flow.h:11`："RTE generic flow API"——**把"匹配 → 动作"标准化下沉到网卡**（Q4）。

同一个 `l3fwd` 示例，**四种查表实现**：`examples/l3fwd/main.c:154`（`int (*main_loop)(void *)` 函数指针）→ `em_main_loop` / `lpm_main_loop` / `fib_main_loop` / `acl_main_loop`（`:167-200` 四处注册）——**DPDK 自己不下结论，把"用什么查表"交给你**：它是库，不是系统。

### 跳 5：消费者清单（九课回收）

DPVS（第 8 课，DPDK-LVS）；tf-vrouter 的 dpdk 壳（第 7 课）；vpp；以及——**我们的 PAL 通道**（材料 28："通过 TCP 127.0.0.1:6666 + protobuf 协议与本机 **PAL/DPDK 数据面**通信"）。本课程第一次把"我们"写进 DPDK 的消费者名单：九课里所有"DPDK 通道"字样的谜底，本课收束。

## ③ 设计推导（问题树）

**Q1：为什么轮询替代中断？**
每包一次中断 = 上下文切换 + 缓存污染，PPS 上限被拉死；轮询把"何时处理"的主动权交给应用（批量收：一次 burst 多包）。代价：**独占 CPU 核**——这就是 DPVS 的 per-lcore、Katran 的 per-CPU、tf-vrouter 的 lcore 管理的"上游原因"：**核绑定不是它们的发明，是 DPDK 的前提**。

**Q2："零拷贝"背后的三笔税是什么？**
真正的零拷贝 = "数据不动、描述符动"，前提是**预分配生态**：mbuf 池预分配（地址稳定→DMA 可达）、大页（TLB 友好+锁页）、mempool 无锁（分配免费）。省掉的是每次的"分配/映射/拷贝"三笔税；付掉的是启动期一次性算清内存 + 灵活性（动态大小/内存突增不友好）。

**Q3：rte_ring 的"无锁"标价？**
按 SP/MP × SC/MC 组合选实现：单产单消走最轻路径，多对多要原子与内存序。"无锁"的准确含义是"**没有阻塞锁**"，不是"没有同步成本"。

**Q4：rte_flow 与 eBPF/XDP 是什么关系？**
**同一场"可编程数据面"浪潮的三种方言**：NIC 界的 rte_flow（匹配-动作下沉网卡）、内核界的 tc/XDP/eBPF（第 9 课 Katran）、策略界的 cilium eBPF（第 10 课）——都在把"转发/策略逻辑"从固定流水线变成**可装载的程序/流表**。

**Q5：DPDK vs XDP——两条路线之争如何收束？**
DPDK = **用户态绕开内核重造一切**（性能天花板高、独占核、生态重）；XDP = **让内核本身快起来**（复用栈与工具、verifier 约束、不独占核）。`drivers/net/af_xdp` 的存在是最好注脚：**两条路线在互相借道**（DPDK 应用可以跑在 XDP 上）。选型看约束：极致 PPS/自有协议栈→DPDK；轻量快速/复用内核→XDP；不少系统两者并存（我们：PAL 通道用 DPDK；轻量化时可看 XDP）。

## ④ 对照卡

| 维度 | DPDK | XDP/eBPF（第 9 课） | 内核栈 |
|---|---|---|---|
| 位置 | **用户态独占核** | 内核最早钩子 | 完整协议栈路径 |
| 收包 | PMD 轮询（burst） | 驱动钩子即时触发 | 中断 + NAPI |
| 内存 | 预分配 + hugepage | 内核页 | sk_buff 按需 |
| 编程面 | rte_flow / burst | BPF 程序 | netfilter / tc |
| 代价 | 独占核 + 生态重 | verifier 约束 | 性能上限低 |
| 我们 | **PAL 通道**（材料 28） | （未用） | genetlink / vpcko |

## ⑤ 验收题

1. 轮询替代中断省了什么、必须付什么？说出至少两处受"独占核"约束的下游设计（第 7/8/9 课里找）。
2. "零拷贝"背后真正的三笔税是什么？预分配生态的软肋在哪？
3. rte_ring 的"无锁"为什么按 SP/SC 组合分档？单产单消为什么能最轻？
4. rte_flow / eBPF / cilium 是"同一场浪潮的三种方言"——具体在争什么（一句话）？
5. DPDK vs XDP 的取舍清单？`af_xdp` 目录的存在说明了什么？

---

*本课行号清单（供追问对质）：`lib/ring/rte_ring.h` 多/单消费者注释@24、SC/MC 分档@101、dequeue 分支@414；`lib/mbuf/rte_mbuf.h`@28（概念指针，共 2113 行）+ `rte_mbuf_core.h`（结构定义所在）；`lib/eal/common/eal_common_memory.c` hugepage_sz@309；`lib/ethdev/rte_flow.h`@11；`lib/ethdev/rte_ethdev.c`（7505 行）；`examples/l3fwd/main.c` main_loop 指针@154、四种实现@167-200；目录学：lib/ **59 个**、drivers/net/ **58 个**（计数实证）、`drivers/net/af_xdp`、`lib/vhost/`、`kernel/linux/` 只剩 uapi、`kernel/freebsd/` 带 contigmem/nic_uio（find 实证）。*

*审查记录（首轮深审）：**0 修正课**（第二例）——补强 1 处：kernel 目录从"linux 只剩 uapi"升级为**两侧对照**（freebsd 仍带 contigmem/nic_uio——"内核依赖度两边不同"的演化标本）。复核项：rte_ring.h 24/101/414、rte_mbuf.h:28（+2113 行）、eal_common_memory.c:309、rte_flow.h:11、rte_ethdev.c 7505、l3fwd main.c 154/167-200、lib 59 / drivers 58（计数）、af_xdp/vhost 目录、材料 28 的 PAL 引文——全部命中。*

*审查记录（二次深审）：0 修正课的自审 = 收尾钉实——把 R1 未验穿的"结构在 `rte_mbuf_core.h`"钉到 **`:475`**（`struct __rte_cache_aligned rte_mbuf`——对齐属性写进声明）；R1 的 kernel 两侧对照补强在位。零尾差。*
