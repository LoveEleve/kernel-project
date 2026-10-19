---
title: "04-使用与收口：logging·切片·终检"
series: "Spring Boot 深化系列"
previous: "03-configuration-properties.md"
next: "（本系列完）"
source_refs:
  - "boot-3.2.5：LoggingApplicationListener:94（监听 ApplicationEnvironmentPreparedEvent :222——logging 先于 context 实锤）"
  - "test-autoconfigure：@WebMvcTest 等切片注解族（目录已核）"
---

# 04-使用与收口：logging·切片·终检

## §0 它干了什么（例子先行）

两个常被问到的机制，一话版本：

```
logging：环境一就位（environmentPrepared）LoggingApplicationListener 就初始化日志系统——
         所以 refresh 期间（内核 05 ★）日志已经在写；application.yml 的 logging.* 此时可读
测试：@SpringBootTest 全量起容器（慢但真）；@WebMvcTest 只装配 MVC 栈（快——切片=只装需要的那层）
```

- **它干了什么**：logging=**抢跑的监听器**；切片=**按需装配的测试自动配置**。
- **本篇回答**：logging 为什么必须早？切片怎么"只装一层"？Boot 高频坑有哪些？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **LoggingApplicationListener** | 监听 environmentPrepared 的监听器（:94）——日志系统初始化点 |
| **LoggingSystem** | 日志系统抽象（logback/log4j2/jul 可换——`logging.system`） |
| **切片测试 Slice Test** | 只装配应用某一层的测试（@WebMvcTest/@JsonTest/@DataJpaTest…） |
| **测试自动配置** | 切片靠 test-autoconfigure 的条件类过滤主自动配置（和内核 04 同一套条件语言） |

## §2 工程使用

**logging 时机的三个工程推论**：

1. refresh 中的报错能进日志文件——因为日志在★前就活了；
2. `logging.config` 指定的自定义 logback.xml 也在 environmentPrepared 后加载；
3. **想在 logging 起来之前打日志**——几乎做不到（早波事件里只能裸 System.out）。

**切片选型**：

| 场景 | 注解 | 装配范围 |
|---|---|---|
| 全链路冒烟 | `@SpringBootTest` | 全容器（慢） |
| Controller 层（MockMvc） | `@WebMvcTest` | MVC 栈+Controller+Advice（**不连 DB/不连 MQ**） |
| JSON 序列化 | `@JsonTest` | 只有消息转换器 |

**工程纪律**：单测能切片就切片（秒级反馈）；需要真实中间件的才升 `@SpringBootTest`——**测试分层与"切片"同名同理**。

## §3 Boot 高频坑速查（合集）

| # | 坑 | 正解 |
|---|---|---|
| 1 | Runner 卡住 ready | 重活异步化（02 §2） |
| 2 | 字段全默认值不报错 | @ConfigurationProperties 三查（03 §3） |
| 3 | `logging.*` 不生效 | 确认在 application.yml 主配置（environmentPrepared 后即读） |
| 4 | 条件装配"没生效" | 内核 04：装配期一次性判定 |
| 5 | started 就暴露端口 | ready 语义（02 §3） |
| 6 | 切片测试连了真库 | 想快就 @WebMvcTest——切片不带 Data 栈 |

## §4 终检（6 题，过 5 算过关）

1. run 四波事件序 + Runner 位次（02）
2. 绑定发生在哪一波；静默失败三查（03）
3. logging 为什么能记到 refresh 的错（04 §1）
4. ready 和 started 语义差（02）
5. @ConfigurationProperties 三种注册方式（03）
6. @SpringBootTest 和 @WebMvcTest 的取舍（04 §2）

## §5 衔接

- **Spring Boot 深化系列 4 篇完，README 全挂链接**——Boot 三块拼图：自动配置（内核 04）+ 运行机制（本系列）+ 事件/Environment（内核 07）。
- 下一战：**B 计划 Spring Cloud 抽象与 SCA 装配 5 篇**（你点名的"深度关联"总装图）——开吗？
