# openfeign-lab 交接文档（2026-10-05 重写）

> 位置：`/data/workspace/source-code/xhs-demo/openfeign-lab`
> 版本基线：**Spring Cloud OpenFeign 4.1.3（Cloud 2023.0.3）+ SCA 2023.0.1.2**（xhs-project 口径）
> 本文件记录已完成/未完成事项与审计口径；学习路线以 `../docs/学习计划-业务分布式微服务.md` 为准。

---

## 一、迁移与版本基线（2026-10-05）

- 自 `source-code/sca-lab/openfeign-lab` **迁入** xhs-demo；pom 改造：继承 xhs-demo 父 POM、移除未使用的 `sca-lab-common`、注册为模块。
- 文档原基线为 4.3.2（Cloud 2025.0.2）；本轮**全部重校到 4.1.3 + SCA 2023.0.1.2**，4.3.2 差异以"版本差异"注记保留。

## 二、已完成

### A 类：官方主流功能（9 篇，已 4.1.3 重校）
| 编号 | 内容 | 代码 | 测试 | 文档 |
|:----:|------|:----:|:----:|:----:|
| A-01 | 声明式调用 | ✅ | ✅ | ✅ |
| A-02 | 请求拦截器 | ✅ | ✅ | ✅ |
| A-03 | 超时配置 | ✅ | ✅ | ✅ |
| A-04 | HTTP 客户端切换 | ✅ | ✅ | ✅ |
| A-05 | 压缩与序列化 | ✅ | ✅ | ✅ |
| A-06 | 熔断(Sentinel) | ✅ | ✅ | ✅ |
| A-07 | 负载均衡 | ✅ | ✅ | ✅ |
| A-08 | 多版本 API | ✅ | ✅ | ✅ |
| A-09 | RestClient 对比 | ✅ | ✅ | ✅ |

### B 类：扩展点自定义（保留 B-02~B-05，1 篇）
| 编号 | 内容 | 代码 | 测试 | 文档 |
|:----:|------|:----:|:----:|:----:|
| B-02 | 自定义 InvocationHandlerFactory | ✅ | ✅ 4 个 | ✅ |
| B-03 | 自定义 Contract | ✅ | ✅ 4 个 | ✅ |
| B-04 | 自定义 Encoder/Decoder | ✅ | ✅ 7+6 个 | ✅ |
| B-05 | 自定义 ErrorDecoder | ✅ | ✅ 8 个 | ✅ |

### D 类：源码解读（7 篇，全部审校 + 编造段清除）
| 编号 | 内容 | 状态（引文复核） |
|:----:|------|:----:|
| D1 | FeignClientFactoryBean 创建机制 | ✅ 314/314 |
| D2 | FeignClientsRegistrar 注册机制 | ✅ 347/340（6 条已核对 reflow/示例） |
| D3 | FeignBlockingLoadBalancerClient | ✅ 345/345 |
| D4 | SpringEncoder/SpringDecoder | ✅ 293/293 |
| D5 | FeignAutoConfiguration + FeignClientsConfiguration | ✅ 286/286 |
| D6 | Targeter + SentinelFeign | ✅ 216/216 |
| D7 | FeignClientProperties 配置优先级 | ✅ 17/17 |

### 收束与审计工件
- `docs/Z-面试题速答.md`：速记卡 + 七组 37 题 + 陷阱 15 连 + 文档索引（184 行）
- `docs/REVIEW-2026-10-05.md`：评审发现（F1 混版/F2 编造/F3 错引/F4 元数据错）、修复进度、口径约定、逐篇深审记录

## 三、已移除（2026-10-05 用户决定：聚焦生产使用 + 核心原理 + 面试题）

- 手写三件套：`B-01-手写Feign代理.md`、`C-02~C-03-手写扩展.md`、`src/.../handwritten/`（37 文件）、`src/test/.../handwritten/`（4 类 20 用例）
- 控制器 7 个手写端点、启动类 `@MyFeignClients` 注解与相关 import
- `RestClientExample` 迁至 `com.scalab.openfeign.restclient`（A-09 保留）
- 恢复途径：sca-lab git 历史（迁移前版本）

## 四、验证状态

- 编译 ✓ / 测试 **28/28**（custom 包；decorator 20 用例随手写移除）
- 全量引文审计（19 篇文档，语料 = 4.1.3 + 4.3.2 + SCA 2023.0.1.2 + feign-core 13.3 + lab）：
  **2032 引文 / 2012 命中 / 20 残留**（全部为已核对的标注示例/reflow）

## 五、构建、测试与运行

```bash
# 构建 + 测试（从 xhs-demo 根）
cd /data/workspace/source-code/xhs-demo
export JAVA_HOME=/env/javaEnv/jdk17
export MAVEN_OPTS="-Xmx512m -XX:MaxMetaspaceSize=256m"   # 建议加内存帽
mvn -pl openfeign-lab test

# A 类运行时联调（需 nacos-lab + Nacos 服务；lab 配置指向远端 Nacos 21.130.247.89:18848）
# 1) nacos-lab（仍在 sca-lab）
cd /data/workspace/source-code/sca-lab && mvn -pl nacos-lab spring-boot:run
# 2) openfeign-lab
cd /data/workspace/source-code/xhs-demo && mvn -pl openfeign-lab spring-boot:run
```

## 六、审计语料（复核引文时使用）

| 语料 | 来源 |
|------|------|
| OpenFeign 4.1.3 主源码 | m2 `spring-cloud-openfeign-core-4.1.3-sources.jar` |
| OpenFeign 4.3.2 主源码（对照） | `/data/workspace/source-code/code/spring/spring-cloud-openfeign`（checkout） |
| SCA 2023.0.1.2 | m2 `spring-cloud-starter-alibaba-sentinel-2023.0.1.2-sources.jar` |
| feign-core 13.3 | m2 `feign-core-13.3-sources.jar` |
| 4.1.3 测试文件 | GitHub raw（spring-cloud-openfeign tag v4.1.3）+ SCA 本地仓库测试 |

## 七、口径约定（后续修改必须遵守）

1. **版本基线 4.1.3 + SCA 2023.0.1.2**；4.3.2 差异只写在"版本差异"注记里；
2. 引文逐字（容忍换行重排/行尾注释/FQN 缩写）；截断必须显式 `...`；
3. 坐标格式 `文件.java:行号`（以 4.1.3 源码为准）；
4. 示例代码必须标注"示例/简化"，不得与源码引文混排。

## 八、已知边界

- 手写内容已移除（如需恢复见 sca-lab git）；
- A 类运行时依赖 nacos-lab（跨仓库）+ 远端 Nacos；
- 4.3.2 新增能力（`FeignHttpMessageConverters`、`FeignOAuth2Properties`、`SpringDataConfiguration`、getUrl 尾斜杠处理等）未纳入正文，仅在版本差异注记中说明。
