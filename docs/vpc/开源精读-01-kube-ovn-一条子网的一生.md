# 开源精读 01 · kube-ovn：一条子网的一生

> 课次：开源精读路线第 1 课（链①②：控制面写侧 + 分发与 agent）
> 对象：kube-ovn（`refs/kube-ovn`，浅克隆 commit `524119b`）
> 对照：我们的一代链（sdn_oss → nfv-proxy → vpc_agent）
> 纪律：本文所有路径与行号均来自实仓核验（写入前 ls/grep/sed 逐条核过）。

---

## 0. 术语速查（首现必释）

| 术语 | 含义 |
|---|---|
| **CRD / Subnet CR** | K8s 自定义资源。kube-ovn 用 `Subnet` CRD 表达"一个虚拟子网"——用户的声明入口 |
| **OVN NB / SB** | Northbound DB（逻辑网络定义：交换机/路由器/端口/ACL）/ Southbound DB（解析后的运行时状态） |
| **LS / LSP / LRP** | Logical Switch（逻辑交换机≈子网）/ Logical Switch Port（交换机端口）/ Logical Router Port（路由器端口） |
| **ovn-controller / ovn-northd** | **OVN 原生组件**：northd 把 NB 翻译成 SB；ovn-controller（每节点一个）把 SB 翻译成 OVS 流表 |
| **workqueue / informer** | K8s 控制器标准零件：informer 监听 API 变更并喂给本地缓存；workqueue 做速率受限的重试队列 |
| **keymutex** | K8s 工具库里的"按 key 加互斥锁"——同一个 key 串行，不同 key 并行 |

## ① 它干了什么——一个例子

对 K8s 集群 apply 一个 Subnet CR（`subnet-a`，CIDR `10.16.0.0/24`，挂 `ovn-default` VPC）。随后发生三件事（设计上全部幂等、可重放）：

1. **K8s 侧**：`subnet-a` 的 `status` 从空变为 Ready，多出 `LogicalSwitch`、`DHCPv4OptionsUUID` 等字段；
2. **OVN NB 侧**：出现逻辑交换机 `subnet-a`，以及一对"缝合端口"——LSP `subnet-a-ovn-default`（type=router）↔ LRP `ovn-default-subnet-a`（IP=子网网关 `10.16.0.1/24`）；
3. **每个节点的 OVS**：自动长出承载该子网的流表——**kube-ovn 没有参与这一跳**，是 OVN 原生组件干的。

输入是一个声明，输出是"K8s 状态 + OVN 逻辑对象 + 全网节点生效"。

## ② 链路走查（file:line 级）

### 跳 1：进程入口——29 行的薄壳

`cmd/controller/cmdmain.go`（全文）：

```go
package main

import (
	"os"
	"path/filepath"

	"github.com/kubeovn/kube-ovn/cmd/pinger"
	"github.com/kubeovn/kube-ovn/pkg/util"
	"github.com/kubeovn/kube-ovn/pkg/util/profiling"
)

const (
	CmdController = "kube-ovn-controller"
	CmdPinger     = "kube-ovn-pinger"
)

func main() {
	cmd := filepath.Base(os.Args[0])
	switch cmd {
	case CmdController:
		profiling.DumpProfile()
		CmdMain()
	case CmdPinger:
		profiling.DumpProfile()
		pinger.CmdMain()
	default:
		util.LogFatalAndExit(nil, "%s is an unknown command", cmd)
	}
}
```

入口只做一件事：按 `argv[0]` 分发（多二进制复用同一仓库）；随后进入 `CmdMain()`（`cmd/controller/controller.go`）装配组件。仓库根的 `cmd/cmdmain.go` 是**另一个分发站**——按 argv 路由到 6 个子命令（另加 `frr-render` 的 `os.Args[1]` 特判）：

```go
	cmd := filepath.Base(os.Args[0])
	switch cmd {
	case CmdMonitor:
		ovn_monitor.CmdMain()
	case CmdSpeaker:
		speaker.CmdMain()
	case CmdWebhook:
		webhook.CmdMain()
	case CmdOvnLeaderChecker:
		ovn_leader_checker.CmdMain()
	case CmdOvnICController:
		ovn_ic_controller.CmdMain()
	default:
		util.LogFatalAndExit(nil, "%s is an unknown command", cmd)
	}
```

### 跳 2：informer 监听 + 事件入队

`pkg/controller/controller.go:985`：

```go
	if _, err = subnetInformer.Informer().AddEventHandler(cache.ResourceEventHandlerFuncs{
		AddFunc:    controller.enqueueAddSubnet,
		UpdateFunc: controller.enqueueUpdateSubnet,
		DeleteFunc: controller.enqueueDeleteSubnet,
	}); err != nil {
		util.LogFatalAndExit(err, "failed to add subnet event handler")
	}
```

Subnet 的增/改/删分别进 `addOrUpdateSubnetQueue`（`controller.go:157` 定义；`:556` 初始化——`newTypedRateLimitingQueue[string]("AddSubnet", nil)`，**速率受限重试队列**）。

### 跳 3：worker 启动

`pkg/controller/controller.go:1502`（在 `startWorkers` 里）：

```go
	go wait.Until(runWorker("add/update subnet", c.addOrUpdateSubnetQueue, c.handleAddOrUpdateSubnet), time.Second, ctx.Done())
```

`runWorker`（`controller.go:1816`）是通用泵；"稍后再试"的机制在 `processNextWorkItem`（`:1772`）里——错误 → `AddRateLimited`（带指数退避的重入队），成功 → `Forget`：

```go
	err := func(item T) error {
		defer queue.Done(item)
		if err := handler(item); err != nil {
			queue.AddRateLimited(item)
			return fmt.Errorf("error syncing %s %q: %w, requeuing", action, getItemKey(item), err)
		}
		queue.Forget(item)
		return nil
	}(item)
```

**handler 返回 error 不是"终点"，是"稍后再试"的信号**。

### 跳 4：handler 的头 40 行——锁、验证、静默重入队

`pkg/controller/subnet.go:560`：

```go
func (c *Controller) handleAddOrUpdateSubnet(key string) error {
	c.subnetKeyMutex.LockKey(key)
	defer func() { _ = c.subnetKeyMutex.UnlockKey(key) }()

	cachedSubnet, err := c.subnetsLister.Get(key)
	if err != nil {
		if k8serrors.IsNotFound(err) {
			return nil
		}
		klog.Error(err)
		return c.recordSubnetKeyError(key, "GetSubnetFailed", err)
	}
	klog.V(3).Infof("handle add or update subnet %s", cachedSubnet.Name)
	subnet, err := c.formatSubnet(cachedSubnet)
	if err != nil {
		err := fmt.Errorf("failed to format subnet %s, %w", key, err)
		klog.Error(err)
		return c.recordResourceError(cachedSubnet, "FormatSubnetFailed", err)
	}

	err = c.validateSubnetVlan(subnet)
	if err != nil {
		if errors.Is(err, errVlanNotReady) {
			// vlan hasn't been processed yet, requeue silently without
			// marking the subnet as failed
			klog.Infof("deferring subnet %s: %v", key, err)
			return err
		}
		err = fmt.Errorf("failed to validate vlan for subnet %s, %w", key, err)
		klog.Error(err)
		if patchErr := c.patchSubnetStatus(subnet, "ValidateSubnetVlanFailed", err.Error()); patchErr != nil {
			klog.Error(patchErr)
			return c.recordResourceError(subnet, "UpdateStatusFailed", patchErr)
		}
		return err
	}

	if err = util.ValidateSubnet(*subnet); err != nil {
		klog.Errorf("failed to validate subnet %s, %v", subnet.Name, err)
		...
	}
	...
```

三个设计点直接写在代码里：
- **`subnetKeyMutex.LockKey(key)`**——同一子网串行处理（不同子网并行）；
- **`errVlanNotReady` 静默重入队**——依赖未就绪 ≠ 失败，**不能**把 status 标成 error（错误分类纪律）；
- 真失败有两个出口：**status 条件回写**（`patchSubnetStatus`，`subnet_status.go:50`——`SetError/NotReady` + K8s Event）或**仅记 Warning Event 并交队列重试**（`recordResourceError`，`resource_events.go:23`）。

### 跳 5：VPC 维度加锁 + CIDR 冲突检查 + 建逻辑交换机

`pkg/controller/subnet.go:669 prepareOvnSubnet`，关键段（VPC 锁 `:701` 起）：

```go
	needRouter := subnet.Spec.Vlan == "" || subnet.Spec.LogicalGateway ||
		(subnet.Status.U2OInterconnectionIP != "" && subnet.Spec.U2OInterconnection)
	// 1. overlay subnet, should add lrp, lrp ip is subnet gw
	// 2. underlay subnet use logical gw, should add lrp, lrp ip is subnet gw
	randomAllocateGW := !subnet.Spec.LogicalGateway && vpc.Spec.EnableExternal && subnet.Name == c.config.ExternalGatewaySwitch
	// 3. underlay subnet use physical gw, vpc has eip, lrp managed in vpc process, lrp ip is random allocation, not subnet gw
	...
	// Lock VPC to prevent CIDR conflict between concurrent subnet creations in the same VPC
	if err := func() error {
		c.vpcKeyMutex.LockKey(subnet.Spec.Vpc)
		defer func() { _ = c.vpcKeyMutex.UnlockKey(subnet.Spec.Vpc) }()

		if err := c.checkSubnetConflict(subnet); err != nil {
			klog.Errorf("failed to check subnet %s, %v", subnet.Name, err)
			return err
		}
		// create or update logical switch
		if err := c.OVNNbClient.CreateLogicalSwitch(subnet.Name, vpc.Status.Router, subnet.Spec.CIDRBlock, gateway, gatewayMAC, needRouter, randomAllocateGW); err != nil {
			klog.Errorf("create logical switch %s: %v", subnet.Name, err)
			return err
		}
		return nil
	}(); err != nil {
		return nil, c.recordResourceError(subnet, "CreateLogicalSwitchFailed", err)
	}
```

两层锁的语义：`subnetKeyMutex` 防"同一子网并发处理"；`vpcKeyMutex` 防"**同一 VPC 下并发建子网导致 CIDR 冲突**"（先锁 VPC，再查冲突，再建）。注意收尾：失败不是裸返回——`recordResourceError(subnet, "CreateLogicalSwitchFailed", err)` 先落一条 K8s Warning 事件、再把错误交还队列重试。

### 跳 6：NB 对象诞生——逻辑交换机 + 缝合端口对

`pkg/ovs/ovn-nb-logical_switch.go:20`：

```go
func (c *OVNNbClient) CreateLogicalSwitch(lsName, lrName, cidrBlock, gateway, gatewayMAC string, needRouter, randomAllocateGW bool) error {
	lspName := fmt.Sprintf("%s-%s", lsName, lrName)
	lrpName := fmt.Sprintf("%s-%s", lrName, lsName)

	// underlay subnets without a CIDR (e.g. BYO-DHCP / external DHCP) have no
	// router-side networks to configure, so skip address/mask computation
	var switchNetworks string
	if cidrBlock != "" {
		networks, err := util.GetIPAddrWithMask(gateway, cidrBlock)
		if err != nil {
			klog.Errorf("failed to get ip %s with mask %s, %v", gateway, cidrBlock, err)
			return err
		}
		switchNetworks = networks
	}

	exist, err := c.LogicalSwitchExists(lsName)
	if err != nil {
		klog.Error(err)
		return err
	}

	// Reconcile existing patch ports in a single transaction.
	if exist && switchNetworks != "" {
		if needRouter {
			return c.reconcileLogicalSwitchPatchPort(lsName, lrName, lspName, lrpName, switchNetworks, gatewayMAC, randomAllocateGW)
		}
		if randomAllocateGW {
			return nil
		}
	} else {
		if needRouter && switchNetworks != "" {
			if err := c.createLogicalSwitchWithPatchPort(lsName, lrName, switchNetworks, gatewayMAC); err != nil {
				klog.Error(err)
				return err
			}
			return nil
		} else if err := c.CreateBareLogicalSwitch(lsName); err != nil {
			klog.Error(err)
			return fmt.Errorf("create logical switch %s", lsName)
		}
	}
	...
```

同一个文件的缝合端口对（patch port）：路由器侧端口 LRP（`:88`）——

```go
		lrp = &ovnnb.LogicalRouterPort{
			UUID:     ovsclient.NamedUUID(),
			Name:     lrpName,
			Networks: strings.Split(networks, ","),
			MAC:      gatewayMAC,
			ExternalIDs: map[string]string{
				"vendor": util.CniTypeName,
			},
		}
```

交换机侧端口 LSP（`:111`）——

```go
		lsp = &ovnnb.LogicalSwitchPort{
			UUID:      ovsclient.NamedUUID(),
			Name:      lspName,
			Addresses: []string{"router"},
			Type:      "router",
			Options:   map[string]string{"router-port": lrpName},
		}
```

两者最后通过**同一个 OVSDB 事务**落库（`:124`）：

```go
	if err := c.Transact("ls-patch-port-update", ops); err != nil {
		return fmt.Errorf("reconcile logical switch %s patch ports: %w", lsName, err)
```

这解释了"子网网关 `10.16.0.1`"在 OVN 里的真实身份：**不是交换机上的地址，而是 LRP（路由器侧端口）网络**——虚拟机/容器的默认网关指向它。

### 跳 7：DHCP 选项 + status 回写

`pkg/controller/subnet.go`（`updateSubnetDHCPOption`，`:521`）：

```go
func (c *Controller) updateSubnetDHCPOption(subnet *kubeovnv1.Subnet, needRouter bool) error {
	mtu, err := c.getSubnetMTU(subnet)
	if err != nil {
		return err
	}

	vpc, err := c.vpcsLister.Get(subnet.Spec.Vpc)
	...
	var routerPortNames []string
	if needRouter {
		routerPortNames = append(routerPortNames, fmt.Sprintf("%s-%s", vpc.Status.Router, subnet.Name))
	}
	dhcpOptionsUUIDs, err := c.OVNNbClient.UpdateDHCPOptions(subnet, mtu, routerPortNames...)
	if err != nil {
		klog.Errorf("failed to update DHCP and RA options for switch %s: %v", subnet.Name, err)
		return err
	}

	if subnet.Status.DHCPv4OptionsUUID != dhcpOptionsUUIDs.DHCPv4OptionsUUID || subnet.Status.DHCPv6OptionsUUID != dhcpOptionsUUIDs.DHCPv6OptionsUUID {
		subnet.Status.DHCPv4OptionsUUID = dhcpOptionsUUIDs.DHCPv4OptionsUUID
		subnet.Status.DHCPv6OptionsUUID = dhcpOptionsUUIDs.DHCPv6OptionsUUID
		bytes, err := subnet.Status.Bytes()
		...
		if _, err := c.config.KubeOvnClient.KubeovnV1().Subnets().Patch(context.Background(), subnet.Name, types.MergePatchType, bytes, metav1.PatchOptions{}, "status"); err != nil {
			klog.Errorf("patch subnet %s dhcp options failed: %v", subnet.Name, err)
			return err
		}
	}
	return nil
}
```

DHCP 选项在**子网维度**创建/更新（`UpdateDHCPOptions`，`pkg/ovs/ovn-nb-dhcp_options.go:27`），并在 **Pod 的交换机端口**创建时被引用（`UpdateDHCPOptionsForPort`，同文件 `:158`；Pod 侧另有 `DHCPv4Options` 注解链路）——Pod 起身拿到的 IP/网关/DNS 由此而来。**声明翻译 + 状态回写**，与建 LS 一样幂等（UUID 相等则跳过 patch）。

### 跳 8：收尾 reconcile + 节点侧边界

`pkg/controller/subnet.go:771 finishOvnSubnetReconcile` 调 `reconcileSubnet@1026`——逐项收敛子网相关的旁路资源：

```go
func (c *Controller) reconcileSubnet(subnet *kubeovnv1.Subnet) error {
	if err := c.reconcileNamespaces(subnet); err != nil { ... }          // 命名空间过滤
	if err := c.reconcileRouteTableForSubnet(subnet); err != nil { ... } // 自定义路由表
	if subnet.Spec.Vpc == c.config.ClusterRouter {
		... c.reconcileOvnDefaultVpcRoute(subnet)                        // default VPC 的 OVN 静态路由
	} else if err := c.reconcileCustomVpcStaticRoute(subnet); err != nil { ... }
	...
	if err := c.reconcileVlan(subnet); err != nil { ... }                // VLAN 绑定
	if err := c.reconcileVips(subnet); err != nil { ... }                // 虚拟 IP 端口
	return nil
}
```

**节点侧**（kube-ovn daemon，`pkg/daemon/controller.go:733`）：

```go
func (c *Controller) runSubnetWorker() {
	for c.processNextSubnetWorkItem() {
	}
}
...
	err := func(obj *subnetEvent) error {
		defer c.subnetQueue.Done(obj)
		c.requestFdbSync()
```

节点侧对"子网事件"的第一反应是 `requestFdbSync()`——**它不做"建逻辑网络"，主干由 OVN 原生组件负责**；节点只维护本机数据面需要补充的状态（如 FDB）。daemon `Run@1053` 里真正的大头是 gateway/端口/隧道检查（`loopOvn0Check`、`loopTunnelCheck`……），那是下一课对照 vpc_agent 的料。

## ③ 设计推导（问题树）

**Q1：为什么需要 controller 这个"翻译层"？**
Subnet CR 是 K8s 的声明形状（CIDR/网关/VPC 名），OVN 要的是逻辑对象（LS/LRP/DHCP）。两者之间必须有人翻译，且翻译规律要求幂等、可重放——controller 就是这份翻译器。这就是它相对我们 `sdn_oss` 的等价位置（**接口层 + 建模翻译**），但注意：它没有 Saga、没有 Outbox。

**Q2：为什么 kube-ovn 不需要"快照+binlog 两级分发"？**
因为它把分发**整段外包给了 OVN**：NB 写完后，`ovn-northd`（NB→SB）与每节点的 `ovn-controller`（SB→OVS 流表）自带增量通道（底层是 OVSDB 的 RAFT 复制与 monitor 推送），节点侧天生"watch 得动"。而我们一代没有 OVN，且要覆盖 K8s 之外的场景（母机/网关/VM），所以自研了 ZK 树 + 快照 + binlog 三件套。**约束不同，解不同。**

**Q3：不在一个事务里完成，一致性怎么保？**
靠"声明式无限重试"：任何一步失败 → handler 返回 error → workqueue 速率受限地重入队 → 下次 reconcile 从头幂等校正；失败原因进 status 条件（`patchSubnetStatus`）与 K8s 事件（`recordResourceError`）。与我们的对照：Saga 是"一次事务 + 逆序补偿"，对账是"事后报告"。三条路线的代价各不相同（见对照卡）。

**Q4：并发怎么防？**
三级：`subnetKeyMutex`（同子网串行）→ `vpcKeyMutex`（同 VPC 的"查冲突+建"原子）→ 单 active controller 实例（`cmd/controller/controller.go:85` `leaderelection.RunOrDie`——只有 `OnStartedLeading` 回调里才启动 `controller.Run`，丢主即退出，天然单写者）。**内存锁够用的前提是"只有一个写者"**。我们不行——sdn_oss 多实例共享 MySQL/ZK，所以要分布式锁（且吃过 ZK 锁雪崩的亏）。

**Q5：`errVlanNotReady` 为什么要"静默重入队"？**
依赖未就绪不是失败：标 error 会让用户看到假故障，也会污染错误统计。代码区分了"稍后再试"（返回 err，不改 status）与"真失败"（patch status）。这是**错误分类**的纪律，和我们对 `is_vif_ready` 的分类（超时≠未就绪）同构。

**代价小结**：reconcile 可能反复跑（要写幂等码）；keymutex 粒度粗（大 VPC 变更串行）；关键路径的黑盒变多（分发/一致性依赖 OVN 内部行为）。

## ④ 对照卡（vs 我们）

| 维度 | kube-ovn | 我们（一代） | 差异根因 |
|---|---|---|---|
| 声明的 SoT | K8s CRD + OVN NB | MySQL（+ ZK 分发树） | 平台：K8s 原生 vs OpenAPI 控制面 |
| 分发 | **外包 OVN**（NB→SB→ovn-controller→OVSDB monitor） | **自研**（ZK 树 / CKV 快照 / binlog 三件套） | 有没有 OVN；是否要覆盖非 K8s 场景 |
| 变更完成语义 | 收敛式重试（无限重试直到对了） | Saga 一次事务（逆序补偿）+ 对账 | 失败观感与补偿成本的取舍 |
| 并发控制 | 内存 keymutex + 单实例 | 分布式锁（ZK→DB）+ VPC 维度 | 实例数 / 共享存储 |
| 节点侧职责 | 极薄（FDB/gateway 补充） | 极厚（vpc_agent 全量 watch + state + netlink 下发 + Inspector 对账） | 节点侧主干是否被 OVN 托管 |
| 一次写内的原子性 | 只保 NB 事务原子（`Transact`） | 保"业务表 + Outbox"同事务（Saga 步内） | 分发外包后，跨层原子性由谁兜底 |

## ⑤ 验收题（答完写入《开源精读路线》看板）

1. 子网的 SoT 究竟是什么？kube-ovn 为什么不需要"快照+binlog 两级分发"？（提示：谁天生自带增量通道）
2. `prepareOvnSubnet` 的三种形态（needRouter / randomAllocateGW / bare）分别对应什么网络场景？各自装配哪些 OVN 对象？
3. controller 在 `CreateLogicalSwitch` 成功、status 回写前崩溃：收敛过程怎么走？（对照：Saga 的一次性补偿会怎么做、各自代价）
4. `vpcKeyMutex` 防的具体并发场景是什么？为什么 kube-ovn 敢用内存锁而我们不能？
5. daemon 的 `runSubnetWorker` 为什么开头只有 `requestFdbSync()`？承载子网的流表是谁写的？

---

*本课行号清单（供追问对质）：`cmd/controller/cmdmain.go`（29 行全文）；`cmd/cmdmain.go` 六子命令分发；`cmd/controller/controller.go:85` `leaderelection.RunOrDie`；`pkg/controller/controller.go` Run@1200、subnet 事件注册@985、worker 启动@1502、processNextWorkItem@1772、runWorker@1816；`pkg/controller/subnet.go` handleAddOrUpdateSubnet@560、prepareOvnSubnet@669（VPC 锁@701）、updateSubnetDHCPOption@521、finishOvnSubnetReconcile@771、reconcileSubnet@1026；`pkg/controller/subnet_status.go:50` `patchSubnetStatus`；`pkg/controller/resource_events.go:23` `recordResourceError`；`pkg/ovs/ovn-nb-logical_switch.go` CreateLogicalSwitch@20、LRP@88 / LSP@111、Transact@124；`pkg/ovs/ovn-nb-dhcp_options.go` UpdateDHCPOptions@27、UpdateDHCPOptionsForPort@158；daemon：`pkg/daemon/controller.go` runSubnetWorker@733、Run@1053。*

*审查记录（本轮"深审"）：修正 4 处行号偏差（699→701、84→88、126→124、522→521）、1 处引码尾部与实仓不符（`return nil, err` → `recordResourceError(subnet, "CreateLogicalSwitchFailed", err)`）、1 处引用错位（leader election 真实调用点在 `cmd/controller/controller.go:85`，原引 `pkg/controller/config.go:18` 仅为配置结构）、1 处事实误述（"六十余个包"→ 实为 6 个子命令分发）、1 处机制措辞（DHCP 选项挂在 Pod 端口引用链，非"登记到 LS 上"）；另补强 2 处引码（重试泵 `AddRateLimited/Forget`、失败出口二分）。全部回仓核验。*

*审查记录（二次深审 · 欠账清账）：R1 全部修正复核在位（8 处标记逐条 grep 命中：`recordResourceError` 收尾 ×3、VPC 锁@701、LRP@88/LSP@111/事务@124、DHCP@521、`leaderelection@cmd/controller:85`、6 子命令分发、端口引用链措辞）；零尾差、零新错——**第 1 课欠账至此清零**。*
