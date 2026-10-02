# Tomcat 面试单题详解（index）

> **视角：嵌入式 Tomcat**（Spring Boot 场景）——所有题目默认从"Spring Boot 内嵌使用"出发，不考独立 Tomcat 的运维配置。
> 位置：`source-code/xhs-demo/docs/tomcat/`（与 `tomcat-lab` 配套，随学随跑）。
> 配套规划（书仓库）：`/data/workspace/knowledage/openjdk-book/docs/openjdk/vol-arch/vol-training-camp/Tomcat源码学习范围规划.md`（T-1 ~ T-6）。
> 版本基线：**Tomcat 10.1.34**（`/data/workspace/source-code/code/spring/tomcat`）+ **Spring Boot 3.5.16**（`/data/workspace/source-code/code/spring/spring-boot`）。
> 用法：按编号深读 → 用文末"自测要求/实战练习"验收。
> 配套实验：`tomcat-lab`（同仓库，`../tomcat-lab`；8 个测试类：23 绿 + 2 个 JDK21 专用；5 个一键脚本）。对应关系：
> 01~06 用 `/slow`、`/inflight` 与启动/停机日志观察；07→`GracefulShutdownTest` + `graceful-shutdown-demo.sh`；08→`AsyncServletTest`；09→`TuningTest` + `close-wait-demo.sh`；10→`CustomizationTest`；11→`VirtualThreadsTest`（JDK21）；12→`DiagnosticsTest`；13→`KubernetesLabTest` + `k8s-jvm-demo.sh`；14→`MigrationLabTest`。
> 状态：**14/14 完成并冻结**（2026-09-24；01~10 经全局 Round 3、11~14 经全局 Round 4）。
> 关联系列：**Filter vs Interceptor vs AOP** 属 Spring MVC 域（本卷 03 篇已标注边界与源码锚点），待 Spring MVC 面试系列开工时列为 01 题。

## 题库索引（14 题：01~10 主线 + 11~14 2026 扩充；另有 00 前置速览）

> **学习入口（推荐）**：`学习清单.md` + `lessons/L01~`（教学课，概念课不掺源码/行号）。
> 本目录其余文件为**面试话术产出物**：学完对应课后回来校验，不提前读。
> （旧的 00 组件速览已被 `lessons/L01` 取代，保留仅作对照。）

| 编号 | 题目 | 级别 | 文件 | 状态 |
|---|---|---|---|---|
| 00 | **前置速览：内嵌 Tomcat 组件地图（非面试题）** | — | `00-tomcat-components.md` | ✅ 完成 |
| 01 | 内嵌 Tomcat 的启动与装配（Spring Boot 如何拉起 Tomcat） | S | `01-embedded-tomcat-startup.md` | ✅ 完成 |
| 02 | 内嵌 Tomcat 线程模型（Acceptor/Poller/Worker 与线程池调参） | S | `02-tomcat-thread-model.md` | ✅ 完成 |
| 03 | 一个 HTTP 请求的完整旅程（Connector→Adapter→Mapper→Valve→Filter→Servlet） | S | `03-request-journey.md` | ✅ 完成 |
| 04 | Mapper 路由（四级匹配与热更新） | A | `04-mapper-routing.md` | ✅ 完成 |
| 05 | Lifecycle 生命周期与容器启停 | A | `05-lifecycle.md` | ✅ 完成 |
| 06 | 内嵌 vs 独立 Tomcat（类加载器 / 部署形态 / 启动方式） | A | `06-embedded-vs-standalone.md` | ✅ 完成 |
| 07 | 优雅停机（GracefulShutdown） | A | `07-graceful-shutdown.md` | ✅ 完成 |
| 08 | 异步 Servlet（AsyncContext / 状态机 / 超时与错误） | B | `08-async-servlet.md` | ✅ 完成 |
| 09 | 调参与故障排查（acceptCount / maxConnections / CLOSE_WAIT / 线程池满） | B | `09-tuning-troubleshooting.md` | ✅ 完成 |
| 10 | 替换与定制 Tomcat（Customizer / Valve / 换容器 SPI） | B | `10-customization-and-replacement.md` | ✅ 完成 |
| 11 | 虚拟线程与 Tomcat（JDK21 / Boot 3.2+） | S | `11-virtual-threads.md` | ✅ 完成 |
| 12 | 线上排查实战（CPU 100% / 内存泄漏 / 请求 hang） | A | `12-production-troubleshooting.md` | ✅ 完成 |
| 13 | K8s / 云原生下的 Tomcat | A | `13-kubernetes-tomcat.md` | ✅ 完成 |
| 14 | Boot 2→3 / jakarta 迁移 | B | `14-jakarta-migration.md` | ✅ 完成 |

> 级别说明：S = 必答且区分度高；A = 高频进阶；B = 加分/排障向。
> 11~14 为 **2026 口径扩充**（虚拟线程 / 排查实战 / 云原生 / 迁移），与 01~10 同一 16 段模板与审查流程。

## 单题文档统一结构（写作模板）

1. 问题（难度 / 频率 / 区分度 / 关键词）
2. 30 秒简洁回答
3. 展开回答（高级开发级）
4. 进一步回答（架构师层级）
5. 理解与复述提示（学习使用，面试时不要直接念）
6. 必记关键词
7. 必须明确的边界
8. 常见错误
9. 自测要求
10. 追问与参考回答（3 个）
11. 示例与使用说明（可复现骨架）
12. 面试官评分点（通过标准 / 加分项 / 危险信号）
13. 实战练习
14. 版本与来源（Tomcat + Spring Boot 双侧 `file:line`）
15. 个人经历填充区（留空，按真实项目补）
16. 真实性说明

## 约定

- **双侧引用**：凡涉及 Spring Boot 集成的结论，必须同时给 Spring Boot 侧与 Tomcat 侧的类/行号；
- **嵌入式前提**：默认没有 server.xml / webapps（独立部署的配置体系）；涉及独立部署差异时明确标注；
- **"个人经历填充区"必须来自真实项目**，技术结论可以直接学，经历不能虚构；
- 新题补录时：先加进本索引，再按模板写文件。
