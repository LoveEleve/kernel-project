---
title: "06-面试收口：五篇回链与陷阱"
series: "SpringMVC 核心管线系列"
previous: "05-interceptor-exception.md"
next: "（本系列完）"
---

# 06-面试收口：五篇回链与陷阱

## §0 它干了什么（例子先行）

本篇零新概念，三张清单收口：

```
① 五篇要点回链 ② 高频坑速查 ③ 话术（自建）+ 终检
```

## §1 五篇要点回链

| 篇 | 一句话考点 | 回链 |
|---|---|---|
| 01 | 五件套；前端控制器；JSON 主路无视图 | 01 |
| 02 | doDispatch 五段锚点；preHandle=false 的完整后果 | 02 |
| 03 | 映射四件条件；Adapter 存在理由（Handler 多态） | 03 |
| 04 | 三族词；双料处理器；认领制；Jackson 三句 | 04 |
| 05 | 三钩时机；异常三级；MDC 清理位 | 05 |

**追问秒答 4 条**：请求进来经过谁→01 五件套一句话；@RequestBody 从哪来→readWithMessageConverters（04）；404 白页谁给的→默认第三级 Resolver（05）；拦截器和 Filter 谁先→Filter 更早、跨框架用 Filter（05 §4）。

## §2 高频坑速查

| # | 坑 | 正解 |
|---|---|---|
| 1 | 两方法映射同一 URL | 启动期 Ambiguous 直接失败（03） |
| 2 | consumes/Content-Type 不匹配 | 415——先查客户端头（03/04） |
| 3 | POJO 无注解收 JSON | 走了 ModelAttribute 路——补 @RequestBody（04） |
| 4 | postHandle 里改响应 | 改不动（流已写）——ResponseBodyAdvice（05） |
| 5 | MDC 清在 postHandle | 异常时 post 不执行——**afterCompletion**（05） |
| 6 | @ExceptionHandler 无兜底 | 必须挂 Exception 兜底类（05） |
| 7 | preHandle 忘 return true | 静默中断——所有请求 200 空响应（02 机制的坑用） |

## §3 话术（按拷打口径自建）

> "SpringMVC 我按管线讲：DispatcherServlet 前端控制器统一调度，doDispatch 五段——HandlerMapping 按 URL/方法/媒体类型四条件查表找到 HandlerMethod，HandlerAdapter 先让 ArgumentResolver 逐参数认领再反射调用（@RequestBody/@ResponseBody 是同一个双料处理器的读写两面），返回值经消息转换器写响应；拦截器 pre/post/after 三钩包住方法，异常走三级 Resolver（@ControllerAdvice 排最前出统一 JSON）。整个体系每个组件都是接口+默认实现，替换=声明 Bean 覆盖——和 Boot 自动配置同一套语言。"

## §4 范围声明（诚实边界）

本系列=**请求处理核心管线**；未覆盖（用户点名出界/未展开）：前端视图、文件上传（checkMultipart）、异步请求（Callable/DeferredResult）、内容协商深水、WebFlux。

## §5 终检（6 题，过 5 算过关）

1. 五件套按顺序职责（01）
2. doDispatch 五段+"调用点"锚点（02）
3. 映射四件条件与撞车行为（03）
4. 双料处理器与认领制（04）
5. 三钩时机 + MDC 清理位（05）
6. 异常三级谁排最前（05）

## §6 衔接

- SpringMVC 系列 6 篇完，README 全挂链接；**L5-6 再补一块（内核+Boot 半区+MVC 管线）**。
- 下一候选由你定：ZooKeeper/Curator（理论已铺）/ 连接池与 ORM / Kafka / Boot 运行机制深化。
