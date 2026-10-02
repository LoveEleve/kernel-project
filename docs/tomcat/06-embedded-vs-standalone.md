# 面试题详解 06 | 内嵌 vs 独立 Tomcat（类加载器 / 部署形态 / 启动方式）

> 难度：★★★☆☆ ｜ 频率：★★★★☆ ｜ 区分度：高（类加载器一问就见深度）
> 关键词：Bootstrap、Catalina、server.xml、HostConfig、appBase、WAR、WebappClassLoader、ParallelWebappClassLoader、delegate、TomcatEmbeddedWebappClassLoader、docBase

---

## 问题

**内嵌 Tomcat（Spring Boot 自带）和独立 Tomcat（装 Tomcat 跑 WAR）到底差在哪？为什么内嵌模式没有 webapps 目录、没有 server.xml、不用 WAR？两者的类加载器结构有什么不同？**

---

## 30 秒简洁回答

> 差异集中在三处：**①部署形态与启动路径**——独立是 `Bootstrap` → `Catalina` 解析 `conf/server.xml` → `Server.start()` → `HostConfig` 扫 `webapps` 自动部署 WAR/目录；内嵌是 `SpringApplication` 启动 → `ServletWebServerApplicationContext` **代码装配** Tomcat 实例 → `Tomcat.start()`，没有 server.xml/webapps。**②配置来源**——server.xml/web.xml/context.xml vs `application.properties` + Customizer Bean。**③类加载器**——独立是 `Bootstrap → Common → Catalina/Shared → Webapp`（每个 WAR 一个，默认 `delegate=false` **应用优先**）；内嵌把应用 ClassLoader 直接当 parent，且 Boot 把 WebappClassLoader 设成 `delegate=true`（**父优先**）、docBase 用临时目录、`findResource` 返回 null。共同点：**同一套内核（Catalina + Lifecycle，05 篇），差异只在"谁建树 + 类加载策略 + 配置来源"。**

---

## 展开回答（高级开发级）

### 1）启动路径对照

```
独立 Tomcat：
  bin/startup.sh → Bootstrap.main（Bootstrap.java:435）
    → initClassLoaders（:140，读 catalina.properties 建 common/server/shared）
    → Catalina → createStartDigester（Catalina.java:388）解析 conf/server.xml（:81）
    → getServer().start()（:761）→ 容器树启动（05 篇）
    → HostConfig.deployApps（HostConfig.java:414）扫 appBase(webapps)
        · deployWARs :423 / deployDirectories :425

内嵌（Spring Boot）：
  SpringApplication.run → ServletWebServerApplicationContext.onRefresh
    → TomcatServletWebServerFactory.getWebServer → 代码装配 Tomcat/Server/Service/Connector
    → Tomcat.start()（Tomcat.java:435）→ getServer().start()（05 篇）
    → 应用由代码注册（DispatcherServlet 映射 "/"，01 篇），无 webapps 扫描
```

### 2）部署形态：webapps 扫描 vs 代码注册

| | 独立 | 内嵌 |
|---|---|---|
| 部署单元 | WAR/目录，放进 `webapps/` | 无需 WAR，胖 jar/可执行归档 |
| 谁发现应用 | `HostConfig` 扫 `appBase`（`:414/:423/:425`） | `TomcatServletWebServerFactory` 直接建 Context |
| docBase | WAR 解压目录（真实文件） | **临时目录**（`createTempDir("tomcat-docbase")`，Boot :245-246） |
| baseDir | `$CATALINA_BASE`（logs/work/conf） | 临时目录 `createTempDir("tomcat")`（Boot :201-202） |
| 多应用 | 一个 Tomcat 托管多个 WAR（每应用独立 Context/ClassLoader） | 通常一个应用一个进程 |
| 管理应用 | manager/host-manager 自带 | 默认没有（可自行加） |

### 3）配置来源对照

| 独立 | 内嵌 |
|---|---|
| `conf/server.xml`（Connector/Host/Valve） | `application.properties`（`server.tomcat.*`）+ `WebServerFactoryCustomizer` |
| `conf/context.xml`（Context 默认属性） | 代码 `Context` 属性 / `TomcatContextCustomizer` |
| `WEB-INF/web.xml`（Servlet/Filter 映射） | 注解 + `ServletContextInitializer`（01 篇） |
| `conf/catalina.properties`（类加载器路径） | 无（一切在应用 classpath） |

### 4）类加载器：最容易被问倒的一层

**独立 Tomcat（`Bootstrap.initClassLoaders :140`）**：

```
JVM 系统类加载器（启动 Bootstrap 自己）
  └─ Common     （common.loader，Tomcat 内部 + 公共库）
      ├─ Catalina（server.loader，Tomcat 自身，可隔离）
      └─ Shared  （shared.loader，所有 Web 应用共享）
          └─ Webapp（每个 WAR 一个，WebappLoader 创建）
              loaderClass 默认 ParallelWebappClassLoader（WebappLoader.java:98）
              默认 delegate=false（WebappClassLoaderBase.java:281）
              → 查找顺序（delegate=false）：JavaSE 类先行（(0.2) SRV.10.7.2 :1242-1248）
                 → 本地仓库 WEB-INF/classes/WEB-INF/lib（(2) findClass :1330）
                 → parent（(3) :1349）  ← 应用优先
```

- `common.loader/server.loader/shared.loader` 为空时**回退为父加载器**（`Bootstrap:140-148` 建层次；`:159-161` 空值 `return parent`）；
- 每个 Web 应用一个 `WebappClassLoader` → **应用间隔离 + 可热部署**（换 WAR 即换 loader）；
- 默认"应用优先"（`delegate=false`）是 Servlet 规范倾向：应用自己的库优先于容器公共库。

**内嵌（Boot `TomcatServletWebServerFactory.prepareContext :235`）**：

```java
context.setDocBase(docBase.getAbsolutePath());                    // :246 临时目录
context.setParentClassLoader(parentClassLoader);                  // :250
TomcatEmbeddedWebappClassLoader loader =
    new TomcatEmbeddedWebappClassLoader(parentClassLoader);       // :256
loader.setDelegate(true);                                         // :257 父优先！
context.setLoader(loader);                                        // :258
```

- `TomcatEmbeddedWebappClassLoader extends ParallelWebappClassLoader`（Boot `:39`），但 `findResource` 直接返回 null（`:57-58`）——**不让 Webapp loader 自己找资源**，统一走 parent（原因见"进一步"）；
- `delegate=true`（父优先）：应用类由应用的 ClassLoader 提供（fat jar 下 parent 是 LaunchedURLClassLoader，Boot :248-249），先问 parent 才能命中；这与独立模式默认相反；
- 结果：**内嵌没有 Web 应用级隔离与热部署语义**（一个进程一个应用，类都在同一 ClassLoader 体系；Boot DevTools 的 restart 是应用框架层另一套机制）。

### 5）一句话收束

> **同一颗内核（Catalina/Lifecycle/Connector 全套复用），差别是三件事：谁建容器树（digester vs 代码）、类从哪来（Webapp loader 应用优先 vs 应用 ClassLoader 父优先）、配置从哪来（server.xml/web.xml vs properties+代码）。**

---

## 进一步回答（架构师层级）

- **为什么内嵌要 `delegate=true` + `findResource=null`**：Spring Boot 胖 jar 场景下应用类由 `LaunchedURLClassLoader`（可执行归档的 custom context classloader）加载；若 Webapp loader 先自己找（delegate=false 的默认行为）或去碰系统类加载器，会出现"同一类被两个 loader 加载"的 `ClassCastException`/资源找不到问题。Boot 的注释写得很直白：*"does not consider the system classloader. This is required to ensure that any custom context class loader is always used"*。
- **独立模式常见类加载事故**：`delegate=false` 破坏双亲委派的直觉——把 `javax.*`/日志实现打进 `WEB-INF/lib` 会与容器库冲突（`LinkageError`）；两个 WAR 互相 `ClassCastException`（同一个类被两个 Webapp loader 各加载一份）。
- **内嵌模式常见类加载事故**：nested jar（`BOOT-INF/lib`）资源读取、`getResource("/WEB-INF/...")` 语义变化（docBase 是空临时目录）。
- **独立 Tomcat 并没有"消失"**：多应用托管（一个 Tomcat 多个 WAR）、运维粒度（重启单应用）、安全隔离（loader 隔离）仍是刚需；传统企业/金融、Docker+WAR 存量都还在。面试里它主要作为**对照组**出现。

---

## 理解与复述提示（学习使用，面试时不要直接念）

- **本质**：考"同一内核的两种运行形态"——把"建树/类加载/配置"三件事各说清，就能收束；
- **回答顺序**：启动路径 → 部署形态（webapps vs 代码）→ 配置来源 → 类加载器（重点，两侧 delegate 方向相反）；
- **一句话收束**："内嵌不是另一个 Tomcat，只是没人帮你解析 server.xml——树是代码建的，类走应用 ClassLoader（父优先），配置变成 properties。"

## 必记关键词

`Bootstrap` ｜ `Catalina` ｜ `server.xml` ｜ `HostConfig/appBase` ｜ `WAR` ｜ `WebappClassLoader/ParallelWebappClassLoader` ｜ `delegate` ｜ `TomcatEmbeddedWebappClassLoader` ｜ `docBase` ｜ `LaunchedURLClassLoader`

## 必须明确的边界

- 两侧**内核相同**（Catalina/Lifecycle/Connector），别答成"两个不同产品"；
- 独立默认 `delegate=false`（应用优先）、**Boot 内嵌设 `delegate=true`**（父优先）——方向相反，必须说对；
- "内嵌没有 webapps/server.xml"指**默认没有**（可配置，如 `server.tomcat.basedir` 指定 baseDir）；
- 内嵌的 docBase 是**临时目录**，不是项目 `src/main/webapp`；Boot **没有** `server.tomcat.document-root` 属性（只有编程式 `TomcatServletWebServerFactory#setDocumentRoot`，`AbstractServletWebServerFactory:196`）；
- 独立模式仍然广泛存在（传统企业/WAR 存量），不要答"已经淘汰"。

## 常见错误

- 答"内嵌 Tomcat 和独立 Tomcat 是两个不同的 Tomcat"；
- 把 delegate 方向说反（独立应用优先、内嵌父优先）；
- 以为内嵌模式 `WEB-INF/` 一定存在（docBase 临时目录，通常没有）；
- 说不清 webapps 是怎么被发现的（`HostConfig` 生命周期监听器）；
- 把 `catalina.properties` 的作用（类加载器路径）忽略。

## 自测要求

- **30 秒**：三处差异（启动路径/部署形态/类加载器）+ 同一内核；
- **3 分钟**：能画出两侧启动链路与类加载器层次（含 delegate 方向）；
- 能回答追问 1~3。

---

## 追问与参考回答

**追问 1：为什么内嵌要把 `delegate` 设成 true？**
> 应用类由应用的 ClassLoader 提供（fat jar 下 parent 是 LaunchedURLClassLoader，Boot :248-249）；父优先（`delegate=true`）+ `findResource` 返回 null（`:57-58`）保证类与资源都从 parent 走，避免 Webapp loader 自己去碰系统类加载器或重复加载导致的 `ClassCastException`。

**追问 2：独立 Tomcat 的类加载器层次与默认委派方向？**
> `Bootstrap → Common → Catalina/Shared → Webapp`（`Bootstrap:140`，路径来自 `catalina.properties`）；`WebappClassLoaderBase.delegate` 默认 **false**（`:281`）——应用优先：本地仓库（`WEB-INF/classes → WEB-INF/lib`，`(2) :1330`）先于 parent（`(3) :1349`），但 JavaSE 类先行（SRV.10.7.2 `:1242-1248`）；每个 WAR 一个 loader，因此可隔离、可热部署。

**追问 3：内嵌模式为什么没有 webapps 目录和 server.xml？**
> 因为"发现应用"这一步被代码取代了：`TomcatServletWebServerFactory` 直接创建 Context 并把 docBase 指到临时目录（`:245-246`）；配置走 properties/Customizer。`HostConfig` 只负责扫 `appBase`（`:414`），内嵌没人把 WAR 放进 `webapps/`，扫描自然为空。

---

## 示例与使用说明

```text
内嵌（Spring Boot）：
  java -jar app.jar
  观察 java.io.tmpdir 下自动生成的 tomcat-*（baseDir）与 tomcat-docbase-*（docBase）目录

独立 Tomcat：
  bin/startup.sh（→ Bootstrap.main:435）
  观察 logs/catalina.out 的 "Deploying web application archive [...]"
  对比：$CATALINA_BASE/{conf,webapps,logs,work,temp}
```

## 面试官评分点

**通过标准**
- 能说"同一内核、三处差异"；
- 能讲两侧启动链路；
- 能讲两侧类加载器与 delegate 方向。

**加分项**
- 能带行号讲 `HostConfig:414` 与 Boot `prepareContext:235-258`；
- 能解释 Boot 为什么 `delegate=true` + `findResource=null`；
- 能举独立模式的类加载事故（LinkageError/ClassCastException）。

**危险信号**
- 把两者当不同产品；
- delegate 方向说反；
- 认为独立模式已淘汰、或答不出 webapps 如何被发现。

## 实战练习

1. `java -jar` 启动一个 Boot 应用，`ls $TMPDIR` 找到 `tomcat-*` 与 `tomcat-docbase-*`，观察里面有什么；
2. 断点 `TomcatServletWebServerFactory.prepareContext`（:235）与 `HostConfig.deployApps`（:414），对照两侧差异；
3. 给 Boot 配 `server.tomcat.basedir`，观察 baseDir 从临时目录变为指定目录。

## 版本与来源

- Tomcat **10.1.34**：`java/org/apache/catalina/startup/{Bootstrap,Catalina,HostConfig}.java`（:140/:435、:81/:388/:761、:414/:423/:425）、`java/org/apache/catalina/loader/{WebappLoader,WebappClassLoaderBase}.java`（:98、:281）；
- Spring Boot **3.5.16**：`TomcatServletWebServerFactory.java`（:201-202、:235-258）、`TomcatEmbeddedWebappClassLoader.java`（:39、:57-58）；
- 本卷章节：10（类加载隔离）、14（Spring Boot 桥）、09（启动与装配）、01（请求旅程）；
- 规划：`Tomcat源码学习范围规划.md` T-1/T-6。

## 个人经历填充区（留空，按真实项目补）

- 从独立 Tomcat 迁移到 Spring Boot 内嵌，踩过哪些坑（web.xml、JNDI、外部配置）？
- 遇到过类加载冲突吗（`LinkageError`/`ClassCastException`），怎么定位的？

## 真实性说明

技术结论与源码引用可复现（Tomcat 10.1.34 + Spring Boot 3.5.16）；示例为操作路径（未附真实运行输出）；项目经历请用真实案例填充。
