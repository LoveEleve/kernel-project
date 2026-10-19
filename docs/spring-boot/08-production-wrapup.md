---
title: "08-生产特性与总收口"
series: "Spring Boot 深化系列"
previous: "07-configdata-chain.md"
next: "（本系列完）"
---

# 08-生产特性与总收口

## §0 它干了什么（例子先行）

收口前补最后一块"生产特性速查"，然后全域大检：

```
优雅停机：server.shutdown=graceful + spring.lifecycle.timeout-per-shutdown-phase
          → 收到停机信号：不再接新请求 → 在途请求跑完（默认 30s 预算）→ 再关容器
懒加载：  lazy-initialization=true → ★2 单例不批量造 → 启动快，首次调用付账
devtools：本地开发热重启——**打包排除**（生产毒药）
```

- **它干了干什么**：三个生产开关，各治一类痛（停机丢请求/启动慢/改代码重启慢）。
- **本篇回答**：优雅停机和 @PreDestroy 谁先谁后？lazy 的账在哪？总收口怎么过？

## §1 生产特性速查表

| 特性 | 配置 | 本质 | 代价/注意 |
|---|---|---|---|
| **优雅停机** | `server.shutdown=graceful` | 先停接新流量，再等在途（默认预算 30s，可用 `spring.lifecycle.timeout-per-shutdown-phase` 调） | 预算内没跑完的请求仍会被掐——预算要按最慢接口调 |
| 停机钩子序 | — | 在途请求完 → **拦截器 afterCompletion/Controller @PreDestroy**（内核 02）→ 容器关 | 与内核销毁逆序衔接 |
| **lazy-init** | `lazy-initialization=true` | ★2 批量造改为首次用才造 | 首调延迟+**启动期就该暴露的配置错误被推迟**——权衡 |
| devtools | 本地依赖 | 自动重启/热加载 | **exclude 进打包**（repackage 自动排除） |

## §2 八篇要点回链（本系列全景）

| 篇 | 一句话考点 |
|---|---|
| 01 | 编排归 SpringApplication，造 Bean 归 refresh |
| 02 | 四波事件 + Runner 位次 + ready 语义 |
| 03 | 绑定在 Before 波；静默失败三查 |
| 04 | logging 抢跑 environmentPrepared；切片测试 |
| 05 | Fat jar 三层类加载；LaunchedURLClassLoader |
| 06 | 端点两段闸：注册≠暴露；health 唯一默认 |
| 07 | ConfigData 双 SPI；optional 在 Loader 层 |
| 08 | 优雅停机/lazy（本篇） |

**与内核系列的总分工一句**：**容器与装配（内核 01-06）+ 运行与机制（本系列）**——面试答 Boot 时，"run 四波+自动配置+Binder"三件套齐了就是完整叙事。

## §3 终检（8 题，过 6 算过关）

1. run 骨架谁造 Bean；四波事件序（01/02）
2. Runner 在哪两事件之间；失败=ready 不到（02）
3. 绑定发生在哪一波；`timeout.ms` 绑不上的原因（03）
4. logging 为何能记 refresh 报错（04）
5. Main-Class 是 JarLauncher；三层类加载（05）
6. @Endpoint 默认可见吗；readiness 组放什么（06）
7. `optional:nacos:` 的 optional 在哪层生效；要寄生配置体系实现什么（07）
8. 优雅停机与 @PreDestroy 的时序；lazy 的隐藏成本（08）

## §4 衔接

- **Spring Boot 深化+补强 8 篇全封**——Boot 域三块：自动配置（内核 04）/运行机制+补强（本系列）/事件 Environment（内核 07）。
- 下一战：**B 计划 Spring Cloud 抽象与 SCA 装配 5 篇**（总装图）——开吗？
