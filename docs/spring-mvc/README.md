# SpringMVC 核心管线系列

> 语料：**spring-framework 6.2.9** spring-webmvc（DispatcherServlet/HandlerMapping/HandlerAdapter/ExceptionResolver 全在）
> 范围（用户点名）：**只抓请求处理核心管线**——DispatcherServlet/映射/适配/参数解析/拦截器/异常；**前端视图与文件上传不属本线**
> 计划：L5-6 补完（内核线已封卷 `docs/spring-kernel`）

## 一、总表（6 篇：概念 → 原理 → 使用 → 面试）

| # | 文章 | 层 | 核心问题 | 素材 / 证据 |
|---|---|---|---|---|
| 01 | [什么是请求处理管线：五大组件](01-pipeline-overview.md) | **概念** | DispatcherServlet/Mapping/Adapter/Resolver What | webmvc 源码结构 |
| 02 | [原理一：doDispatch 五段主干](02-do-dispatch.md) | **原理** | 一个请求进来到返回的固定剧本 | DispatcherServlet 源码 |
| 03 | [原理二：映射与适配](03-mapping-adapter.md) | **原理** | HandlerMapping 怎么找方法；Adapter 怎么调方法 | 源码核心类 |
| 04 | [使用：参数解析与消息转换](04-args-messageconverter.md) | **使用** | @RequestParam/@RequestBody/@PathVariable 从哪来；JSON 选型 | 源码+工程 |
| 05 | [使用：拦截器与异常解析](05-interceptor-exception.md) | **使用** | HandlerInterceptor 三方法；ExceptionResolver 顺序；常见坑 | 源码+工程 |
| 06 | [面试收口：五篇回链与陷阱](06-interview-wrapup.md) | **面试** | 五篇回链；高频坑；终检 | 综合 |

## 二、学习路径

```text
概念：01
原理：02 → 03
使用：04 → 05
收口：06
```

## 三、前置要求

- Spring 内核系列 02/03（Bean 生命周期/AOP——Controller 也是 Bean、拦截器也是组件）。
- OpenFeign 系列（客户端视角的对端就是本系列的服务端管线）。

## 四、掌握分级

- **必须掌握**：doDispatch 五段；HandlerMapping/Adapter 分工；参数解析器与消息转换器；拦截器三方法时机；异常解析顺序。
- **理解思想**：Servlet 前端控制器模式；"找-适-调-返"的稳定骨架（又一次模板方法）。
- **知道即可**：内容协商/视图解析（前端）、MultipartResolver（上传）、异步请求处理（Callable/DeferredResult）进阶。

## 五、边界

- 前端（视图解析/JSP/Thymeleaf）、文件上传 → **明确不做**（用户点名）。
- WebFlux → 非本线（Reactor 系列另有）。
- Spring Security 过滤器链 → 不属本线。
