---
title: "03-原理二：映射与适配"
series: "SpringMVC 核心管线系列"
previous: "02-do-dispatch.md"
next: "04-参数解析与消息转换.md（待写）"
source_refs:
  - "framework 6.2.9：AbstractHandlerMethodMapping.getHandlerInternal:378→lookupHandlerMethod:400；RequestMappingHandlerAdapter.invokeHandlerMethod:886；ServletInvocableHandlerMethod.invokeAndHandle:115（已核）"
---

# 03-原理二：映射与适配

## §0 它干了什么（例子先行）

doDispatch 的①"找"和②"适"，各自展开是一条小流水线：

```
① 找（Mapping）：
   /orders/123 → lookupHandlerMethod(:400)
     → 遍历注册表：URL 模式匹配（精确>通配>路径变量）
     → 条件再筛：HTTP 方法（GET/POST）、consumes（Content-Type）、produces（Accept）
     → 得到 HandlerMethod（Controller 类+方法 对）+ 拦截器链
② 适（Adapter）：
   invokeHandlerMethod(:886)
     → 逐个参数问 ArgumentResolver："这个参数你能解吗？"（@RequestParam/@PathVariable/@RequestBody…）
     → ServletInvocableHandlerMethod.invokeAndHandle(:115) 反射调用方法
     → 返回值交给 ReturnValueHandler（@ResponseBody→消息转换器，04 展开）
```

- **它干了什么**：Mapping 是"多条件查表"，Adapter 是"参数逐个问+反射调"。
- **本篇回答**：映射怎么多条件竞争？Adapter 为什么必须存在（不能直接反射）？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **HandlerMethod** | Handler 的真身：beanName/Bean 类型+Method 组合 |
| **映射条件四件** | URL 模式 / HTTP 方法 / consumes / produces——注解都写在 @RequestMapping 家族上 |
| **RequestMappingInfo** | 四件条件的打包比较对象（匹配竞争的记分牌） |
| **ArgumentResolver 族** | 每种参数一个"解题器"（04 主场） |
| **ReturnValueHandler 族** | 每种返回值一个"收卷器"（@ResponseBody 的归属） |

**匹配优先级直觉**（面试够用版）：更具体的 URL（无通配>有通配；路径变量更具体者胜）优先；同分再看方法/媒体类型条件——细节在 RequestMappingInfo.compareTo，用时查。

## §2 工程使用：映射的常见配法与坑

```java
@RestController
@RequestMapping("/orders")                    // 类级前缀
public class OrderController {
    @GetMapping("/{id}")                      // 精确+变量——主路
    public Order get(@PathVariable long id) { ... }

    @PostMapping(value = "/search",           // 条件收窄：媒体类型+响应类型
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Order> search(@RequestBody OrderQuery q) { ... }
}
```

| 坑 | 正解 |
|---|---|
| 两个方法映射同一 URL+方法 | 启动期注册冲突直接报错——**宁可收窄条件不要赌运气** |
| consumes 写错（客户端没带 Content-Type） | 415——先查客户端头再改服务端 |
| 类级前缀+方法路径拼出 `//` | 统一风格：前缀带 `/`、方法不带首 `/` |

## §3 掌握卡（2 条）

1. **Mapping 锚点**：getHandlerInternal(:378)→lookupHandlerMethod(:400)——**注册表查表+条件竞争**；注册发生在启动期（扫描 @Controller 收集注解——Bean 生命周期 05 ★1 的邻居）。
2. **Adapter 存在的理由**：Handler 千姿百态（返回 String/对象/void；参数带各种注解）——**DispatcherServlet 不懂业务签名，只认 Adapter 接口**——又一次"接口隔离变化"（ServletInvocableHandlerMethod.invokeAndHandle:115 是反射前的最后一站）。

## §4 边界（一句话翻篇）

- RequestMappingInfo.compareTo 全规则 → 用时查源码。
- produces 与内容协商 → 边界（01 已声明不深展开）。
- 自定义 ArgumentResolver 注册 → 04 末尾一句。

## §5 Self-Test（2 题）

- Q1：映射条件四件是？两个方法撞同一映射会怎样？
- Q2：为什么不直接反射调 Controller，非要 Adapter？（Handler 多态 vs Dispatcher 单一调度）

## §6 衔接

- 找与适的骨架收口；下篇 04 参数解析与消息转换：②内部"逐个问参数"的工程主场（@RequestParam/@RequestBody 从哪来 + Jackson 选型一句话）。
