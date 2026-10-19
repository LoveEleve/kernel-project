---
title: "02-原理一：doDispatch五段主干"
series: "SpringMVC 核心管线系列"
previous: "01-pipeline-overview.md"
next: "03-映射与适配.md（待写）"
source_refs:
  - "framework 6.2.9 DispatcherServlet.doDispatch 体内关键行已核：getHandler:1065/getHandlerAdapter:1072/applyPreHandle:1084/ha.handle:1089/applyPostHandle:1096/processDispatchResult:1106/triggerAfterCompletion:1109"
---

# 02-原理一：doDispatch五段主干

## §0 它干了什么（例子先行）

`doDispatch` 是 01 剧本的源码形态——五段固定：

```
mappedHandler = getHandler(request)          // ① 找：Mapping 匹配（:1065）
ha = getHandlerAdapter(handler)              // ② 选适配器（:1072）
mappedHandler.applyPreHandle(...)            // ③ 拦截器 preHandle（:1084，false 则中断）
mv = ha.handle(request, response, handler)   // ④ 适配器调用 Controller（:1089——参数解析在这内部）
mappedHandler.applyPostHandle(...)           // ⑤ 拦截器 postHandle（:1096）
processDispatchResult(...)                   // 返回值处理（@ResponseBody 写响应；:1106）
// 异常路径统一走 triggerAfterCompletion（:1109/1112——afterCompletion 总会执行）
```

- **它干了什么**：找→适配→调用→收尾，**异常也不脱轨**（afterCompletion 兜底执行）。
- **本篇回答**：五段各在哪一行？preHandle 返回 false 会发生什么？afterCompletion 为什么"总会"跑？

## §1 概念铺垫（What：HandlerMethod 与 HandlerExecutionChain）

| 词 | 一句话 |
|---|---|
| **Handler** | 找到的"处理者"——REST 主路里就是 **HandlerMethod**（Controller 类+方法 对） |
| **HandlerExecutionChain** | Handler + 它的**拦截器列表**打包体——getHandler 返回的不是裸方法而是这个链 |
| **mv（ModelAndView）** | 老 MVC 的返回载体；JSON 主路里常为 null（响应已在 handle 内写完） |

**为什么链要打包拦截器**：拦截器和 Handler 是**一对绑定**（Mapping 决定哪些拦截器管这个 URL）——③⑤ 的 applyPre/PostHandle 都从这个打包体上取拦截器依次执行。

## §2 工程使用：五段里的干预点

| 想干什么 | 挂在哪段 | 工程写法（05 细讲） |
|---|---|---|
| 鉴权/染色（入参前） | ③ preHandle | 实现 HandlerInterceptor |
| 统一改返回体 | ⑤ postHandle / ResponseBodyAdvice | 05 一句带 |
| 耗时统计 | ③ 计时 + afterCompletion 算差 | 或直接用 Micrometer 04 的 @Timed |
| 统一异常出 JSON | 异常路径 → ExceptionResolver | 05 主场 |

**口诀**：**改请求找 preHandle，改响应找 post/after，改错误找 ExceptionResolver**。

## §3 掌握卡（2 条）

1. **五段锚点**（行号已核）：找 :1065 → 适 :1072 → pre :1084 → 调 :1089 → post :1096；结果处理 :1106；异常统一 :1109——**面试能报出"ha.handle 是真正的调用点"就够深**。
2. **中断语义**：preHandle 返回 false → **后续拦截器与 Controller 都不执行**，直接走 afterCompletion——鉴权失败提前 return 的机制根据（不是"抛异常才停"）。

## §4 边界（一句话翻篇）

- checkMultipart 一步（:1061）→ 上传场景，**本线出界**（用户点名）。
- 异步请求（Callable/DeferredResult）的二次分发 → 翻篇。
- MultipartResolver/内容协商 → 边界外。

## §5 Self-Test（3 题）

- Q1：五段按锚点顺序复述；真正的"调用 Controller"是哪一行？
- Q2：preHandle 返回 false 的完整后果？（拦截器链停 + Controller 不跑 + afterCompletion 仍跑）
- Q3：HandlerExecutionChain 里装了什么？为什么 Mapping 要决定拦截器？

## §6 衔接

- 主干收口；下篇 03 映射与适配：①"找"和②"适"各自的方法论（URL 匹配链 + Adapter 的类型分发）。
