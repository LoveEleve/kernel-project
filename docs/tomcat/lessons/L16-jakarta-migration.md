# Lesson 16: Boot 2→3 / jakarta 迁移

> 课程索引：`../学习清单.md` → Stage 4 · Lesson 16（收官）
> 版本口径：Tomcat **10.1.34**（Servlet 6.0 / jakarta）；Spring Boot **3.5.16**；迁移对照方为 Boot 2.7 / Tomcat 9.0（公开版本对应）
> 来源：迁移工具包说明（`spring-boot-properties-migrator`）；lab 实测（`MigrationLabTest`）；版本矩阵为公开版本对应
> 状态：✅ 已讲（2026-09-29）
> **提问方式：直接说编号**，如「L16-4 没懂」

---

## 0. 本课定位

| 项 | 内容 |
|---|---|
| 目标 | 回答：版本矩阵是什么、包名迁移影响哪四类、迁移步骤与工具、隐形坑在哪 |
| 前置 | L01（组件；版本引用：Servlet 6.0）、L04（Filter） |
| 不涉及 | 逐项依赖升级清单（因项目而异）；WAR 部署运维细节 |
| 下一步 | Stage 5 产出：`architecture-map` + 面试话术校验（现有 14 篇） |

---

## 1. 🔴 要掌握的 3 条（结论层，需要能复述）

1. **版本矩阵**：Boot 2.7 = JDK8+ / Spring 5.3 / Tomcat 9.0（Servlet 4.0，`javax`）；Boot 3.x = **JDK17+** / Spring 6 / Tomcat **10.1**（Servlet **6.0**，`jakarta`）。换的是"**坐标**"（包名），方法签名基本不变。
2. **影响面四类**：**代码**（Java EE 包全换 `javax.`→`jakarta.`，但 `javax.sql/naming/management` 等 JDK SE 包**不动**）/ **依赖**（老库只有 javax 版，混入即运行期炸弹）/ **属性**（改名**静默失效**，如 `server.max-http-header-size`→`server.max-http-request-header-size`）/ **部署**（独立部署要换 Tomcat 10 + web.xml 6.0）。
3. **三件工具 + 验证闭环**：`dependency:tree`（抓旧 servlet-api）/ `spring-boot-properties-migrator`（扫改名属性）/ `jakartaee-migration`（WAR 字节码迁移）。**"能启动"不等于"迁移完成"**——要全量回归 + 灰度。

---

## 2. 依据

### 2.1 为什么换名（背景一句话）

Java EE 捐给 Eclipse 基金会后**商标不能带走**，包名不能沿用 `javax`——于是整体换命名空间（EE9 起；Servlet 6.0 随 EE10/Tomcat 10.1）。

### 2.2 迁移工具包（`spring-boot-properties-migrator`）

> Support for migrating legacy Spring Boot properties.

**翻译**：支持迁移旧版 Spring Boot 属性。

- 要点：把它加为依赖后启动一次，会**报告**改名/删除的属性——是对付"静默失效"的官方手段（`PropertyMigration` / `PropertiesMigrationReporter` 类）。

### 2.3 版本矩阵

| | Boot 2.7 | Boot 3.x |
|---|---|---|
| JDK | 8+ | **17+** |
| Spring | 5.3 | 6.x（Jakarta EE 9+ 基线） |
| 内嵌 Tomcat | 9.0（Servlet 4.0，javax） | **10.1**（Servlet **6.0**，jakarta） |
| 命名空间 | `javax.servlet.*` | `jakarta.servlet.*` |
| 同族包 | `javax.annotation/validation/persistence/transaction/websocket…` | `jakarta.*` 全系 |

> Tomcat 10.0 起换名（Servlet 5.0/EE9）；10.1 是 Servlet 6.0/EE10。

---

## 3. 影响面四类（带例子）

| 类别 | 变化 | 例子 |
|---|---|---|
| 代码 | Java EE 包全换 | `javax.servlet.Filter` → `jakarta.servlet.Filter`；`FilterRegistrationBean<Filter>` 泛型随之换 |
| **不动** | JDK SE 包 | `javax.sql.*`、`javax.naming.*`、`javax.management.*`、`javax.crypto.*`——**无脑全局替换会改错** |
| 依赖 | 老库只有 javax 版 | 如旧版安全/上传组件 → 运行期 `NoClassDefFoundError`（用 dependency:tree 抓） |
| 属性 | 改名静默失效 | `server.max-http-header-size` → `server.max-http-request-header-size`（旧名不报错、行为回默认） |
| 部署 | 独立要换容器 + web.xml | Tomcat 10 + web.xml 6.0 命名空间；内嵌无运维动作 |

**最隐蔽的坑**：`javax.annotation.PostConstruct` 没换 → 编译通过、运行不生效（Bean 不初始化）。

---

## 4. 迁移步骤（可执行清单）

1. **先升 JDK 17**（Boot 3 硬门槛）；
2. 升 Boot 3 BOM（顺手排除旧 `javax.servlet-api` 传递）；
3. 全局替换 EE 包（IDE/OpenRewrite，逐个包确认，**别碰 JDK SE 包**）；
4. 第三方库升到 jakarta 版本（`dependency:tree` 查残留 `tomcat-embed 9` / `servlet-api`）；
5. 加 `spring-boot-properties-migrator` 启动一遍，按警告改属性；
6. 反射/字符串/XML 里的旧类名人工搜（`"javax.servlet`、`Class.forName`、web.xml schema）；
7. 回归重点：Filter/Servlet/Listener/WebSocket/文件上传/会话/cookie；
8. 压测对比 + 灰度（Tomcat 10.1 对非法请求更严格、cookie 默认值有变）。

---

## 5. lab 实测（`MigrationLabTest`）

```text
jakarta Filter（FilterRegistrationBean<jakarta.servlet.Filter>）→ X-Lab-Filter=jakarta，200
--server.max-http-request-header-size=1KB + 2KB 头 → 400；对照 200
```

> 第一个用例同时证明：`jakarta.servlet.Filter` 在 Boot 3 下编译运行正常（javax 写法编不过）。

---

## 6. 术语对照（本课汇总）

| 术语 | 中文 | 一句话 |
|---|---|---|
| javax → jakarta | 包名迁移 | EE9 起的命名空间变更 |
| Servlet 6.0 | — | Tomcat 10.1 实现的规范版本 |
| properties-migrator | 属性迁移器 | 扫描改名/删除的属性 |
| jakartaee-migration | 迁移工具 | WAR 字节码级转换 |
| dependency:tree | 依赖树 | 抓传递依赖里的旧 servlet-api |
| FilterRegistrationBean | 过滤器注册 Bean | 注册 URL/顺序/dispatcher 的入口 |

---

## 7. Self-Test（参考答案直接给出）

**Q1. Boot 2 升 3，版本矩阵怎么变？**
> 参考答案：JDK8+→17+、Spring 5.3→6、Tomcat 9.0（Servlet 4.0/javax）→10.1（Servlet 6.0/jakarta）。换的是包名"坐标"，API 签名基本不变。

**Q2. 哪些 `javax.` 不能换？换错了会怎样？**
> 参考答案：`javax.sql/naming/management/crypto/net` 等 JDK SE 包不动；无脑全局替换会改错（编译错误或行为异常）。

**Q3. 属性改名为什么危险？怎么发现？**
> 参考答案：旧名**不报错、静默失效**（行为回默认值）。用 `spring-boot-properties-migrator` 启动一遍，按警告改。

**Q4. 内嵌升级和独立部署升级有什么区别？**
> 参考答案：内嵌：BOM 升级即换 Tomcat，成本全在应用代码/依赖；独立：要换 Tomcat 10 安装、web.xml 命名空间（6.0）、共享库与存量 WAR 逐个迁移（或上 `jakartaee-migration`）。

**Q5. "能启动"为什么不等于"迁移完成"？**
> 参考答案：注解不生效（PostConstruct）、属性静默、依赖冲突、反射/字符串引用——都要靠"工具扫描 + 全量回归 + 灰度"兜底。

---

## 8. 本课状态与下一步

- 状态：✅ 已讲（2026-09-29）
- 实验（可选）：跑 `MigrationLabTest`；在自己项目里 grep `javax.servlet` / `javax.annotation` 列迁移清单。
- **Stage 4（L13~L16）全部完成**。下一步：**Stage 5 产出**——`architecture-map`（组件图 + 请求流转 + 速记卡）+ 用现有 14 篇做面试话术校验。
