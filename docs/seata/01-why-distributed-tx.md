---
title: "01-为什么需要分布式事务"
series: "Seata 分布式事务系列"
previous: "README.md"
next: "02-AT模式.md（待写）"
lab_refs:
  - "seata-lab/docs/D1-分布式事务核心概念.md（§一~§五）"
theory_refs:
  - "dist-theory/03-distributed-transaction-2pc-tcc.md（理论线）"
---

# 01-为什么需要分布式事务

## §0 它干了什么（例子先行）

下单要扣库存（库存库）+ 建订单（订单库），两个库：

```
不保证：扣库存成功 → 建订单失败 → 库存少了订单没有（数据漂移）
保证：  要么都成，要么都回滚（回滚=把已做的反向撤销，如库存扣了就加回去——也叫补偿）

Seata 用法：入口方法加一个注解
  @GlobalTransactional
  public void createOrder(dto){ inventory.deduct(); order.create(); }
  业务代码里没有 begin/commit——框架全管了
```

- **它干了什么**：跨库操作的原子性——一荣俱荣，一损俱损（回滚补偿）。
- **输入/输出**：输入=`@GlobalTransactional` 方法；输出=全成或全回滚。
- **本篇回答**：谁协调（三角色）？xid 怎么传？注解怎么生效？

## §1 掌握卡（3 条）

1. **问题本质**：拆库后单库 ACID 失效——跨服务/跨库重新实现原子性（CAP 视角：XA 走强一致 CP，AT/TCC/SAGA 走最终一致 AP，04 讲全光谱）。
2. **三角色**：TC（协调，独立进程）/ TM（发起，入口注解）/ RM（资源，代理 DB）——xid 是全局事务号，靠 RPC 头传播。
3. **唯一业务感知点 = `@GlobalTransactional`**：切面拦截 → 申请 xid → 绑线程；RPC 头透传 `TX_XID`；业务零传参。

## §2 问题与三角色

单体靠 DB 事务；拆微服务后三个典型翻车：库存扣了订单没写、注册了券没发、转账 A 扣了 B 没入。

| 角色 | 职责 | 关键类 |
|---|---|---|
| **TC** | 维护全局 xid + 分支状态；收 TM 的 begin/commit/rollback；向 RM 下发二阶段决议 | 独立进程（Seata Server，lab 在 docker） |
| **TM** | `@GlobalTransactional` 方法即边界——发起 xid、定提交/回滚 | `GlobalTransactionScanner`（AOP 切面：不改业务代码、在方法前后自动加逻辑的机制） |
| **RM** | 每个被覆盖的 DB 操作；AT 靠 `DataSourceProxy` 生成反向 SQL，TCC 靠业务实现 | `DataSourceProxy` |

## §3 生命周期（6 步）

```
① TM 进注解方法 → Scanner 拦截 → 申请 xid → RootContext.bind（绑当前线程）
② RPC 调库存 → 拦截器传 TX_XID 头 → 下游恢复 xid → 注册分支 branchId
   → 执行 SQL + 记 undo（AT）/ 执行 Try（TCC）
③ RPC 调订单 → 同上
④ 方法返回：无异常→COMMIT，抛异常→ROLLBACK → 切面通知 TC
⑤ TC 下发二阶段：COMMIT（异步删 undo）/ ROLLBACK（跑 undo 补偿）
⑥ RootContext.unbind 清线程
```

**xid 传播**（和 tracing 02 同款问题不同答案）：Seata 拦截器在 HTTP 头透传 `TX_XID`，下游 `RootContext.bind` 恢复——**业务不手动传**（对比 xhs 自研 6 头是业务自己透传，Seata 是框架内置，07 对照）。

## §4 @GlobalTransactional 五参数

| 参数 | 默认 | 说明 |
|---|---|---|
| `timeoutMills` | **60000**（60s） | 超时未决 → TC 强制回滚 |
| `rollbackFor` | 空（只回滚 RuntimeException） | 触发回滚的异常 |
| `noRollbackFor` | 空 | 不触发回滚的异常 |
| `propagation` | REQUIRED | 传播（同 `@Transactional` 家族概念） |
| `name` | ""（空=方法全限定名） | 日志/监控用 |

**生效原理**：`GlobalTransactionScanner`（Spring 的 BeanPostProcessor——Bean 创建时被通知、可改可代理的扩展点）扫描注解 → 建代理 → 事务模板接管——这是**应用级扫描**，和 Boot 的条件自动配置（Tracing 04 讲过）是两回事；装配细节 05 讲。

## §5 边界（一句话翻篇）

- 2PC 为什么阻塞 / CAP 权衡 → 理论线 `dist-theory/03`（不重讲）。
- DataSourceProxy 内部（前/后镜像、反向 SQL）→ 02。
- 四模式对比表 → 04。

## §6 Self-Test（3 题）

- Q1：三角色各干什么？TC 是不是进程内的库？
- Q2：业务代码怎么"零传参"拿到 xid？（注解+头传播+绑线程三连）
- Q3：`rollbackFor` 默认只回滚什么？想让 `Exception` 全回滚怎么配？

## §7 衔接

- 本篇回答"谁协调"；下篇 02 回答 AT 怎么做（一阶段双写 + 全局锁 + 二阶段补偿——Seata 默认模式）。
