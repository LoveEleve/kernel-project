# Nacos 学习模块 — 总索引（2026-10-05 更新）

> 位置：`/data/workspace/source-code/xhs-demo/nacos-lab`（自 sca-lab 迁入）
> 版本基线：**Nacos 2.3.0**（对齐 xhs-project：SCA 2023.0.1.2 + nacos-client 2.3.0）
> 范围（用户决定）：**生产使用 + 核心原理 + 面试题**；**C 类手写已移除**
> 评审记录：`docs/REVIEW-2026-10-05.md`（35 篇概念级审完，22 处修复 + 3 组跨文档矛盾）

---

## 一、生产使用（A 类，16 篇）

| 目录 | 内容 |
|---|---|
| A-01 服务注册与发现 | 实例生命周期（临时/持久）、客户端缓存与故障转移、优雅下线 |
| A-02 配置中心 | 三维模型（namespace/group/dataId）、共享配置、spring.config.import 详解 |
| A-03 动态刷新 | 刷新链路详解（gRPC 推送 + 5s 兜底 + 3min 全量）、@RefreshScope vs @ConfigurationProperties |
| A-04 健康检查 | 心跳与自保护（双层健康检查、保护阈值 `<=`） |
| A-05 命名空间与安全 | 环境隔离策略、两层鉴权（AuthFilter/TpsControl/ParamCheck） |
| A-06 配置灰度与回滚 | beta 发布（betaIps）、配置历史与回滚、配置加密 |
| A-07 集群部署 | 三层端口架构、Naming vs Config 同步对比、容量规划、滚动升级 |
| A-08 元数据管理 | metadata 流转与生产用法 |
| A-09 Prometheus SD | nacos_sd_configs + relabel + Pushgateway（Boot 3 属性前缀） |
| A-10 Bootstrap 废弃 | bootstrap.yml → spring.config.import 迁移 |

## 二、扩展点（B 类，5 篇）

| 编号 | 内容 |
|---|---|
| B-01 | 自实现 ServiceRegistry（Spring Cloud 层） |
| B-02 | 自定义配置监听（替代 @RefreshScope 的精确刷新） |
| B-03 | 自定义 LoadBalancer（MetadataBasedLoadBalancer） |
| B-04 | 自定义 NamingService（包装模式 Decorator） |
| B-05 | 自定义 AuthPluginService（Nacos 鉴权 SPI） |

## 三、源码深度解读（D 类，14 篇，全部已审校 ✅）

### Phase 1: API 契约层 (2 docs)
| 编号 | 主题 |
|---|---|
| D-01 | NamingService API + Instance/ServiceInfo 数据模型 |
| D-02 | ConfigService API 接口设计 |

### Phase 2: 客户端 SDK 层 (2 docs)
| 编号 | 主题 |
|---|---|
| D-03 | NacosNamingService SDK 全链路 — 注册/发现/订阅/注销 |
| D-04 | NacosConfigService SDK + ClientWorker gRPC 监听 |

### Phase 3: 服务端基础设施 (2 docs)
| 编号 | 主题 |
|---|---|
| D-05 | gRPC 连接模型 — GrpcServer + ConnectionManager + RequestHandler |
| D-06 | Client 模型 + 数据存储设计 |

### Phase 4: 服务端命名服务核心 (2 docs)
| 编号 | 主题 |
|---|---|
| D-07 | 注册处理链 + 事件驱动索引系统 |
| D-08 | Push 推送体系 + 健康检查 + 故障自愈 |

### Phase 5: 数据一致性 (2 docs)
| 编号 | 主题 |
|---|---|
| D-09 | Distro V2 协议（AP） |
| D-10 | CP/AP 双模式路由 + JRaft 整合 |

### Phase 6: 配置中心 (2 docs)
| 编号 | 主题 |
|---|---|
| D-11 | 配置发布/查询/存储 + 变更通知（两级事件） |
| D-12 | AsyncNotifyService + 集群配置同步 |

### Phase 7: 设计全景 + 面试模拟 (2 docs)
| 编号 | 主题 |
|---|---|
| D-13 | 注册中心设计全景 + 集群管理 |
| D-14 | **面试模拟 18 题**（收束篇：6 组题 + 分级路线图） |

---

## 四、口径约定（评审确立）

1. 版本基线 **Nacos 2.3.0**；版本差异写"版本差异"注记；
2. 引文逐字（容忍 reflow/行尾注释）；截断显式 `...`；
3. 坐标以 2.3.0 源码为准（仓库相对路径）；
4. 示例/实验代码必须标注，不与源码引文混排。
