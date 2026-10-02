# 面试题详解 10 | 替换与定制 Tomcat（Customizer / Valve / SPI）

> 难度：★★★☆☆ ｜ 频率：★★★★☆ ｜ 区分度：中高（扩展点全景 + Valve/Filter 选择 + 换容器机制）
> 关键词：WebServerFactoryCustomizer、TomcatConnectorCustomizer、TomcatContextCustomizer、TomcatProtocolHandlerCustomizer、Valve、RemoteIpValve、AccessLogValve、ServletWebServerFactory、forward-headers-strategy

---

## 问题

**内嵌 Tomcat 想改配置/加功能，有哪些扩展点？三层 Customizer 分别管什么、什么时候生效？Valve 和 Filter 有什么区别、什么时候必须用 Valve？想换 Jetty/Undertow，背后的机制是什么？**

---

## 30 秒简洁回答

> Boot 的扩展点是"**三层 Customizer + 管道 Valve**"：`WebServerFactoryCustomizer<T extends WebServerFactory>`（改工厂/连接器/协议层属性——Boot 自己的 `TomcatWebServerFactoryCustomizer` 就是它）→ `TomcatConnectorCustomizer`/`TomcatContextCustomizer`/`TomcatProtocolHandlerCustomizer`（工厂创建 Connector/Context/ProtocolHandler 时逐个 apply，Bean 由 `ServletWebServerFactoryConfiguration:68-78` 收集、支持 `@Order`）→ **Valve**（Tomcat 容器管道，`addEngineValves/addContextValves`，执行在 Filter/Servlet **之前**，能拿到 Catalina 内部对象）。换容器不是换 Tomcat，而是**换 `ServletWebServerFactory` 实现**：`@ConditionalOnClass` + `@ConditionalOnMissingBean` 在 Tomcat/Jetty/Undertow 三个内嵌配置间选择（`:64/:86/:104`），排除 starter 换依赖即可——机制比容器本身更值得记。

---

## 展开回答（高级开发级）

### 1）扩展点全景

| 扩展点 | 接口/方法 | 生效时机 | 典型用途 |
|---|---|---|---|
| 工厂级 | `WebServerFactoryCustomizer<T>`（`WebServerFactoryCustomizer:40/:46`） | factory Bean 初始化前（`WebServerFactoryCustomizerBeanPostProcessor:55/:68-69`） | 端口、线程、连接器属性（`server.tomcat.*` 的底层） |
| Connector 级 | `TomcatConnectorCustomizer`（`addConnectorCustomizers :687`） | 创建 Connector 后 | `connector.setProperty(...)`、超时、压缩 |
| Context 级 | `TomcatContextCustomizer`（`addContextCustomizers :670`） | 创建 Context 后 | session 配置、加 **Context 级 Valve**、docBase |
| ProtocolHandler 级 | `TomcatProtocolHandlerCustomizer<T extends ProtocolHandler>`（`:32/:38`；`addProtocolHandlerCustomizers :720`） | 创建 ProtocolHandler 后 | `setMaxKeepAliveRequests`、`setUseVirtualThreads`、换 executor（08/09 篇的虚拟线程就这么接） |
| 容器管道 | `addEngineValves :588` / `addContextValves :617` | 容器启动前 | 访问日志、真实 IP、审计/限流 |
| 应用注册 | `ServletContextInitializer`（01 篇） | Context 启动时 | 注册 Servlet/Filter/Listener |

**收集与排序**：`ServletWebServerFactoryConfiguration.EmbeddedTomcat`（`:63-79`）把容器里的三类 Customizer Bean 收进工厂（`:75-77`，`orderedStream()` → 支持 `@Order`/`Ordered`）；`WebServerFactoryCustomizer` 则通过 `BeanPostProcessor` 在 factory 初始化前 apply（`:55/:68-69`）。

### 2）Valve vs Filter

| | Valve | Filter |
|---|---|---|
| 层级 | Tomcat **容器管道**（Engine/Host/Context/Wrapper 各有一条 Pipeline） | Servlet 规范，应用级 |
| 位置 | 在 Filter/Servlet **之前**（`StandardEngineValve→…→StandardWrapperValve`，03 篇） | Wrapper 内、Servlet 前 |
| 能力 | 可拿 Catalina 内部对象（Request/Response/Host/Context）、作用于**整个容器** | 只能拿 Servlet API，可移植（Tomcat/Jetty 通用） |
| 内置例子 | `AccessLogValve`（访问日志）、`RemoteIpValve`（X-Forwarded-For 还原真实 IP，`RemoteIpValve:359`） | `CharacterEncodingFilter`、`RequestContextFilter` |
| 写法 | `extends ValveBase`（`ValveBase:37`）+ `getNext().invoke(req,resp)`；`pipeline.addValve`（`StandardPipeline:268`） | `implements Filter` |

**实战口径**：需要"所有请求都过 + 拿容器信息 + 不想被应用绕过"→ Valve（如审计、真实 IP、访问日志）；应用逻辑/可移植 → Filter。

### 3）`RemoteIpValve`：Boot 帮我们加的 Valve（源码级例子）

```
TomcatWebServerFactoryCustomizer.customizeRemoteIpValve（:230-256）：
  条件：protocolHeader/remoteIpHeader 配了 或 getOrDeduceUseForwardHeaders()（:258-265）
        · 未配置 forward-headers-strategy 时：探测 CloudPlatform（云平台自动开）
        · 显式配置：strategy == NATIVE 才开
  动作：factory.addEngineValves(new RemoteIpValve())（:256）
```

所以 `server.forward-headers-strategy=native` 时，`request.getRemoteAddr()` 会自动还原为 `X-Forwarded-For` 里的客户端 IP（lab 实测见下）；`server.tomcat.remoteip.*` 可配 header 名/可信代理（默认 `hostHeader=X-Forwarded-Host`、`portHeader=X-Forwarded-Port`，`ServerProperties.Tomcat.Remoteip:1064+`）。

### 4）换容器机制（了解即可，生产以 Tomcat 为主）

```
ServletWebServerFactoryConfiguration：
  EmbeddedTomcat  : @ConditionalOnClass({Servlet, Tomcat, UpgradeProtocol}) + @ConditionalOnMissingBean(ServletWebServerFactory)（:64-65）
  EmbeddedJetty   : @ConditionalOnClass({Servlet, Server, WebAppContext})（:86）
  EmbeddedUndertow: @ConditionalOnClass({Servlet, Undertow, SslClientAuthMode})（:104）
```

- 换法：排除 `spring-boot-starter-tomcat`，引入 `spring-boot-starter-jetty`/`-undertow`（源码树三个 starter 都在）→ 条件装配选中另一个 `ServletWebServerFactory`；
- **Customizer 泛型自动适配**：`WebServerFactoryCustomizer<TomcatServletWebServerFactory>` 在换成 Jetty 后不再生效（类型不匹配），配置前缀也变成 `server.jetty.*`/`server.undertow.*`；
- 现实：Tomcat 是绝对主流（生态/运维/文档），Jetty 在 WebSocket/高并发与 Hadoop/Spark 生态、Undertow 在 WildFly/JBoss 系有存量——**面试里要的是"SPI 机制"，不是某个容器的细节**。

### 5）完全接管：直接给 `ServletWebServerFactory` Bean

```java
@Bean
public TomcatServletWebServerFactory tomcatFactory() {
    TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
    factory.addEngineValves(new MyValve());
    factory.addConnectorCustomizers(c -> c.setProperty("maxPostSize", "0"));
    return factory;
}
```

一旦自己定义该 Bean，Boot 的条件装配（`@ConditionalOnMissingBean`）就退让——**这是最高优先级的定制方式**。

---

## 进一步回答（架构师层级）

- **定制优先级与"覆盖坑"**：属性驱动的 customizer（Boot 自带）由 BeanPostProcessor 后注册，**会覆盖用户先注册的同类 customizer**（lab 实测：自定义 `setMaxKeepAliveRequests(1)` 被 Boot 的默认 100 覆盖）；正确姿势：改属性、或自定义 `ServletWebServerFactory` Bean、或选 Boot 不设置的定制点（如 `connector.setProperty("server", ...)`）；
- **Valve 的生产用法**：`AccessLogValve` 做统一访问日志（比 Filter 更早、更全）、`RemoteIpValve` 取真实 IP（网关后必配）、自定义 Valve 做审计/黑名单/限流（能拿到 Host/Context）；
- **何时不该用 Valve**：业务逻辑、需要跨容器移植、需要 Spring 依赖注入生态 → 用 Filter/Interceptor；
- **换容器的隐性成本**：行为差异（超时/连接语义）、配置前缀、第三方库的容器适配；不是"零成本替换"；
- **与前面篇章的联动**：`server.tomcat.*`（09 篇）就是 customizer 实现；虚拟线程（08/09）走 ProtocolHandlerCustomizer；MBean registry（09）也是 customizer 里的开关。

---

## 理解与复述提示（学习使用，面试时不要直接念）

- **本质**：考"内嵌容器的可扩展性设计"——三层 Customizer 管配置、Valve 管管道、SPI 管替换；
- **回答顺序**：扩展点全景（表格）→ Customizer 收集/排序机制 → Valve vs Filter → RemoteIpValve 例子 → 换容器 SPI；
- **一句话收束**："要改配置就三层 Customizer，要进管道就用 Valve，要换容器就换 `ServletWebServerFactory` 实现——内嵌容器的定制全是'条件装配 + 回调'。"

## 必记关键词

`WebServerFactoryCustomizer` ｜ `TomcatConnectorCustomizer` ｜ `TomcatContextCustomizer` ｜ `TomcatProtocolHandlerCustomizer` ｜ `Valve/ValveBase/Pipeline` ｜ `RemoteIpValve` ｜ `AccessLogValve` ｜ `ServletWebServerFactory` ｜ `forward-headers-strategy` ｜ `@ConditionalOnMissingBean`

## 必须明确的边界

- `ServletContextInitializer` 管"注册组件"，Customizer 管"配置容器"，两者别混；
- Valve 在 Filter **之前**执行，且只对所在容器生效（Engine 级=全局，Context 级=单应用）；
- 换容器不是"Tomcat 换掉"，是**工厂实现**换了；自定义 Customizer 泛型不匹配会静默失效（要按新容器类型写）；
- `server.forward-headers-strategy=native` 是 Boot 自动加 `RemoteIpValve` 的开关（未配置时只有云平台环境自动开）；
- 自己定义 `ServletWebServerFactory` Bean 会**覆盖** Boot 的默认装配；
- **自定义 ProtocolHandler/Connector customizer 若和 Boot 属性撞车，属性会赢**（注册顺序：属性 customizer 后加，`LinkedHashSet` 后者覆盖前者）。

## 常见错误

- 用 Filter 做"所有 webapp 都生效"的容器级事情；
- 换容器后不知道 customizer 失效、配置前缀要改；
- 分不清 `WebServerFactoryCustomizer` 与 `ServletContextInitializer`；
- 网关后不配 `RemoteIpValve`/forward-headers，拿到的都是网关 IP；
- 自定义 customizer 和 Boot 属性撞车还奇怪"为什么没生效"（属性后注册会覆盖）。

## 自测要求

- **30 秒**：三层 Customizer + Valve + SPI 换容器；
- **3 分钟**：能画扩展点全景与生效时机（含行号）；
- 能回答追问 1~3。

---

## 追问与参考回答

**追问 1：`WebServerFactoryCustomizer` 和 `ServletContextInitializer` 有什么区别？**
> 前者改**容器/工厂**（端口、线程、连接器属性，在 factory 初始化前由 `WebServerFactoryCustomizerBeanPostProcessor` apply）；后者往 **ServletContext 注册组件**（Servlet/Filter/Listener，01 篇）。

**追问 2：Valve 和 Filter 什么时候必须用 Valve？**
> 需要"容器级生效 + 拿 Catalina 内部对象 + 不被应用绕过"时（真实 IP、访问日志、审计）；业务逻辑用 Filter（可移植、能注入 Spring Bean）。Valve 在 Filter 之前执行。

**追问 3：换 Jetty/Undertow 的机制是什么？**
> Boot 的 `ServletWebServerFactoryConfiguration` 用 `@ConditionalOnClass` + `@ConditionalOnMissingBean` 在三个工厂实现里选；排除 tomcat starter、引入目标 starter 即可。代价：Customizer 泛型要改、配置前缀要改、行为有差异。

---

## 示例与使用说明（lab 复现，`xhs-demo/tomcat-lab`）

```java
// 1) 自定义 Valve（引擎级）：给所有响应加 Header
public class LabHeaderValve extends ValveBase {
    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        response.setHeader("X-Lab-Valve", "hit");
        getNext().invoke(request, response);
    }
}
// 通过 WebServerFactoryCustomizer 挂上
factory.addEngineValves(new LabHeaderValve());

// 2) Connector 定制：改 Server 响应头（Boot 不设置该属性，不会被属性 customizer 覆盖）
factory.addConnectorCustomizers(connector -> connector.setProperty("server", "xhs-lab/1.0"));

// 3) 属性驱动的定制：keep-alive=1（响应 Connection: close）
//    --server.tomcat.max-keep-alive-requests=1
// 4) RemoteIpValve：--server.forward-headers-strategy=native + X-Forwarded-For → remoteAddr 变真实 IP
```

实测（2026-09-24，lab）：见 `tomcat-lab/README.md`「实验四」——4 个用例的实际输出。

> **lab 实测挖出的机制坑（重要）**：自定义 `TomcatProtocolHandlerCustomizer` 去 `setMaxKeepAliveRequests(1)` **会被 Boot 覆盖回 100**——因为 Boot 属性驱动的 customizer 由 `WebServerFactoryCustomizerBeanPostProcessor:55/:68` 在 factory 初始化前**后注册**（`TomcatWebServerFactoryCustomizer:144-145` 映射 `maxKeepAliveRequests`），把用户先注册的同类 customizer 覆盖掉。**要覆盖属性，直接用属性本身，或自定义 `ServletWebServerFactory` Bean。**

## 面试官评分点

**通过标准**
- 三层 Customizer 与 Valve 的定位/时机；
- Valve vs Filter 的选择；
- 换容器是 SPI 机制而非容器细节。

**加分项**
- 能带行号讲 `ServletWebServerFactoryConfiguration:63-79` 与 `customizeRemoteIpValve:230-265`；
- 能讲 `@ConditionalOnMissingBean` 与自定义工厂 Bean 的覆盖关系；
- 有 Valve 生产实践（真实 IP/访问日志）。

**危险信号**
- 换容器答成"改 pom 就行"（不知道 customizer/配置会失效）；
- Valve/Filter 混用不分场景；
- 网关后拿不到真实 IP 不知道怎么修。

## 实战练习

1. 跑 `CustomizationTest` 三个用例，对照响应头/返回体；
2. 自己写一个 Valve 统计请求耗时并加到响应头；
3. 把 `spring-boot-starter-tomcat` 换成 `-undertow`，观察哪些 customizer/属性失效（只做一次即可）。

## 版本与来源

- Spring Boot **3.5.16**：`web/server/{WebServerFactoryCustomizer,WebServerFactoryCustomizerBeanPostProcessor}.java`（:40/:46、:42/:55/:68-69/:74）、`web/embedded/tomcat/{TomcatServletWebServerFactory,TomcatProtocolHandlerCustomizer}.java`（:588/:617/:670/:687/:720/:142/:376-378、:32/:38）、`autoconfigure/web/servlet/ServletWebServerFactoryConfiguration.java`（:63-79/:86/:104）、`autoconfigure/web/embedded/TomcatWebServerFactoryCustomizer.java`（:96/:132-145/:230-256/:258-265）、`autoconfigure/web/ServerProperties.java`（:1064+）；
- Tomcat **10.1.34**：`catalina/valves/{ValveBase,RemoteIpValve}.java`（:37、:359）、`catalina/core/StandardPipeline.java`（:268/:307）、`coyote/http11/{Http11Processor,AbstractHttp11Protocol}.java`（:386-391/:1011/:1046-1053、:448/:457）；
- lab：`xhs-demo/tomcat-lab`（`CustomizationTest`）；
- 本卷章节：07（Valve/FilterChain）、19（Valve 族）、14（Spring Boot 桥）、09（启动与装配）；
- 规划：`Tomcat源码学习范围规划.md` T-6。

## 个人经历填充区（留空，按真实项目补）

- 项目里加过什么 Valve（真实 IP/日志/审计）？
- 有没有自定义过 WebServerFactoryCustomizer 解决过具体问题？

## 真实性说明

技术结论与源码引用可复现（Boot 3.5.16 + Tomcat 10.1.34）；lab 实测数据为 2026-09-24 本机运行（Boot 3.2.5 / 内嵌 Tomcat 10.1.20）；项目经历请用真实案例填充。
