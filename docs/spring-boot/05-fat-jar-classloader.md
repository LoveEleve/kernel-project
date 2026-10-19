---
title: "05-Fat jar与类加载"
series: "Spring Boot 深化系列"
previous: "04-usage-wrapup.md"
next: "06-Actuator端点体系.md（待写）"
source_refs:
  - "boot-3.2.5：spring-boot-tools/spring-boot-loader（launch/Launcher.java 新结构）+ loader-classic/LaunchedURLClassLoader.java:42（已核）"
---

# 05-Fat jar与类加载

## §0 它干了什么（例子先行）

`java -jar app.jar` 为什么不用 `-cp lib/*` 就能跑？因为 Boot 把依赖**打进同一个 jar**，靠一个特殊 Main 接管类加载：

```
app.jar
├── META-INF/MANIFEST.MF   Main-Class: o.s.boot.loader.launch.JarLauncher
├── org/springframework/boot/loader/…   ← loader 自己的类（最先被 JVM 加载）
├── BOOT-INF/classes/      ← 你的 .class
└── BOOT-INF/lib/*.jar     ← 所有依赖 jar
```

```
JVM 只认 MANIFEST 的 Main-Class（system 类加载器先载 loader）
  → JarLauncher 自建 LaunchedURLClassLoader（:42）
  → 指向 BOOT-INF/classes + BOOT-INF/lib/**（你的代码和依赖都在这层）
  → 反射调你的真正的 main（你的类在 loader 之外——"先有鸡"问题的解法）
```

- **它干了什么**：**把"没有 -cp"变成"loader 自己拼 -cp"**——依赖目录即类路径。
- **本篇回答**：三层类加载各管什么？为什么第三方 jar 看不到 loader 的类（反之亦然）？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **Fat jar（可执行 jar）** | 普通 jar + BOOT-INF 目录布局 + 自带 loader |
| **JarLauncher** | MANIFEST 里的 Main-Class——**不是你的 main**，是引导器 |
| **LaunchedURLClassLoader** | 自定义 URLClassLoader（:42），类路径=BOOT-INF/classes+lib |
| **三层类加载** | Bootstrap(核心 JDK) → System(启动参数 -cp，即 loader 自身) → **自定义**(你的应用与依赖) |

**关键设计——父委托（加载前先问父加载器）的分层点**：你的代码由 LaunchedURLClassLoader 加载，它的父层只有 loader 自身——**System 层（启动参数 classpath）看不到 BOOT-INF**（所以 `Class.forName("org.springframework...")` 在 main 之前由 System 层找不到——只有过了 LaunchedURLClassLoader 才找得到）；同理 IDEA 开发期 classpath 是平铺的，**和 fat jar 的分层不同**——这是"本地跑好、`java -jar` 报 ClassNotFound"类问题的常见根因。

## §2 工程使用

| 场景 | 动作 |
|---|---|
| 看 Main-Class 到底是谁 | `unzip -p app.jar META-INF/MANIFEST.MF` |
| 排查类冲突（两个版本同名类） | 启动加 `-Dloader.debug`/看 loader 路径；maven dependency:tree 先行 |
| war 部署（容器外置） | 走 SpringBootServletInitializer（内核 01 系列提过）——Tomcat 自己的类加载器，无三层问题 |
| GraalVM native | 不走这套——AOT 编译，loader 概念消失（知道即可） |

**版本注记**：3.2 的 loader 分新旧两目录（`spring-boot-loader` 新结构 `loader/launch/` vs `loader-classic`）——新结构为分层 jar（**分层 Docker 镜像**：依赖层与代码层分开拷贝，改代码不重拉依赖层）铺路。

## §3 掌握卡（2 条）

1. **引导三步**：MANIFEST 指 Launcher → Launcher 建 LaunchedURLClassLoader → 反射调真实 main——**你的 main 不是 JVM 第一个调的 main**。
2. **分层的意义**：BOOT-INF/lib 与 classes 分开=**层化基础**——本地是"分层 jar"，Docker 场景是"分层镜像"（构建提速的同一思想）。

## §4 边界（一句话翻篇）

- JarLauncher 新结构全源码 → loader/launch 包用时查。
- LoaderAutoConfiguration/属性封装 → loader 配置项查文档。
- classloader 泄漏排查（热部署/容器内）→ 与 devtools/容器线合并在 08。

## §5 Self-Test（3 题）

- Q1：`java -jar` 的 Main-Class 是谁？它做了什么才轮到你的 main？
- Q2：三层类加载各载什么？为什么 main 之前 `Class.forName(你的依赖)` 找不到？
- Q3：BOOT-INF 分层和 Docker 分层镜像的关系？

## §6 衔接

- 打包层收口；下篇 06 Actuator 端点体系：`/actuator/*` 背后的注册与暴露模型。
