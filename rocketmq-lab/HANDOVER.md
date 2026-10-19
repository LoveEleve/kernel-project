# rocketmq-lab 交接文档（2026-10-05 重写）

> 位置：`/data/workspace/source-code/xhs-demo/rocketmq-lab`
> 版本基线：**rocketmq-spring 2.3.0 + rocketmq-client 5.1.4**（对齐 xhs-project）
> 用途：RocketMQ 5.x 消息学习模块（发送/消费/顺序/延时/广播/事务消息）——生产使用 + 核心原理 + 面试题
> 评审记录：`docs/REVIEW-2026-10-05.md`

---

## 一、迁移与范围调整（2026-10-05）

- 自 `source-code/sca-lab/rocketmq-lab` **迁入** xhs-demo；pom 改造：继承 xhs-demo 父 POM、pin `rocketmq-spring-boot-starter 2.3.0`（传递 client 5.1.4）、注册为模块。
- 用户决定：**砍掉 C 类手写**（C1 简易存储 / C2 幂等+重试 / C3 Rebalance 算法，共 7 文件；a/b 类无引用）。
- 恢复途径：sca-lab git 历史。
- **版本重定基**：文档/锚点从 5.3.1+2.3.1 → **5.1.4+2.3.0**（运行时对齐）。

## 二、已完成（9 篇 D 文档 + A/B 验证代码）

| 内容 | 状态 |
|---|---|
| D1 核心概念与整体架构（330 行，六类组件/双 msgId/存储三件套/可靠性开关） | ✅ 审校（17 处修） |
| D2 NameServer 路由发现（六张表/注册/剔除/查询/AP 后果） | ✅ 审校（10 处修） |
| D3 Producer 发送机制（三种发送/队列选择/重试/发送歧义） | ✅ 审校（21 处修） |
| D4 Broker 存储机制（三类文件/分发/刷盘/复制/恢复/清理） | ✅ 审校（15 处修） |
| D5 Consumer 消费机制（长轮询/Rebalance/ProcessQueue/失败处理/Pop） | ✅ 审校（11 处修，含 18 级延时纠错） |
| D6 事务消息（半消息/回查/OP batch/与 Outbox/Seata 对比） | ✅ 审校（10 处修） |
| D7 延时顺序广播（18 级/时间轮/三层锁/广播全分） | ✅ 审校（17 处修） |
| D8 rocketmq-spring 集成（Template/Listener 容器/事务桥） | ✅ 审校（17 处修，删编造注解） |
| D9 自动配置 + 5.x Proxy（绑定链路/Proxy 双协议/双 starter 对比） | ✅ 审校（10 处修） |
| A 类 10 控制器（A01-A10，14 文件；A08 不存在，批量=A02） | ✅ 保留（sca-lab 已 curl 实测） |
| B 类 5 扩展点（B01-B05，5 文件） | ✅ 保留 |

## 三、验证状态

- 编译 ✓；评审共修 **128 处**（锚点重定位 + 概念错误）
- **运行前置**：NameServer `21.130.247.89:9876`（broker-a 单 Master，`21.130.247.89:10911`）

## 四、构建与运行

```bash
cd /data/workspace/source-code/xhs-demo
export JAVA_HOME=/env/javaEnv/jdk17
export MAVEN_OPTS="-Xmx512m -XX:MaxMetaspaceSize=256m"
mvn -pl rocketmq-lab compile
mvn -pl rocketmq-lab spring-boot:run     # 端口 28040

# 验证（需 NameServer 可达）
curl http://127.0.0.1:28040/actuator/health
curl http://127.0.0.1:28040/a01/sync            # 同步发送
curl http://127.0.0.1:28040/a06/send            # 事务消息
```

**A06 事务消息配置坑**（sca-lab 踩坑记录）：`TransactionMQProducer extends DefaultMQProducer` → starter 的 `@ConditionalOnMissingBean` 会跳过 auto-config → 必须手动提供 `@Primary RocketMQTemplate + labProducer`（见 `a/A06TransactionConfig.java`）；labProducer 不要手动 `start()`（Template 的 afterPropertiesSet 会自动启动）。

## 五、审计语料

| 语料 | 位置 |
|---|---|
| **RocketMQ core 5.1.4**（评审基线） | `/data/workspace/source-code/code/spring/rocketmq-5.1.4`（worktree，tag `rocketmq-all-5.1.4`） |
| **rocketmq-spring 2.3.0**（D8/D9 基线） | `/data/workspace/source-code/code/spring/extra/rocketmq-spring-2.3.0`（worktree，tag `rocketmq-spring-all-2.3.0`） |
| RocketMQ core 5.3.2（旧语料，对照） | `/data/workspace/source-code/code/spring/rocketmq` |

## 六、口径约定

1. 版本基线 **rocketmq-spring 2.3.0 + client 5.1.4**（= xhs-project）；文档锚点以此为准；
2. 引文逐字；示例必须标注；
3. 环境为远端单 Master（无 Slave）——主从/同步复制场景无法实测。

## 七、已知边界

- C 类手写已移除（如需恢复见 sca-lab git）；
- 顺序消费/广播/Pop 等 A 类端点保留（sca-lab 实测过，本轮未重跑）；
- 环境无 Slave/Controller，HA 相关场景仅文档级。
