# 面试题详解 01 | 内嵌 Tomcat 的启动与装配（Spring Boot 如何拉起 Tomcat）

> 难度：★★★★☆ ｜ 频率：★★★★★ ｜ 区分度：高（Spring Boot 集成 + Tomcat 容器装配双考点）
> 关键词：ServletWebServerApplicationContext、TomcatServletWebServerFactory、TomcatWebServer、org.apache.catalina.startup.Tomcat、TomcatStarter、ServletContainerInitializer、ServletContextInitializer、StandardServer/Service/Engine/Host/Context、Connector、NioEndpoint、MapperListener

---

## 问题

**Spring Boot 内嵌 Tomcat 是怎么启动的？容器树（Server/Service/Engine/Host/Context）和 Connector、Mapper 是在什么时候装配起来的？DispatcherServlet / Filter / Listener 是怎么注册进容器的？**

> 前置：如果对 Server/Service/Engine/Host/Context/Connector 的职责还没有画面，先读 **00 篇组件地图**（10 分钟）——本篇假设你已认识这些组件，只讲"怎么装起来、按什么顺序启动"。

---

## 30 秒简洁回答

> Spring Boot 启动时由 `ServletWebServerApplicationContext` 调 `TomcatServletWebServerFactory.getWebServer(initializers)`：先用 `new Tomcat()`（Tomcat 官方提供的程序化装配辅助类）搭出 Server→Service→Engine→Host，再 `prepareContext` 创建 `TomcatEmbeddedContext` 并挂上 `TomcatStarter`，最后包成 `TomcatWebServer`。启动时 `tomcat.start()` 沿 Lifecycle 逐级启动容器（Server→Service→Engine/Host/Context），`Connector` 启动时把 `NioEndpoint` 的 **Acceptor/Poller 线程**拉起来、`MapperListener` 建好路由树；`TomcatStarter` 作为 `ServletContainerInitializer` 回调 Spring 的 `ServletContextInitializer`，完成 DispatcherServlet / Filter / Listener 的注册。

---

## 展开回答（高级开发级）

### 1）入口链（Spring Boot 侧）

```
SpringApplication.run
 → ServletWebServerApplicationContext.onRefresh()          （:164-168 → createWebServer）
 → ServletWebServerFactory.getWebServer(initializers)
 → TomcatServletWebServerFactory.getWebServer(...)         （:196-219）
```

`getWebServer` 里的四个关键动作（同一方法）：

```java
Tomcat tomcat = new Tomcat();                        // :200  程序化装配的起点
// 配置 port/baseDir/定制器（ConnectorCustomizer/ContextCustomizer/ProtocolHandlerCustomizer）
prepareContext(tomcat.getHost(), initializers);      // :218  建 Context + 挂 TomcatStarter
return getTomcatWebServer(tomcat);                   // :219 → new TomcatWebServer(...)（:516-517）
```

`prepareContext`（:235-...）里：

- 创建 `TomcatEmbeddedContext`（:237）；
- 设置类加载器 `TomcatEmbeddedWebappClassLoader`（:256）；
- `TomcatStarter starter = new TomcatStarter(initializers); embeddedContext.setStarter(starter);`（:404-406）。

### 2）Tomcat 侧装配：`org.apache.catalina.startup.Tomcat`

- 它是 Tomcat 官方给"嵌入式/程序化"用的辅助类（`Tomcat.java:137`）；
- 内部持有 `Catalina`：`new Catalina()`（:406）→ `server = catalina.getServer()`（:415）；
- 容器对象按需创建：`getServer()` → `new StandardServer()`（:590-598，并在其中创建 Service 挂上：:607-609）；`getService()` → `getServer().findServices()[0]`（:531-532）；`getHost()` → `new StandardHost()`（:561）；`getEngine()` → `new StandardEngine()`（:577）；
- `MapperListener` 由 `StandardService` 持有（`StandardService.java:103` 创建，`:422` 随 Service 启动）——这就是"路由树在启动阶段建好"的来源；
- `addWebapp(...)` 创建 `StandardContext` 并挂到 Host 上；
- **容器树**：`StandardServer` → `StandardService` → `StandardEngine` → `StandardHost` → `StandardContext` → `StandardWrapper`（Wrapper 在 Servlet 注册时生成）。

### 3）启动：Lifecycle 链（容器 + 端口 + 路由）

```
TomcatWebServer.initialize()            （Spring Boot：:110）
 → this.tomcat.start()                  （:128 → Tomcat.java:435）
 → StandardServer.startInternal()       （Tomcat：:855）
 → StandardService.startInternal()      （:406；顺序：容器 → 路由 → Connector）
      · :415 engine.start()              容器树先就绪
      · :422 mapperListener.start()      建 Mapper 路由树（Host/Context/Wrapper 映射）
      · :428 connector.start()           “Start our defined Connectors second”
 → Connector → ProtocolHandler → NioEndpoint
      · Acceptor 线程（AbstractEndpoint:1397-1403）
      · Poller 线程（NioEndpoint:278-282，线程名 "-Poller"，:281 硬编码 daemon）
      · Worker 用 ThreadPoolExecutor（AbstractEndpoint:1089-1091；线程名 "-exec-"）
```

Spring Boot 的一个顺序细节（端口绑定的坑）：

```java
// TomcatWebServer.initialize()（:117-122）
context.addLifecycleListener(event -> {
    if (START_EVENT) {
        removeServiceConnectors();     // 先摘掉 Connector，避免 Service 启动阶段提前 bind
    }
});
...
this.tomcat.start();                   // 容器先起来（此时还没有监听端口）
...
addPreviouslyRemovedConnectors();      // :236 容器就绪后再统一启动 Connector → 端口开始监听
```

### 4）Servlet 注册机制：`TomcatStarter`（Spring Boot 的桥）

- `TomcatStarter implements ServletContainerInitializer`（:36）——Tomcat 在 Context 启动时会回调所有 `ServletContainerInitializer`；
- `onStartup(...)`（:49）遍历 Spring 的 `ServletContextInitializer` 并逐个回调（:51-53）：

```java
for (ServletContextInitializer initializer : this.initializers) {
    initializer.onStartup(servletContext);          // 注册 DispatcherServlet / Filter / Listener
}
```

- 启动异常被暂存到 `startUpException`，由主线程统一抛出，避免 Tomcat 日志重复炸。

---

## 进一步回答（架构师层级）

- **内嵌 vs 独立**：无 `server.xml` / `webapps`（配置体系不同）；Context 由代码创建；类加载器简化（`TomcatEmbeddedWebappClassLoader`）；Jar 启动时用 `LaunchedURLClassLoader`（见 06 题）。
- **线程存活**：Tomcat 的线程（Acceptor/Poller/Worker）**默认都是 daemon**（`AbstractEndpoint.java:910` `daemon = true`；Acceptor `:1403`、Worker 线程工厂 `:1090` 跟随该开关，Poller `NioEndpoint:281` 硬编码 true），所以 Spring Boot 额外起一个**非 daemon** 的 `container-N` 线程调 `tomcat.getServer().await()` 阻止 JVM 立即退出（`TomcatWebServer:140-142、214-225`）。
- **生命周期与 Spring 集成**：`WebServerStartStopLifecycle` 把 WebServer 的 start/stop 挂进 Spring 生命周期；优雅停机见 07 题。
- **定制点体系**：`TomcatConnectorCustomizer`（Connector：超时/压缩/SSL）、`TomcatContextCustomizer`（Context：session/cookie）、`TomcatProtocolHandlerCustomizer`（协议处理器：HTTP/2 等）、`WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory>`（工厂：端口/线程池）；配置属性 `server.tomcat.*`。
- **端口与失败**：`port=0` 随机端口；绑定失败抛 `ConnectorStartFailedException`（Boot 带专门的 FailureAnalyzer）。

---

## 理解与复述提示（学习使用，面试时不要直接念）

- **本质**：这道题考"Spring Boot 如何用代码装配并启动一个 Servlet 容器"；
- **回答顺序**：Spring 入口 → 容器树/Context 装配 → Lifecycle 启动（含 Connector/Endpoint/Mapper）→ Servlet 注册（TomcatStarter）；
- **一句话收束**："Spring Boot 用 Tomcat 的程序化辅助类搭出容器树，用 Lifecycle 启动容器和 Connector，用 TomcatStarter 把 `ServletContextInitializer` 桥进 Context，完成 Servlet/Filter 的代码化注册。"

## 必记关键词

`ServletWebServerApplicationContext` ｜ `TomcatServletWebServerFactory` ｜ `TomcatWebServer` ｜ `org.apache.catalina.startup.Tomcat` ｜ `TomcatStarter` ｜ `ServletContextInitializer` ｜ `StandardServer/Service/Engine/Host/Context` ｜ `Connector` ｜ `NioEndpoint` ｜ `MapperListener` ｜ `removeServiceConnectors`

## 必须明确的边界

- `TomcatServletWebServerFactory` / `TomcatWebServer` / `TomcatStarter` 是 **Spring Boot 的类**；`org.apache.catalina.startup.Tomcat` 是 **Tomcat 的类**；
- DispatcherServlet 不是 Tomcat 的组件，是 Spring MVC 的；
- `new Tomcat()` **不等于**开始监听——端口在 Connector 启动（NioEndpoint bind/start）时才绑定；
- Mapper 路由树是容器启动过程中由 `MapperListener` 建好的，不是"来了请求才建"；
- **本篇只管"装配与启动"**：请求进入后如何被解析与路由（CoyoteAdapter→Mapper→Valve→Servlet）见 03 篇；`LifecycleBase` 状态机细节见 05 篇；嵌入式/外置部署的分支见 06 篇。

## 常见错误

- 把 Spring Boot 的类说成 Tomcat 的（或反过来）；
- 以为内嵌 Tomcat 也会读 `server.xml`——Spring Boot 的装配路径不调用 `Tomcat.init(ConfigurationSource...)`（`Tomcat.java:391/:403`，其内部 `catalina.load()` :410/:412 才是解析配置文件的入口），配置全部由代码/属性完成；
- 说不清 DispatcherServlet 的注册路径（只会说"Spring Boot 自动配的"）；
- 把"容器启动"和"Connector 绑端口"混为一谈；
- 不知道 `removeServiceConnectors` 这一步（端口绑定顺序的坑）。

## 自测要求

- **30 秒**：入口链 + 容器树 + Servlet 注册路径；
- **3 分钟**：能画出 Spring 侧 → Tomcat 侧 → Lifecycle → Servlet 注册的完整时序；
- 能回答追问 1~3。

---

## 追问与参考回答

**追问 1：DispatcherServlet / Filter 是怎么注册进 Tomcat 容器的？**
> Spring Boot 把要注册的 Servlet/Filter/Listener 封装成 `ServletContextInitializer`，通过 `TomcatStarter`（`ServletContainerInitializer`）在 Context 启动时统一回调注册。

**追问 2：`tomcat.start()` 里端口是什么时候真正监听的？为什么要先 `removeServiceConnectors`？**
> 端口在 Connector 启动时绑定（Connector → ProtocolHandler → NioEndpoint）。Spring Boot 在 Service 启动前先摘掉 Connector，避免 Service 启动阶段提前 bind；等容器（Engine/Host/Context）启动完成后再 `addPreviouslyRemovedConnectors()` 统一启动 Connector——保证"容器先就绪、端口后监听"，也便于失败处理。

**追问 3：如何定制内嵌 Tomcat？**
> 四条路：`TomcatConnectorCustomizer`（Connector 层：超时/压缩）、`TomcatContextCustomizer`（Context 层：session/cookie）、`TomcatProtocolHandlerCustomizer`（协议处理器：HTTP/2）、`WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory>`（工厂层：端口/线程池）；配置属性走 `server.tomcat.*`。

---

## 示例与使用说明

```java
// 定制示例：改 Connector 超时（嵌入式视角）
@Component
class MyTomcatCustomizer implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {
    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addConnectorCustomizers(connector ->
                connector.setProperty("connectionTimeout", "5000"));
    }
}
```

复现路径：本卷 14（Spring Boot 桥）、09（Lifecycle 装配）、01（请求旅程）；
源码位置：Tomcat `java/org/apache/catalina/startup/Tomcat.java`、`java/org/apache/catalina/core/StandardServer.java`、`java/org/apache/catalina/core/StandardService.java`、`java/org/apache/tomcat/util/net/{AbstractEndpoint,NioEndpoint}.java`；Spring Boot `.../web/embedded/tomcat/`、`.../web/servlet/context/ServletWebServerApplicationContext.java`。

## 面试官评分点

**通过标准**
- 能讲入口链（Spring 侧四步）、容器树装配、Lifecycle 启动、Servlet 注册的桥；

**加分项**
- 能讲 `removeServiceConnectors` 的顺序细节与"daemon 线程 + container-N await 线程"的存活机制；
- 能讲定制点体系与内嵌/独立差异；

**危险信号**
- 只会说"Spring Boot 自动启动 Tomcat"；
- 分不清 Spring Boot 与 Tomcat 两侧的类；
- 不知道 `TomcatStarter` 的存在。

## 实战练习

1. 加日志/断点跟一遍：`getWebServer` → `prepareContext` → `TomcatWebServer.initialize` → `tomcat.start()`；
2. 打印容器树：`tomcat.getServer()` → Service → Engine → Host → Context（观察内嵌场景的 Context 路径与 docBase）；
3. 用 `WebServerFactoryCustomizer` 改 `maxThreads`，再用 `jstack` 看 `http-nio-8080-exec-*` 线程名验证（线程名来自 `TaskThreadFactory(getName() + "-exec-", ...)`，`AbstractEndpoint.java:1090`）。

## 版本与来源

- Tomcat **10.1.34**：`/data/workspace/source-code/code/spring/tomcat`；
- Spring Boot **3.5.16**：`/data/workspace/source-code/code/spring/spring-boot`；
- 本卷章节：14（Spring Boot 桥）、09（启动与装配）、01（请求旅程）；
- 规划：`Tomcat源码学习范围规划.md` T-1（启动与装配闭环）。

## 个人经历填充区（留空，按真实项目补）

- 你项目里内嵌 Tomcat 做过哪些定制（线程池/端口/超时/SSL）？为什么？
- 启动失败的排查经历（端口占用、`ConnectorStartFailedException`、Context 启动异常）？

## 真实性说明

技术结论与源码引用可复现（Tomcat 10.1.34 + Spring Boot 3.5.16）；代码为演示骨架；项目经历请用真实案例填充。
