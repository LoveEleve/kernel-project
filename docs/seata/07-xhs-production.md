---
title: "07-xhs生产对照：自研TCC与fence实锤"
series: "Seata 分布式事务系列"
previous: "06-three-way-comparison.md"
next: "08-面试收口.md（待写）"
lab_refs:
  - "docs/中间件拷打/K06-Seata.md（生产陷阱 7 条 + 项目证据）"
source_refs:
  - "xhs common/tcc/TccFenceService.java（177 行：三态返回 + 乐观锁状态转换）"
  - "xhs inventory InventoryTccService.java:35-75（fence 先行模式）"
  - "xhs inventory TccTimeoutJob.java:18-51（xid 维度超时取消 + 旧实现三缺陷）"
---

# 07-xhs生产对照：自研TCC与fence实锤

## §0 它干了什么（例子先行）

秒杀扣库存要"冻结→确认/解冻"，xhs 的每一步**先过 fence 再动业务**：

```
Try：    INSERT t_tcc_fence(status=1) 成功 → 冻结库存
         插入冲突→查状态：3=Cancel已来(悬挂，拒) / 其他=重复(跳过)
Confirm： UPDATE ... SET status=2 WHERE status=1（乐观锁，改 1 行才真扣）
Cancel：  UPDATE ... SET status=3 WHERE status=1 → 解冻
超时兜底：TccTimeoutJob 每分钟扫冻结明细，按 xid 取消（走 fence 1→3）
```

- **它干了什么**：把 03 讲的 fence 状态机**手写了一遍**——没引 Seata，借其解法。
- **输入/输出**：输入=重试/乱序的 TCC 调用；输出=三防齐活，双冻结/超卖不可能。
- **本篇回答**：为什么自研？和 Seata fence 差在哪？超时 Job 治什么病？

## §1 掌握卡（4 条）

1. **xhs 不用 Seata 运行时**（K06 原话"借其解法"）：选型理由是"TCC 侵入太大 / AT 全局锁瓶颈"（选型文档原文）——自研 `TccFenceService`（177 行）+ 明细表，零 Seata 依赖。
2. **三态返回 + 关键约束**：每方法返回 EXECUTE/SKIP/REJECTED 类结果——**调用方必须按返回值跳过业务**，否则双冻结/双扣/双解冻（类注释原话）。
3. **乐观锁状态转换**：`UPDATE ... WHERE status=1` 改 1 行才执行业务——防并发双 Confirm（K06 架构师加分项原话）。
4. **超时取消按 xid 维度**：旧实现按 SKU 聚合一刀切有三缺陷，新实现按冻结明细逐条 + Redisson 锁 + fence 状态转换。

## §2 选型：为什么自研

| 方案 | xhs 评价（选型文档原文） |
|---|---|
| Seata AT | "全局锁瓶颈"——热点行抢不过 |
| Seata TCC | "侵入太大"（要引 TC + 客户端） |
| **自研 TCC + fence** | 借 Seata fence 思想，手写 177 行，零依赖 |

和 06 选型反面清单互为镜像：06 说"别为分布式而分布式"，xhs 是"能不上 TC 就不上"——**业务只要冻结/确认/解冻三语义，一张表自己够**。

## §3 TccFenceService vs Seata fence（03 对照）

| 维度 | Seata（lab 03） | xhs 自研 |
|---|---|---|
| 表 | `tcc_fence_log` 4 态（1/2/3/**4=SUSPENDED**） | `t_tcc_fence` **3 态**（1=Try/2=Confirm/3=Cancel） |
| 空回滚占位 | INSERT status=4 | Cancel INSERT status=3（Try 见 3 拒绝=悬挂判定） |
| 幂等 | 状态查表跳过 | 返回 DUPLICATE/SKIP，**调用方负责跳业务** |
| 并发防重 | fence handler 内部 | `UPDATE WHERE status=1`（乐观锁，改 1 行才算数） |
| 装配 | `useTCCFence=true` 自动 | `ObjectProvider` 懒解析 + fail-closed（fail-closed=拿不到 JdbcTemplate 直接抛——**宁可失败也不悄悄放行**，源码注释"在使用处 fail-closed"） |

三态数字不同、思想同源：**一条 (xid, branch_id) 记录 + 单向状态机**（03 §3 的"防悬挂精髓=INSERT+唯一约束"完全平移）。

## §4 fence 先行模式（InventoryTccService）

```java
// tryDeductStock 骨架（:35-42）
TryFenceResult r = tccFenceService.tryFence(xid, branchId, "tryDeductStock");
if (r == SUSPENDED) return false;   // 悬挂：拒
if (r == DUPLICATE) return true;    // 幂等：跳过业务（返回值示意，精确逻辑以源码为准）
// FIRST → 才真正冻结库存
```

Confirm/Cancel 同款：先 `confirmFence`（1→2）/`cancelFence`（1→3）状态转换成功才动库存。**模式记忆：fence 前置，业务后置，结果枚举分三岔**。

## §5 TccTimeoutJob：超时兜底

TC 挂了/网络断了，Try 冻结的库存谁解？——Job 每 60s 扫 `t_tcc_freeze_detail`（xid 维度冻结明细），超时记录按 xid 逐个走 fence 1→3 取消 + Redisson 锁防多实例并发。

旧实现三缺陷（类注释原话，反面教材）：聚合冻结无 xid 维度（一刀切回退别单的）/ `updated_at` 被刷新导致热 SKU 永远不超时 / **回退不写 fence → 迟到 Confirm 把已回退的再 confirm → 超卖**。第三条正是 03 "防悬挂"在真实生产翻车的样子。

## §6 K06 生产陷阱 7 条（收口清单）

| # | 陷阱 | 一句话 |
|---|---|---|
| 1 | AT 全局锁热点 | 热点行别 AT，改 TCC/异步 |
| 2 | TCC 不开 fence | 生产必开（xhs 自研也是 fence-first） |
| 3 | 三方法不幂等 | 重试必然发生，状态机+业务键双保险 |
| 4 | 空回滚当异常 | 是正常场景，"插记录+跳过"不是报错 |
| 5 | undo_log 忘建/忘清 | AT 每库都要建，定期清理 |
| 6 | 本地锁键找错 | RM `表:主键` vs TC `res^^^table^^^pk`（02 §2） |
| 7 | 无脑上强一致 | 多数业务最终一致+幂等+对账够了 |

## §7 边界（一句话翻篇）

- `t_tcc_freeze_detail` DDL/选型文档全文 → K06 项目证据段，用时查。
- TC 存储模式（file/db/redis）/对账四模式 → K06 发散追问 + dist-consistency。
- XXL-Job 补偿体系 → 非本系列。

## §8 Self-Test（4 题）

- Q1：xhs 为什么不上 Seata？（两评语：侵入大 + 全局锁瓶颈）
- Q2：xhs fence 三态和 Seata 四态差在哪？（SUSPENDED 编号 4 vs 3）
- Q3："关键约束"原话说什么？违反了会怎样？
- Q4：旧超时实现第三缺陷是什么现象？治它的两味药？（fence 状态转换 + xid 维度）

## §9 衔接

- 生产线收口；下篇 08 面试收口（K06 五段式 + 追问 + 终检）。
