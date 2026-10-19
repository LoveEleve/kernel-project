# seata-lab 交接文档（2026-10-05 重写）

> 位置：`/data/workspace/source-code/xhs-demo/seata-lab`
> 版本基线：**Seata 客户端 2.0.0 + TC 2.1.2**（AT 端到端已验证）
> 用途：Seata 分布式事务学习模块（AT/TCC/SAGA/XA）——生产使用 + 核心原理 + 面试题
> 评审记录：`docs/REVIEW-2026-10-05.md`

---

## 一、迁移与范围调整（2026-10-05）

- 自 `source-code/sca-lab/seata-lab` **迁入** xhs-demo；pom 改造：继承 xhs-demo 父 POM、pin `seata-spring-boot-starter 2.0.0`、保留 Central 仓库（seata 不在阿里云镜像，见旧 HANDOVER 踩坑 1）、注册为模块。
- 用户决定：**砍掉 C 类手写**（`c1/C1ManualATCompensation`，自包含无引用）。
- 恢复途径：sca-lab git 历史。

## 二、已完成（5 篇 D 文档 + AT 验证端点）

| 内容 | 状态 |
|---|---|
| D1 分布式事务核心概念（XA/AT/TCC/SAGA 对比 + TC/TM/RM + 配置模型） | ✅ 审校（注解默认值/拦截器/类全核实） |
| D2 AT 模式详解（ExecuteTemplate → 镜像 → undo_log → 全局锁 → 二阶段） | ✅ 审校（修锁键格式） |
| D3 TCC 模式详解（Try/Confirm/Cancel + fence 四态状态机） | ✅ 审校（fence 状态/DDL/方法全对） |
| D4 SAGA 与 XA 模式 | ✅ |
| D5 Seata 集成层（GlobalTransactionScanner + DataSourceProxy 自动装配） | ✅ |
| A01 `A01TransferController`（AT 转账：COMMIT/ROLLBACK 实测） | ✅ 保留 |

## 三、验证状态

- 编译 ✓；引文审计：34 条（27 条为示例，非引文）
- **运行前置**：TC `21.130.247.89:27091`（2.1.2）、MySQL `21.130.247.89:13306`（seata_lab，含 `seata_account` + `undo_log` 表）

## 四、构建与运行

```bash
cd /data/workspace/source-code/xhs-demo
export JAVA_HOME=/env/javaEnv/jdk17
export MAVEN_OPTS="-Xmx512m -XX:MaxMetaspaceSize=256m"
mvn -pl seata-lab -am compile
mvn -pl seata-lab spring-boot:run     # 端口 28041

# AT 转账验证（需 TC + MySQL 可达）
curl http://127.0.0.1:28041/a01/balances                     # A/B 余额
curl "http://127.0.0.1:28041/a01/transfer?amount=50"         # COMMIT 路径
curl "http://127.0.0.1:28041/a01/transfer-rollback?amount=50" # ROLLBACK 路径
```

## 五、审计语料

| 语料 | 位置 |
|---|---|
| **Seata v2.0.0 源码**（评审基线） | `/data/workspace/source-code/code/spring/seata-2.0.0`（浅克隆 v2.0.0） |
| Seata v2.5.0（对照） | `/data/workspace/source-code/code/spring/seata` |

## 六、口径约定

1. 版本基线 **客户端 2.0.0 + TC 2.1.2**；版本差异写注记；
2. 引文逐字；示例必须标注；
3. 坐标以 `seata-2.0.0` 源码为准。

## 七、已知边界

- C 类手写已移除（如需恢复见 sca-lab git）；
- TCC/SAGA 的 A 类端到端验证未做（D3 §六 预告的 `inventoryTcc` 未实现）——如需可补；
- 运行依赖远端 TC/MySQL（21.130.247.89）。
