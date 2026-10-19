---
title: "05-集成层：Scanner与数据源代理"
series: "Seata 分布式事务系列"
previous: "04-saga-xa-selection.md"
next: "06-三方案对比.md（待写）"
lab_refs:
  - "seata-lab/docs/D5-Seata集成层.md"
---

# 05-集成层：Scanner与数据源代理

## §0 它干了什么（例子先行）

引 starter，加 3 行配置，全局事务就通了：

```yaml
seata:
  tx-service-group: default_tx_group          # 事务分组
  service:
    vgroup-mapping: { default_tx_group: default }   # 分组 → TC 集群名
    grouplist: { default: 21.130.247.89:27091 }     # 集群名 → TC 地址
```

启动后发生两件事：`GlobalTransactionScanner` 扫描所有 Bean 的 `@GlobalTransactional` 建代理；所有 DataSource Bean 被自动包成 `DataSourceProxy`——**业务代码零改动**（01 的注解 + 02 的镜像都是这两步装起来的）。

- **它干了什么**：把 01~04 讲的 TM/RM 接进 Spring 生命周期。
- **输入/输出**：输入=starter+配置；输出=注解生效 + 数据源被代理。
- **本篇回答**：Scanner 怎么工作？xid 存哪？数据源怎么被包？配置链怎么读？

## §1 掌握卡（4 条）

1. **Scanner 工作流**：启动建代理 → 调用时查 RootContext：无 xid 向 TC 申请（begin），有 xid 加入（REQUIRED）→ 方法返回无异常 COMMIT / 匹配 rollbackFor 则 ROLLBACK → unbind。
2. **RootContext = xid 的 ThreadLocal**（每线程一份的变量，03 系列讲过）：所有 RM 靠 `getXID()` 判断"当前线程在不在全局事务里"；RPC 拦截器自动解绑/重绑（01 §3 回顾）。
3. **数据源自动包装默认开**：`enable-auto-data-source-proxy=true` → `SeataAutoDataSourceProxyCreator`（BeanPostProcessor）包所有 DataSource；只读库/日志库用 `excludes-for-auto-proxying` 排除。
4. **配置是两跳映射**：`tx-service-group` → `vgroup-mapping`（分组→集群名）→ `grouplist`（集群名→地址）——中间加一层分组，换 TC 集群时不动业务配置。

## §2 Scanner 四步

```
启动：starter 自动装配 Scanner → BeanPostProcessor 阶段扫注解建代理
调用：① 查 RootContext：无 xid→向 TC begin；有 xid→加入现有
     ② 跑原方法体
     ③ 返回：无异常→COMMIT；匹配 rollbackFor→ROLLBACK
     ④ unbind 清线程
```

（01 §4 说"应用级扫描"，这里是它的完整工作流；和 Boot 条件自动配置两回事——这里扫描发生在 Bean 生命周期里。）

## §3 数据源代理：默认自动 + 可排除

| 配置 | 默认 | 干什么 |
|---|---|---|
| `enable-auto-data-source-proxy` | **true** | 自动包 DataSource（02 的镜像拦截靠它，无需手动 `@Bean DataSourceProxy`） |
| `excludes-for-auto-proxying` | 空 | 排除名单（只读库、日志库不该被拦） |

三个自动配置类分工：`SeataAutoConfiguration`（Scanner/FailureHandler）/ `SeataCoreAutoConfiguration`（yml 绑定）/ `SeataSpringFenceAutoConfiguration`（**TCC fence 的装配入口**——03 说 `useTCCFence` 必开，开关背后是这个类在注册 handler）。

## §4 配置速查

必配三段（分组/映射/地址，§0 已示）。可选：`enabled` / `application-id`（默认=应用名）。AT 调优四参：

| 参数 | 默认 | 说明 |
|---|---|---|
| `client.rm.lock.retryInterval` | 10ms | 全局锁重试间隔（02 §3 的 LockRetryController） |
| `client.rm.lock.retryTimes` | 30 | 最大重试次数 |
| `client.tm.commitRetryCount` | 5 | TM 发 COMMIT 重试 |
| `client.tm.rollbackRetryCount` | 5 | TM 发 ROLLBACK 重试 |

lab 实测配置（28041 端口，AT 端到端跑通——从入口 API 到数据库全链路验证过 COMMIT/ROLLBACK）：`config/registry type: file`（本地文件注册，非 Nacos）+ 上面三段。

## §5 边界（一句话翻篇）

- FailureHandler/SeataCore 细节 → `lab D5 §2.3` 用时查。
- RPC 拦截器（Feign/RestTemplate 传 xid）的代码级 → 用时看源码 `TransactionPropagationInterceptor`。
- 注册中心（Nacos 注册 TC）/事务分组高可用 → 用时查官方文档。

## §6 Self-Test（3 题）

- Q1：Scanner 调用时怎么决定"新事务"还是"加入"？（RootContext 查 xid）
- Q2：不想让日志库被 Seata 拦，配什么？
- Q3：配置为什么是两跳映射？换 TC 集群改哪一行？

## §7 衔接

- 使用线收口（05）；下篇 06 三方案对比（Seata vs RocketMQ 事务消息 vs Outbox——D1 §七 + RocketMQ 06 钩子兑现 + dist-theory 回链）。
