---
title: "04-使用：参数解析与消息转换"
series: "SpringMVC 核心管线系列"
previous: "03-mapping-adapter.md"
next: "05-拦截器与异常.md（待写）"
source_refs:
  - "framework 6.2.9：HandlerMethodArgumentResolverComposite/RequestResponseBodyMethodProcessor:67（@RequestBody+@ResponseBody 双料处理器）/readWithMessageConverters:127,148/writeWithMessageConverters:208（已核）"
---

# 04-使用：参数解析与消息转换

## §0 它干了什么（例子先行）

```java
public Order get(@PathVariable long id, @RequestHeader("X-Trace-Id") String tid) { ... }
public Order create(@RequestBody OrderDTO dto) { ... }   // 请求 JSON → 对象；返回对象 → JSON
```

②内部对每个参数**逐个问**"谁能解"：

```
id  → PathVariableMethodArgumentResolver 从 URL 取
tid  → RequestHeaderMethodArgumentResolver 从头取
dto  → RequestResponseBodyMethodProcessor：
         读：readWithMessageConverters(:127)——按 Content-Type 选 Jackson 等转换器反序列化
         写：返回值再走 writeWithMessageConverters(:208)——序列化成响应
```

- **它干了什么**：参数=**解析器认领制**；@RequestBody/@ResponseBody 是**同一个双料处理器**的读写两面。
- **本篇回答**：三族概念怎么分？工程上参数怎么配、JSON 怎么选？自定义解析器什么时候写？

## §1 概念铺垫（What：三族词）

| 族 | 一句话 | 例 |
|---|---|---|
| **ArgumentResolver 族** | 每种参数一个解题器；`Composite` 组合遍历认领 | @PathVariable/@RequestParam/@RequestHeader… |
| **ReturnValueHandler 族** | 每种返回值一个收卷器 | @ResponseBody 的写响应面 |
| **HttpMessageConverter 族** | 字节流↔对象的翻译官 | Jackson（JSON）/字符串/表单 |

**双料处理器**（源码已核）：`RequestResponseBodyMethodProcessor`（:67）**同时**实现读与写——这就是"@RequestBody 和 @ResponseBody 像一对"的实现真相。

## §2 工程使用

**参数注解选型**（日常 95%）：

| 场景 | 注解 |
|---|---|
| 路径变量 | `@PathVariable` |
| 查询/表单参数 | `@RequestParam`（required/defaultValue 两属性解决 90% 报错） |
| 请求头 | `@RequestHeader` |
| JSON 体 | `@RequestBody`（**必须 Content-Type 正确**——415 的根源，03 §2 坑回顾） |
| 对象整体接收 | 无注解 POJO（ModelAttribute 路径——表单绑定，非 JSON） |

**Jackson 工程要点**（不深展开，够用三条）：默认已装（starter-web 带 jackson）；`@JsonIgnore/@JsonProperty` 控制字段；全局定制（日期格式/空值策略）用 `ObjectMapper` Bean 覆盖——**转换器可插拔**（换 fastjson=换 Converter Bean，但先确认必要性）。

**自定义 ArgumentResolver 场景**：全局注入"当前用户/租户"这类横切参数——注册进 Adapter 的 customArgumentResolvers——**业务级横切优先 @Aspect/拦截器，参数级刚需才动 Resolver**（粒度纪律同 03 系列 §2）。

## §3 掌握卡（2 条）

1. **认领制**：Composite 遍历 Resolver，谁 `supportsParameter` 返回 true 谁接单——一个参数只有一次认领；没人认领=启动期或运行期报 `IllegalStateException`（典型：缺 @RequestBody 注解直接收 POJO 走了 ModelAttribute 路）。
2. **读写双料**：@RequestBody 的"读"与 @ResponseBody 的"写"同属 `RequestResponseBodyMethodProcessor`——序列化失败（对象有不可序列化字段等）就发生在 :208 这一步。

## §4 边界（一句话翻篇）

- 全部 Resolver 清单/顺序 → Composite 注册表用时查。
- 内容协商（Accept 头选 Converter）→ 01 已声明不深。
- 自定义 Converter/全局 ObjectMapper 定制细节 → 用时查 Jackson 文档。

## §5 Self-Test（3 题）

- Q1：三族词各管什么？@RequestBody/@ResponseBody 为什么是同一个类？
- Q2：@RequestBody 收到表单会怎样？根因是什么注解/Resolver 错位？
- Q3：想给所有接口自动注入"当前租户"参数，首选 Resolver 还是 AOP？（AOP/拦截器优先）

## §6 衔接

- 参数与消息收口；下篇 05 拦截器与异常：③⑤的钩子工程 + ExceptionResolver 顺序——管线最后一块使用面。
