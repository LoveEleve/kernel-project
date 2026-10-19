---
title: "05-使用：拦截器与异常解析"
series: "SpringMVC 核心管线系列"
previous: "04-args-messageconverter.md"
next: "06-面试收口.md（待写）"
source_refs:
  - "framework 6.2.9：HandlerInterceptor 三 default 方法 :103/:130/:155；DispatcherServlet 默认异常三件套 javadoc :102-104 + initHandlerExceptionResolvers:686（已核）"
cross_refs:
  - "xhs TraceIdConfig（Tracing 06）：addInterceptors order(-100)——拦截器注册的实战样例"
---

# 05-使用：拦截器与异常解析

## §0 它干了什么（例子先行）

两块工程高频件：

```java
// ① 拦截器：pre/post/after 三钩（doDispatch 的③⑤——02 回收）
@Component
public class TraceInterceptor implements HandlerInterceptor {
    public boolean preHandle(req, resp, handler) { MDC.put("traceId", ...); return true; }  // false=中断
    public void postHandle(...) { /* 想改 ModelAndView 的时代产物，JSON 主路少用 */ }
    public void afterCompletion(...) { MDC.remove("traceId"); }   // 必须——清理（Tracing 05 同款教训）
}

// ② 异常收口：@ControllerAdvice + @ExceptionHandler（ExceptionResolver 的工程形态）
@RestControllerAdvice
public class GlobalExceptionAdvice {
    @ExceptionHandler(BizException.class) public R<?> biz(BizException e) { ... }
    @ExceptionHandler(Exception.class)     public R<?> other(Exception e) { ... }  // 兜底
}
```

- **它干了什么**：拦截器=**横切钩**（鉴权/染色/清理）；Advice=**错误统一出口**——都是"不改 Controller 就能加的行为"。
- **本篇回答**：三钩时机与返回值语义？注册怎么排序？异常三级默认顺序是什么？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **HandlerInterceptor** | 三方法接口（default 空实现——只覆写需要的，:103/:130/:155） |
| **preHandle 返回 false** | 中断后续+Controller，afterCompletion 仍跑（02 §3 已讲机制） |
| **HandlerExceptionResolver** | 异常→响应的解析器链 |
| **@ControllerAdvice + @ExceptionHandler** | 异常解析器的声明式形态（最常用实现） |

**默认异常三级**（DispatcherServlet javadoc :102-104）：

```
① ExceptionHandlerExceptionResolver   @ExceptionHandler/@ControllerAdvice
② ResponseStatusExceptionResolver     @ResponseStatus 映射状态码
③ DefaultHandlerExceptionResolver     框架默认（404/406/415/400…）
```

**顺序语义**：自定义 Advice 排最前——**业务异常出统一 JSON；没接住才落到框架默认**（404 那张白页就是③的产物）。

## §2 工程使用

**注册与排序**（xhs TraceIdConfig 实战样例，Tracing 06）：

```java
@Configuration
public class MvcConfig implements WebMvcConfigurer {
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TraceInterceptor())
                .addPathPatterns("/**")
                .order(-100);          // 越小越先——染色/鉴权类要排在业务日志类之前
    }
}
```

**三坑**：

| 坑 | 正解 |
|---|---|
| preHandle 改了 MDC/ThreadLocal 忘了清 | afterCompletion 清理（**与线程池复用同根**——Tracing 05/07 全系列教训） |
| postHandle 里改 JSON 响应 | 改不动——@ResponseBody 已写流；要用 ResponseBodyAdvice（知道即可） |
| @ExceptionHandler 只写了子类没写父类 | 按异常类型最近匹配——**兜底 Exception 必须有** |

## §3 掌握卡（2 条）

1. **三钩时机**：pre=方法前（可中断）/ post=方法后成功才到（**异常时不执行**）/ afterCompletion=**无论成败都到**（清理位）——"资源清理放 afterCompletion"是纪律。
2. **异常三级+Advice 形态**：默认①②③（:102-104 已核）；工程上只写 ①的 Advice 形态——②给"纯状态码异常"、③兜底白名单外的一切。

## §4 边界（一句话翻篇）

- ExceptionResolver 自定义排序/多 Advice 顺序 → @Order 用时查。
- Filter vs Interceptor 时机差（Filter 更早，Servlet 层）→ 部署语境一句：**跨框架的东西（如 trace 头）可上 Filter，MVC 语义相关用 Interceptor**。
- CORS/OPTIONS 处理 → 翻篇。

## §5 Self-Test（3 题）

- Q1：三钩各在什么时机？postHandle 为什么异常时不到？
- Q2：默认异常三级顺序？业务统一 JSON 挂哪级？
- Q3：MDC 清理为什么必须在 afterCompletion 而不是 postHandle？

## §6 衔接

- 使用面收口；下篇 06 面试收口：五篇回链 + 高频坑 + 终检。
