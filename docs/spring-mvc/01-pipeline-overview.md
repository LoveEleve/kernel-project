---
title: "01-什么是请求处理管线：五大组件"
series: "SpringMVC 核心管线系列"
previous: "README.md"
next: "02-doDispatch.md（待写）"
source_refs:
  - "framework 6.2.9 webmvc 已核：DispatcherServlet/HandlerMapping/HandlerAdapter/HandlerExceptionResolver（核心接口名）"
---

# 01-什么是请求处理管线：五大组件

## §0 它干了什么（例子先行）

`GET /orders/123` 从进应用到返回 JSON，中间的接力：

```
Servlet 容器收到请求
  → DispatcherServlet（总调度：所有请求的唯一入口）
  → HandlerMapping（按 URL 找到"谁来处理"：OrderController.getOrder(123)）
  → HandlerAdapter（适配器：替 DispatcherServlet 去调这个方法——参数一个个解析）
  → Controller 方法执行，返回对象
  → 消息转换器（Jackson 把对象写成 JSON 响应——@ResponseBody 主路）
  （出错 → HandlerExceptionResolver 接住）
  （过程可插 → HandlerInterceptor 前后钩——05）
```

- **它干了什么**：**前端控制器模式**——一个 DispatcherServlet 统管所有请求，把"找谁/怎么调/怎么回/错了怎么办"拆给四个可替换组件。
- **本篇回答**：五个词各管什么？为什么 JSON 接口不碰"视图"？

## §1 概念铺垫（What：五件套词表）

| 词 | 一句话 | 常见实现 |
|---|---|---|
| **DispatcherServlet** | 总调度（前端控制器），doDispatch 是剧本 | 唯一入口 |
| **HandlerMapping** | **找**：URL→Handler（方法） | `RequestMappingHandlerMapping`（@RequestMapping 家族） |
| **HandlerAdapter** | **适**：按 Handler 类型选调用方式并解析参数 | `RequestMappingHandlerAdapter` |
| **HandlerExceptionResolver** | **错**：异常→错误响应 | `ExceptionHandlerExceptionResolver`（@ExceptionHandler） |
| **HandlerInterceptor** | **钩**：方法前中后插逻辑（05） | 自定义/框架自带（LocaleChange 等） |

**记忆钩**：**找（Mapping）→ 适+调（Adapter）→ 错（ExceptionResolver）；Dispatcher 是导演，Interceptor 是场务**。

## §2 为什么 JSON 接口没有"视图"（和旧 MVC 的分界）

老 MVC 时代多一块 ViewResolver（ModelAndView→JSP 页面）；**REST 时代主路是 `@ResponseBody`：返回对象→消息转换器直接写响应流**——视图解析器退居冷板凳。本系列按你点名的范围：**只讲 JSON 主路，视图线不展开**。

## §3 掌握卡（2 条）

1. **五件套职责线**：Mapping 找、Adapter 调（含参数解析——04）、Controller 干业务、Converter 写响应、ExceptionResolver 收错——**每环都是接口+默认实现**（03 的模板方法哲学第四次出现：Spring 内核/Sentinel/Micrometer/此处）。
2. **所有组件都是 Bean**：Mapping/Adapter/Resolver 全由自动配置装配（Spring 内核 04 的语言）——**替换某个组件=声明同类型 Bean 覆盖**（Boot 自动配置退让语义）。

## §4 边界（一句话翻篇）

- 五件套装配关系/自动配置类 → 02/03。
- 内容协商/编码器/异步请求 → 翻篇（核心管线外）。
- WebFlux 对照 → README 边界。

## §5 Self-Test（2 题）

- Q1：五件套按请求顺序复述各自职责。
- Q2：@ResponseBody 路线为什么不需要 ViewResolver？

## §6 衔接

- 概念层收口；下篇 02 doDispatch：总调度的固定剧本——五段主干与关键源码锚点。
