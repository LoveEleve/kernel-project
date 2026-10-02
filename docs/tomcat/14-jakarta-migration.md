# 面试题详解 14 | Boot 2→3 / jakarta 迁移（内嵌 Tomcat 视角）

> 难度：★★★★☆ ｜ 频率：★★★★★ ｜ 区分度：高（大版本升级实战题：一处命名空间变更，牵动全工程）
> 关键词：javax→jakarta、Servlet 6.0、Tomcat 10.1、Spring 6、JDK17、属性改名、properties-migrator、jakartaee-migration

---

## 问题

**Spring Boot 2 升 Boot 3（Tomcat 9 升 10.1）到底改了什么？`javax.servlet` → `jakarta.servlet` 的影响面有多大？内嵌和独立部署升级有什么区别？最容易踩的坑有哪些？**

---

## 30 秒简洁回答

> 核心是 **Java EE → Jakarta EE 的包名迁移**：`javax.servlet.*`（以及 annotation/validation/persistence/transaction/websocket…）整体换成 `jakarta.*`——**Tomcat 10 起只认 jakarta**（Servlet 5.0/EE9 起），Boot 3 强制 **Java 17+ / Spring 6 / Tomcat 10.1（Servlet 6.0）**。内嵌场景升级 = 换依赖 + 改 import（代码里 EE 的 `javax.` 全换 `jakarta.`，但 `javax.sql/naming/management` 等 **JDK SE 包不动**）+ 第三方库换 jakarta 兼容版；独立部署还多一步：**换 Tomcat 10 + web.xml 命名空间（4.0→6.0）**。两个隐形杀手：**属性改名静默失效**（如 `server.max-http-header-size` → `server.max-http-request-header-size`，不迁移器不报错）和**依赖树里混入旧 `javax.servlet-api`**（排查用 `mvn dependency:tree`）。

---

## 展开回答（高级开发级）

### 1）版本矩阵与"谁在变"

| | Boot 2.7 | Boot 3.x |
|---|---|---|
| JDK | 8+ | **17+** |
| Spring | 5.3 | 6.x（Jakarta EE 9+ 基线） |
| 内嵌 Tomcat | 9.0（Servlet 4.0，**javax**） | **10.1**（Servlet **6.0**，**jakarta**；10.0=Servlet 5.0/EE9 起换名） |
| 命名空间 | `javax.servlet.*` | `jakarta.servlet.*` |
| 配置 | `server.max-http-header-size` 等 | **改名**：`server.max-http-request-header-size`（`ServerProperties:111`）等一批 |

> 「为什么叫 jakarta」：Java EE 由 Oracle 捐给 Eclipse 基金会但**商标不能带走**，包名不能沿用 javax，只能整体换命名空间（EE9）；EE10 再叠加 Servlet 6.0。

### 2）影响面清单（对照着扫）

| 类别 | 变化 | 备注 |
|---|---|---|
| Servlet/Filter/Listener | `javax.servlet.(http.)?` → `jakarta.servlet.(http.)?` | 最常见；lab 用 `FilterRegistrationBean<Filter>` + `jakarta.servlet.Filter` 演示 |
| 注解 | `javax.annotation.{PostConstruct,Resource}` → `jakarta.annotation.*` | 常漏：编译过、运行不生效（Bean 不初始化） |
| 校验 | `javax.validation.*` → `jakarta.validation.*`（Bean Validation 3.0） | Hibernate Validator 7/8 |
| 持久化 | `javax.persistence.*` → `jakarta.persistence.*`（JPA 3.0） | Hibernate ORM 6 |
| 事务 | `javax.transaction.*` → `jakarta.transaction.*` | |
| WebSocket/EL/Mail/JAXB/JAX-RS | 同上换 `jakarta.*` | 老库（shiro/springfox 等）常卡这 |
| **不换** | `javax.sql.*`、`javax.naming.*`、`javax.management.*`、`javax.crypto.*` | 这些是 **JDK SE 包**，别一起替换 |
| 属性 | `server.max-http-header-size`→`...request...`、`spring.redis.*`→`spring.data.redis.*` 等 | **静默失效**，用 `spring-boot-properties-migrator` 扫 |
| 独立部署 | Tomcat 10 + web.xml `version="6.0"` 命名空间更新 | 内嵌一般不看 web.xml；WAR 部署必须改 |

### 3）内嵌 vs 独立：升级成本差在哪

```
内嵌（Boot）：换 BOM → 改 import → 修第三方依赖 → 回归。容器随 starter 一起换，无运维动作。
独立：换 Tomcat 10 安装 → web.xml/context.xml 命名空间与 schema 更新 →
      共享库（$CATALINA_HOME/lib）里 javax 库要么换要么隔离 → 存量 WAR 逐个验证
      （可用 jakartaee-migration 工具做字节码级转换，但反射/字符串引用仍需人工核）
```

### 4）迁移步骤（可执行清单）

1. **先升 JDK 17**（Boot 3 硬门槛）→ 2. 升 Boot 3 BOM（顺手排除旧 `javax.servlet-api`）→ 3. **全局替换 EE 的 `javax.` → `jakarta.`**（用 IDE/OpenRewrite，逐个包确认，别碰 JDK SE 包）→ 4. 第三方库升到 jakarta 版本（`dependency:tree` 查残留 tomcat-embed 9 / servlet-api）→ 5. 加 `spring-boot-properties-migrator` 启动一遍，按警告改属性 → 6. 反射/字符串/XML 里的旧类名人工搜 → 7. 回归重点（Filter/Servlet/Listener/WebSocket/文件上传/会话/cookie）→ 8. 压测对比（Tomcat 10.1 与 9 的性能/严格度差异）。

### 5）高频坑（按"隐蔽程度"排序）

| 坑 | 症状 | 定位 |
|---|---|---|
| `javax.annotation.PostConstruct` 没换 | 编译过、运行不生效 | 搜 `javax.annotation` |
| 依赖树混入旧 servlet-api | `NoClassDefFoundError: javax/servlet/...` 或 ClassCastException | `mvn dependency:tree -Dincludes=javax.servlet` |
| 属性改名被忽略 | 行为回到默认（如 header 限制变回 8KB） | properties-migrator / 启动日志 |
| 反射/字符串写死旧类名 | 启动或运行期 ClassNotFound | grep `"javax.servlet` |
| Tomcat 10.1 严格度（RFC 7230 非法头、cookie 默认 RFC 6265） | 老客户端/网关请求被 400 | 抓包 + `rejectIllegalHeader`/cookie 处理器 |
| WAR 的 web.xml 还是 4.0 | 部署报 schema 错 | 更新到 6.0 命名空间 |

---

## 进一步回答（架构师层级）

- **迁移的本质是"坐标迁移"而非"API 变更"**：方法签名几乎不变，难在**生态协同**（每个依赖都要有 jakarta 版本）；评估工作量=第三方库清单而不是代码量；
- **双轨期策略**：内部公共库可以先出 jakarta 版；老系统用 Tomcat 9 与 10 并存，网关分流，逐系统切换；
- **静默失效比编译错误更危险**：属性改名、`@PostConstruct` 不生效、依赖冲突——一律靠"工具扫描 + 回归用例"而不是"能启动就算迁移成功"；
- **行为差异要有压测兜底**：Tomcat 10.1 对非法请求更严格、cookie/SameSite 默认值变化（10/13 篇）、`maxHttpRequestHeaderSize` 语义（09 篇）——全链路回归 + 灰度；
- **与 11/13 篇联动**：迁到 Boot 3.2+ 才能开虚拟线程（11 篇）；新部署形态的参数（MaxRAMPercentage/探针）顺手一起上（13 篇）。

---

## 理解与复述提示（学习使用，面试时不要直接念）

- **本质**：考"大版本升级的工程方法论"——版本矩阵、影响面清单、隐形坑、验证闭环；
- **回答顺序**：为什么换名 → 版本矩阵 → 影响面（代码/依赖/配置/部署）→ 步骤清单 → 坑与验证；
- **一句话收束**："javax→jakarta 是坐标迁移：代码全量换包名、依赖全量换生态、属性要工具扫、行为要回归测——能启动不等于迁移完成。"

## 必记关键词

`javax→jakarta` ｜ `Servlet 6.0` ｜ `Tomcat 10.1` ｜ `Spring 6` ｜ `JDK17` ｜ `javax.annotation.PostConstruct` ｜ `dependency:tree` ｜ `spring-boot-properties-migrator` ｜ `OpenRewrite` ｜ `jakartaee-migration` ｜ `web.xml 6.0`

## 必须明确的边界

- **只换 Java EE 包**：`javax.sql/naming/management/crypto/net` 等 JDK SE 包**不动**；
- Tomcat 10.0 就已换名（Servlet 5.0）；10.1 是 Servlet 6.0——面试别说"10.1 才开始换"；
- 属性改名**不报错**（回归默认值），必须主动扫；
- 内嵌升级无运维动作，独立部署要换容器与 web.xml；
- `jakartaee-migration` 只搬字节码/字符串映射，反射与配置仍需人工核。

## 常见错误

- 全局无脑替换 `javax.` 把 `javax.sql` 也换了；
- 以为"编译通过=迁移完成"（漏注解/属性/反射）；
- 依赖树里旧 servlet-api 没排（运行期才炸）；
- 独立部署只换 WAR 不换 Tomcat/web.xml；
- 不压测不灰度直接全量。

## 自测要求

- **30 秒**：版本矩阵 + 影响面四类 + 两个隐形杀手；
- **3 分钟**：能讲步骤清单与坑的定位手法（含行号/命令）；
- 能回答追问 1~3。

---

## 追问与参考回答

**追问 1：Boot 2 升 3 最大的风险是什么？**
> 不是 API（签名基本没变），而是**生态与隐形失效**：老依赖只有 javax 版（classpath 混用 → 运行期炸弹）、属性改名静默失效、`javax.annotation` 注解不生效。控制手段：依赖树审计 + properties-migrator + 全量回归。

**追问 2：内嵌升级和独立部署升级有什么区别？**
> 内嵌：BOM 升级即换 Tomcat（10.1），成本全在应用代码/依赖；独立：要换 Tomcat 10 安装、web.xml 命名空间（6.0）、共享库与存量 WAR 逐个迁移（或上 `jakartaee-migration`），运维面更大。

**追问 3：怎么快速找出项目里所有 javax 引用？**
> 三步：① IDE/OpenRewrite 全量替换 Java EE 包（保留 JDK SE 包）；② `mvn dependency:tree` 抓传递依赖里的旧 servlet-api/tomcat-embed 9；③ grep 字符串/反射/XML（`"javax.servlet`、`Class.forName`、web.xml schema）。

---

## 示例与使用说明（lab 复现，`xhs-demo/tomcat-lab`）

```java
// Boot 3（jakarta）里的标准写法：Filter 与注册
public class TimingFilter implements jakarta.servlet.Filter {          // javax.servlet.Filter 在 Boot 3 编不过
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        ((HttpServletResponse) response).setHeader("X-Lab-Filter", "jakarta");
        chain.doFilter(request, response);
    }
}
@Bean
FilterRegistrationBean<TimingFilter> labFilter() {                     // 泛型必须是 jakarta.servlet.Filter
    FilterRegistrationBean<TimingFilter> registration = new FilterRegistrationBean<>(new TimingFilter());
    registration.addUrlPatterns("/*");
    return registration;
}
```

lab 实测（2026-09-24）：

```text
jakarta Filter 注册运行        → 响应头 X-Lab-Filter=jakarta，请求 200
--server.max-http-request-header-size=1KB + 2KB 头 → 400（超限被拒）
对照（不带大头）               → 200
```

> 属性演示点：Boot 2 时代它叫 `server.max-http-header-size`（改名后旧名静默失效）；Tomcat 侧由 `Http11Processor:167` 把 `getMaxHttpRequestHeaderSize()` 传给 `Http11InputBuffer` 执行。

## 面试官评分点

**通过标准**
- 版本矩阵与包名迁移范围（含"JDK SE 包不动"）；
- 影响面四类（代码/依赖/属性/部署）；
- 知道"编译过≠迁移完成"。

**加分项**
- 能讲 `dependency:tree`/properties-migrator/OpenRewrite/jakartaee-migration 的工具链；
- 能讲 10.0（Servlet 5.0）与 10.1（Servlet 6.0）的区别；
- 有真实迁移项目经验（坑、灰度、压测数据）。

**危险信号**
- 无脑替换 javax；只说"改 import"；忽略属性与依赖树；不回归不灰度。

## 实战练习

1. 跑 `MigrationLabTest`（jakarta Filter + header 超限 400）；
2. 在自己项目里 grep `javax.servlet` / `javax.annotation` 各多少处，列迁移清单；
3. 用 `mvn dependency:tree` 找出旧 servlet-api 传递依赖（如果有）。

## 版本与来源

- Spring Boot **3.5.16**：`autoconfigure/web/ServerProperties.java`（`maxHttpRequestHeaderSize` :111）、`autoconfigure/web/embedded/TomcatWebServerFactoryCustomizer.java`（:107-110/:268-269）；Boot 2 的旧属性名（`server.max-http-header-size`）为迁移对照口径（Boot 2 源码未收录）；
- Tomcat **10.1.34**：`coyote/http11/Http11Processor.java`（:167 传入 `getMaxHttpRequestHeaderSize()`）、`AbstractHttp11Protocol.java`（`maxHttpHeaderSize :274` / `maxHttpRequestHeaderSize :288`）、`LocalStrings.properties:61`（头过大文案）；Servlet 包名迁移（EE9/EE10）为规范事实；
- lab：`xhs-demo/tomcat-lab`（`MigrationLabTest`；实测 400/200）；
- 本卷章节：13（Servlet 契约边界）、15（类加载器泄漏）；
- 规划：`Tomcat源码学习范围规划.md` T-1/T-6。

## 个人经历填充区（留空，按真实项目补）

- 做过 Boot 2→3 迁移吗？踩过哪些坑、花了多久、怎么灰度？
- 有没有遇到"能启动但行为不对"的迁移事故（属性/注解）？

## 真实性说明

技术结论与源码引用可复现（Boot 3.5.16 + Tomcat 10.1.34）；lab 实测为 2026-09-24 本机运行（Boot 3.2.5 / 内嵌 Tomcat 10.1.20）；Boot 2 对照信息为公开迁移口径（未在源码树验证）；项目经历请用真实案例填充。
