# nacos-lab 交接文档（2026-10-05 重写）

> 位置：`/data/workspace/source-code/xhs-demo/nacos-lab`
> 版本基线：**Nacos 2.3.0**（对齐 xhs-project：SCA 2023.0.1.2 + nacos-client 2.3.0）
> 用途：Nacos 注册中心 + 配置中心学习模块（生产使用 + 核心原理 + 面试题）
> 索引：`docs/PLAN.md`；评审记录：`docs/REVIEW-2026-10-05.md`

---

## 一、迁移与范围调整（2026-10-05）

- 自 `source-code/sca-lab/nacos-lab` **迁入** xhs-demo；pom 改造：继承 xhs-demo 父 POM、移除未使用的 `sca-lab-common`、**pin `nacos-client 2.3.0`**（覆盖 SCA BOM 默认，与 xhs-project 一致）、注册为模块。
- 用户决定：**砍掉 C 类手写**（`handwritten/` 5 文件 + `docs/C-手写填补/` 3 篇）；控制器/启动类引用自包含，无残留。
- 恢复途径：sca-lab git 历史。

## 二、已完成（35 篇文档 + 24 源文件）

| 类 | 数量 | 状态 |
|---|---|---|
| A 生产使用 | 16 篇 | ✅ 概念级审校（修 3：A-02 优先级语义 / A-07 MySQL 属性 / A-09 Boot3 前缀） |
| B 扩展点 | 5 篇 | ✅ 审校（修 3：B-01 代理名 / B-04 措辞 / B-05 `AuthPluginService`） |
| D 源码解读 | 14 篇 | ✅ 审校（修 16：D-01 含跨文档矛盾 2、D-03/D-05/D-08/D-10 等） |
| 收束篇 | D-14 面试模拟 18 题 | ✅ 采用为收束（含分级路线图） |

**概念级评审总计：35 篇全部审完，22 处修复 + 3 组跨文档矛盾**（简单取模/JRaft 存储/CP-AP 路由点）。

## 三、验证状态

- 编译 ✓（24 个源文件；无测试目录）
- 全量引文审计（语料 = Nacos 2.3.0 + SCA + spring-cloud-commons + lab）：**1682 引文 / 1181 命中**——未命中为格式/版本噪声（非编造；编造扫描 163 个类名仅 10 个未命中且多为误报）

## 四、构建与运行

```bash
cd /data/workspace/source-code/xhs-demo
export JAVA_HOME=/env/javaEnv/jdk17
export MAVEN_OPTS="-Xmx512m -XX:MaxMetaspaceSize=256m"
mvn -pl nacos-lab -am compile
# 运行（需要 Nacos 服务；application.yml 指向远端 Nacos 21.130.247.89:18848，namespace sca-lab-dev）
mvn -pl nacos-lab spring-boot:run
```

## 五、审计语料

| 语料 | 位置 |
|---|---|
| **Nacos 2.3.0 全仓库源码** | `/data/workspace/source-code/code/spring/nacos-2.3.0`（浅克隆 tag 2.3.0，39M，持久路径） |
| SCA（2025.x HEAD，对照） | `/data/workspace/source-code/code/spring/spring-cloud-alibaba` |
| spring-cloud-commons / spring-boot | `/data/workspace/source-code/code/spring/spring-cloud-commons`、`spring-boot` |
| lab 源码 | `src/` |

## 六、口径约定

1. 版本基线 **Nacos 2.3.0**；版本差异写"版本差异"注记（如 D-01 的 Distro 取模、A-09 的 Boot3 前缀）；
2. 引文逐字（容忍 reflow/行尾注释/FQN 缩写）；截断显式 `...`；
3. 坐标格式（仓库相对路径 + 行号）以 2.3.0 源码为准；
4. 示例/实验代码必须标注。

## 七、已知边界

- C 类手写已移除（如需恢复见 sca-lab git）；
- 运行依赖远端 Nacos（21.130.247.89:18848）；集群实验为架构理解性内容，未实际搭建集群；
- SCA 侧集成代码（NacosServiceRegistry/NacosLoadBalancer 等）按工作区 2025.x 源码对照，与项目 2023.0.1.2 存在版本差异（B 类文档已注明关键差异点）。
