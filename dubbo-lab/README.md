# dubbo-lab · Dubbo 学习实验沙箱

> 目的：**所有 Dubbo demo 集中一处**——每篇 Dubbo 文章要验证的机制，都在这里有一个可跑、可改、可观察的最小样例。
> 版本基线：**Dubbo 3.3.3**（与 `20-Dubbo源码分析规划.md` 的源码 tag `dubbo-3.3.3` 对齐）/ **Java 17**（与 `xhs-project` 对齐）。
> 刻意采用 **API 风格启动**（`DubboBootstrap` 显式装配），不走 Spring Boot 自动配置——先看清核心链路，Spring 集成放到支撑域。

## 目录结构

```
dubbo-lab/
├── pom.xml            # 父 POM：Dubbo 3.3.3 + Java 17
├── docker-compose.yml # Nacos 2.2.3（注册中心实验用；已有运行实例可直接复用）
├── lab-naive/         # 手写最简 RPC（第 01 篇：代理+序列化+Socket，零依赖）
├── lab-api/           # 共享接口与模型（DemoService / User）
├── lab-provider/      # 提供者（ProviderApp + DemoServiceImpl）
├── lab-consumer/      # 消费者（ConsumerApp）
├── scripts/           # 10 个一键实验脚本（见"实验脚本一览"）
├── tools/             # wire-proxy.py（TCP 抓包）/ qos-cmd.py（QoS telnet 客户端）
└── evidence/          # 每个脚本的证据日志（NN-*.log）
```

## 运行（第一阶段：直连模式，零外部依赖）

```bash
# 0) 一次性：安装模块到本地仓库（本机默认 java 21，需显式用 jdk17）
export JAVA_HOME=/env/javaEnv/jdk17
mvn -DskipTests install

# 方式一：一键脚本（推荐，自带清理与证据）——全部 10 个脚本见下方"实验脚本一览"
bash scripts/02-direct-invoke.sh

# 方式二：手动两步
# 1) 起 provider（阻塞，占 20880 端口）
mvn -q -pl lab-provider exec:exec
# 2) 另开终端：跑 consumer（直连 dubbo://127.0.0.1:20880）
mvn -q -pl lab-consumer exec:exec
```

已验证输出（evidence/02-direct-invoke.log）：

```
[lab-consumer] sayHello = hello dubbo-lab from lab-provider
[lab-consumer] getUser  = User{id=1, name='user-1'}
[lab-consumer] done
provider 已停止，20880 已释放
```

> 实现说明：用 `exec:exec`（独立 JVM）而不是 `exec:java`——后者与 Dubbo 停机钩子的类加载器不兼容（会出现 `ExecutorUtil` CNFE）；
> 两个 App 默认关闭 QoS（22222 端口），consumer 超时可配（`-Dlab.timeout.ref=3000`，默认 3000ms，沙箱冷启动可能 >1s）。

## 运行（第二阶段：Nacos 注册中心，文章"注册中心与服务发现"用）

```bash
docker compose up -d                       # 或复用已有 nacos 容器
mvn -q -pl lab-provider -am exec:exec -Dlab.registry=nacos://127.0.0.1:8848
mvn -q -pl lab-consumer exec:exec -Dlab.registry=nacos://127.0.0.1:8848
```

## 实验脚本一览

| 脚本 | 篇 | 说明 | 依赖 |
|---|---|---|---|
| `scripts/01-naive-rpc.sh` | 01 | 手写最简 RPC（零第三方依赖） | 无 |
| `scripts/02-direct-invoke.sh` | 02 | 直连调用（dubbo://127.0.0.1:20880） | 无 |
| `scripts/03-timeout-precedence.sh` | 02 | 超时三级覆盖对照（4 场景） | 无 |
| `scripts/04-registry-nacos.sh` | 03 | 注册/订阅与上下线通知 | Nacos |
| `scripts/05-spi-extension.sh` | 04 | SPI 自定义 Filter 生效 | 无 |
| `scripts/06-call-chain-lb.sh` | 05 | 调用链 Filter + 多实例 LB 对照 | Nacos |
| `scripts/07-protocol-async.sh` | 06 | 抓包（wire-proxy）+ 异步 + timeout 真相 | 无 |
| `scripts/08-failover-graceful.sh` | 07 | 重试放大/业务异常/优雅下线/标签路由 | 标签路由段需 Nacos |
| `scripts/09-governance-qos.sh` | 08 | RpcContext/泛化/缓存/QoS/线程池 | QoS 段需 Nacos |
| `scripts/10-full-journey.sh` | 09 | 一次调用端到端 trace | 无 |
| `scripts/11-routing-lb-group.sh` | 10 | 条件路由 + 最少活跃 + 服务分组 | Nacos 段 |
| `scripts/12-triple-protocol.sh` | 11 | 双协议共存 + Triple HTTP/2 抓包 | 无 |

## 与文章系列的映射（从0开始 · 问题驱动，详见 docs/dubbo/00-系列规划.md）

| 篇 | 核心问题 | 覆盖域 | lab 扩展点 |
|---|---|---|---|
| 01 | RPC 为什么存在？手写最简 RPC 会死在哪？ | 预备 | **新增 `lab-naive`**（手写 RPC） |
| 02 | 怎么用？为什么超时不生效？ | D-1 | 直连实验 + 超时覆盖对照 |
| 03 | 服务怎么发布和订阅？ | D-4 | Nacos 注册、kill provider 看通知 |
| 04 | 为什么处处可扩展？ | D-2 | 自定义 Filter 扩展（LB 用内置对照） |
| 05 | 一次调用经过了什么？ | D-3/D-5 | 调用链 Filter、多 provider LB 对照 |
| 06 | 请求如何变成网络字节？ | D-6/D-7 | wire-proxy 抓包、timeout 实验 |
| 07 | 服务不可用怎么办？ | D-5 | retries 放大、优雅下线、标签路由 |
| 08 | 生产治理与诊断 | D-8+支撑 | RpcContext/泛化/缓存/QoS/线程池 |
| 09 | 收束：完整旅程 + 设计哲学 | 综合 | 全链路 trace、与 Feign 对照 |
| 10 | 集群深化：条件路由/最少活跃/分组 | D-5 补全 | 直连负面对照 + Nacos 路由 + leastactive |
| 11 | 协议扩展与 Triple | D-6 支撑 | 双协议共存 + HTTP/2 抓包 |

## 约定

- 所有 demo 都进本 lab（api/provider/consumer 三个模块内按包区分），不散落到其它目录；
- provider/consumer 入口保持单一（`ProviderApp`/`ConsumerApp`），实验差异通过系统属性（`-Dlab.*`）或新增 demo 类切换；
- 每个实验结束必须能优雅停机（kill provider 后端口释放），便于脚本化；
- **`-Dlab.*` 参数要显式转发**：`exec:exec` 是独立 JVM，Maven 的 `-D` 不会自动传入——需在模块 pom 的 `<arguments>` 里加 `-Dlab.xxx=${lab.xxx}`（默认值写在 `<properties>`），再用 `-Dlab.xxx=...` 覆盖。
