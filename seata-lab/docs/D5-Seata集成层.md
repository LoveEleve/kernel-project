# D5 — Seata 集成层

> **定位**：D1~D4 讲了四种模式，本篇讲 Spring Boot 如何将 Seata 嵌入应用——GlobalTransactionScanner / DataSourceProxy / seata-spring-boot-starter 自动装配。
> **深度**：配置模型 + Bean 装配流。

---

## 一、GlobalTransactionScanner：`@GlobalTransactional` 如何生效

`GlobalTransactionScanner` 是 Seata 的 TM（事务管理器）入口——Spring AOP 切面扫描所有 `@GlobalTransactional` 方法并织入全局事务控制。

### 1.1 工作流

```
Spring 启动 → seata-spring-boot-starter 自动装配 GlobalTransactionScanner
   ↓
BeanPostProcessor 阶段：对每个 Bean 扫描方法上的 @GlobalTransactional
   ↓ 存在 → 创建 AOP 代理
   ↓
方法调用时：
  ① 切面拦截 → 检查 RootContext 中是否已有 xid
     无 xid → 向 TC 申请新的全局事务（begin）
     有 xid → 加入现有事务（propagation=REQUIRED）
  ② 执行原方法体
  ③ 方法返回：
     无异常 → 向 TC 发送 COMMIT
     抛异常（rollbackFor 声明的）→ 向 TC 发送 ROLLBACK
  ④ 清理线程 xid（RootContext.unbind）
```

### 1.2 RootContext：xid 的 ThreadLocal 绑定

`RootContext.bind(xid)` 将 xid 绑定到当前线程——所有下游的 Seata Resource Manager（DataSourceProxy / TCC 分支注册）通过 `RootContext.getXID()` 检测当前线程是否在一个全局事务中。RPC 前需要手动解绑 → 重新绑定到目标线程（由 Spring MVC/Feign 拦截器自动处理），D1 已详述。

---

## 二、DataSourceProxy：RM 的自动装配

`seata-spring-boot-starter` 提供了自动包装 DataSource 的机制：

### 2.1 enableAutoDataSourceProxy（默认 true）

```yaml
seata:
  enable-auto-data-source-proxy: true  # 默认开启
```

当 `true` 时，`SeataAutoDataSourceProxyCreator` 作为 BeanPostProcessor 自动扫描所有 DataSource Bean，用 `DataSourceProxy` 包装。**无需手动编码**——本 lab 的 AT 模式 A 类验证已利用此特性。

### 2.2 手动控制包装（excludesForAutoProxying）

```yaml
seata:
  excludes-for-auto-proxying: myNoProxyDataSource
```

排除了某些数据源（如只读库、日志库等不需要 Seata 拦截的场景）。

### 2.3 自动配置类一览

| 类 | 职责 |
|---|---|
| `SeataAutoConfiguration` | 全局事务相关 Bean——GlobalTransactionScanner / FailureHandler |
| `SeataCoreAutoConfiguration` | 核心——加载 application.yml 配置 binding（SeataProperties） |
| `SeataSpringFenceAutoConfiguration` | TCC fence 配置——自动注册 FenceHandler / TCC 方法拦截 |

---

## 三、配置参数速查

### 3.1 必须配置

```yaml
seata:
  tx-service-group: default_tx_group            # 事务分组
  service:
    vgroup-mapping:
      default_tx_group: default                  # 分组→TC 集群
    grouplist:
      default: 21.130.247.89:27091               # TC 地址
```

### 3.2 可选配置

| 参数 | 默认值 | 说明 |
|---|---|---|
| `enabled` | true | 开关 Seata |
| `application-id` | `spring.application.name` | TM 标识 |
| `enable-auto-data-source-proxy` | true | 自动包装 DataSource |
| `excludes-for-auto-proxying` | `{}`（空） | 排除不需要包装的 DataSource 名称 |

### 3.3 AT 客户端事务配置

AT 模式下的超时策略和重试次数：

| 参数 | 默认值 | 说明 |
|---|---|---|
| `client.rm.lock.retryInterval` | 10ms | 全局锁获取重试间隔 |
| `client.rm.lock.retryTimes` | 30 | 最大重试次数 |
| `client.tm.commitRetryCount` | 5 | TM 向 TC 发 COMMIT 的重试次数 |
| `client.tm.rollbackRetryCount` | 5 | TM 向 TC 发 ROLLBACK 的重试次数 |

---

## 四、本 lab 配置示例

实际使用的 seata-lab application.yml（已在 28041 运行验证）：

```yaml
seata:
  enabled: true
  application-id: seata-lab
  tx-service-group: default_tx_group
  service:
    vgroup-mapping:
      default_tx_group: default
    grouplist:
      default: 21.130.247.89:27091
  config:
    type: file
  registry:
    type: file
```

**验证命令**：
```bash
curl http://127.0.0.1:28041/actuator/health  # → {"status":"UP"}
# 查看 Seata 日志确认 TC 连接
grep "seata" /tmp/seata-lab.log
```

---

## 五、本篇要点

1. **GlobalTransactionScanner** 是 TM 的 Spring AOP 入口——扫描 `@GlobalTransactional` 织入全局事务
2. **DataSourceProxy 自动包装**（`enableAutoDataSourceProxy=true`）——不需手动编码
3. **RootContext ThreadLocal** 是 xid 传播的枢纽——所有 RM 通过它判断当前线程是否在全局事务中
4. **本 lab 的 AT 验证**：A01 用 MySQL 业务表（`seata_account`）+ `undo_log` 完成 COMMIT/ROLLBACK 端到端验证——数据源由 `enable-auto-data-source-proxy` 自动包装
