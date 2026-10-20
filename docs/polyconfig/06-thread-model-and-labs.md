---
title: "06-进阶：线程模型、扩展与实验"
series: "PolyConfig 配置统一抽象系列"
previous: "05-interview-wrapup.md"
next: "（本系列完）"
source_refs:
  - "PropertySourceExtensionLoader:80/selectImports:92/loadPropertySource:134/createConfigChangePublisher:242；NacosPropertySourceLoader:48 static cache:55/getConfigService:121/configureRefresher:89；ResourcePropertySourcesRefresher:96/105；ResourcePropertySources:64/114；EnvironmentConfigChangeListener:54/76；PolyConfigAutoConfiguration:39"
cross_refs:
  - "03-refresh-events.md（锁边界）；04-usage-pitfalls.md（编码/双路事件）"
---

# 06-进阶：线程模型、扩展与实验

## §0 它干了什么（例子先行）

不接 Nacos，自己写一个“内存后端”，只用一个注解就能把配置塞进 Spring Environment：

```java
@InMemoryPropertySource("app.properties")
@Configuration
public class AppConfig {
}
```

```java
AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(AppConfig.class);
System.out.println(context.getEnvironment().getProperty("app.name"));
// in-memory
```

这个例子回答两件事：

- **扩展一个新后端到底要写多少代码**：1 个注解 + 1 个 Loader（2 个抽象方法），核心模块零改动。
- **它跑在哪个线程**：启动时跑在解析 `@Configuration` 的线程；运行时刷新跑在 Nacos 客户端通知线程；事件监听器默认也在同一个线程同步执行。

本篇回答：锁外发布到底解决了什么、没解决什么？新增后端怎么写？多 dataId/失败模式/双路事件怎么验证？

---

## §1 线程模型：锁外 ≠ 异步

### 1.1 三条线程

| 路径 | 触发者 | 线程 | 关键代码 |
|---|---|---|---|
| 启动加载 | Spring 解析 `@Configuration` | 启动线程（通常是 `main`） | `selectImports:92` → `loadPropertySource:134` → `getConfig:78`（阻塞拉取） |
| 运行时刷新 | Nacos 客户端通知 | **Nacos 客户端通知线程** | `Listener.receiveConfigInfo` → `refresher.refresh:96` |
| 事件分发 | `changePublisher.publish` | **默认与发布者同线程** | `publishEvent:54` → Spring `ApplicationEventMulticaster` |

Nacos 侧的关键事实：`Listener.getExecutor()` 返回 `null` 时，Nacos 客户端会在自己的线程里直接 `run()` 回调（Nacos 日志原话：`task execute in nacos thread`），**不是 Netty 服务端线程**，也不是 PolyConfig 新建的线程。

### 1.2 锁外发布到底解决什么

```java
public void refresh(String resourceValue, Resource resource) {
    List<ConfigPropertyChangedEvent> changes = doRefresh(resourceValue, resource); // synchronized
    if (changes != null && !changes.isEmpty()) {
        changePublisher.publish(sourceName, changes); // 锁外
    }
}
```

`doRefresh:105` 是 `synchronized`：重载、diff、更新 `resourceSources`、`replace` 都在锁内。`publish` 在锁外，所以**监听器不会持锁执行**。

这解决的是：

- 监听器里重建连接池（秒级）时，**不会占着刷新器的锁**，其他刷新请求可以继续；
- 监听器如果回调 `refresh()`，也不会因为同一把锁直接死锁。

它**不解决**：

- Spring 事件默认是**同步**的。`publishEvent` 会在当前线程依次调用监听器；监听器慢，`publish` 就慢，**这次通知所在的 Nacos 客户端线程仍然被阻塞**。
- 它也不会把监听器切到业务线程池。真正的异步需要自己配 `ApplicationEventMulticaster` 的 `TaskExecutor`，或在监听器内异步。

### 1.3 实测证据

用项目里的 `ConfigChangePublisher` 在一个命名线程里发布事件，监听器 sleep 300ms：

```java
Thread publisher = new Thread(() -> {
    new ConfigChangePublisher(context).publish("demo",
            List.of(new ConfigPropertyChangedEvent("k", "old", "new")));
}, "nacos-notify-thread");
```

输出：

```text
publish thread  = nacos-notify-thread
listener thread = nacos-notify-thread
listener done
publish returned
elapsed ms = 309
```

结论：**同线程、同步、阻塞 309ms**。所以文档里不能说“推送线程不被业务监听器拖住”；准确说法是“**锁外发布避免监听器持锁执行，但监听器仍同步占用 Nacos 客户端通知线程**”。

### 1.4 要异步怎么办

三种做法，按侵入性从低到高：

1. **监听器内异步**：`@Async` + `@EnableAsync`，或自己丢线程池。只影响这个监听器。
2. **全局异步 multicaster**：注册名为 `applicationEventMulticaster` 的 `SimpleApplicationEventMulticaster`，设置 `TaskExecutor`。影响整个应用的所有 Spring 事件，慎用。
3. **换事件通道**：监听器只做入队，业务线程消费。适合重活、需要背压/重试的场景。

异步的代价：事件顺序、异常传播、应用关闭时的在途任务都要自己处理；PolyConfig 默认不替你做。

---

## §2 扩展一个新后端：完整代码

目标：不依赖 Nacos，从内存 Map 读配置。新增两个文件，核心模块零改动。

### 2.1 注解

```java
package com.example.polyconfig;

import io.github.loveeleve.polyconfig.spring.context.annotation.PropertySourceExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@PropertySourceExtension
@Import(InMemoryPropertySourceLoader.class)
public @interface InMemoryPropertySource {

    @AliasFor(annotation = PropertySourceExtension.class)
    String[] value() default {};
}
```

只声明了 `value`，其余通用属性（`name`/`autoRefreshed`/`first`/`before`/`after`/`ignoreResourceNotFound`/`encoding`）由基类 `getString/getBoolean/getStringArray` 回退默认值——这就是“三方后端注解不必写全通用属性”的落地。

### 2.2 Loader

```java
package com.example.polyconfig;

import io.github.loveeleve.polyconfig.spring.context.annotation.PropertySourceExtensionLoader;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class InMemoryPropertySourceLoader extends PropertySourceExtensionLoader<InMemoryPropertySource> {

    private static final Map<String, String> CONFIG = Map.of(
            "app.properties", "app.name=in-memory\napp.port=8080");

    @Override
    protected Class<InMemoryPropertySource> getAnnotationType() {
        return InMemoryPropertySource.class;
    }

    @Override
    protected Resource[] resolveResources(AnnotationAttributes attributes, String propertySourceName,
                                          String resourceValue) {
        String content = CONFIG.get(resourceValue);
        if (content == null) {
            return new Resource[0];
        }
        return new Resource[]{
                new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8), resourceValue)
        };
    }
}
```

两个抽象方法：

- `getAnnotationType()`：返回后端注解类型，样板代码；
- `resolveResources()`：把 `dataId` 变成 `Resource`；返回空数组表示“资源不存在”，由 `ignoreResourceNotFound` 决定抛错还是跳过。

可选钩子：

- `configureRefresher()`：需要 `autoRefreshed=true` 时重写，在里面对后端注册监听器，回调 `refresher.refresh(dataId, newResource)`。内存后端没有推送源，所以不重写。

### 2.3 验证

```java
@InMemoryPropertySource("app.properties")
@Configuration
static class AppConfig {
}

@Test
void testInMemoryBackend() {
    try (AnnotationConfigApplicationContext context =
                 new AnnotationConfigApplicationContext(AppConfig.class)) {
        assertEquals("in-memory", context.getEnvironment().getProperty("app.name"));
        assertEquals("8080", context.getEnvironment().getProperty("app.port"));
    }
}
```

没有改 `polyconfig-spring` 的任何一行——这就是“新增后端不动核心”的可验证版本。

---

## §3 四个实验

### 3.1 多 dataId 同名 key 谁赢

```java
@TestPropertySource({"app.properties", "app2.properties"})
static class MultipleResourcesConfig {
}
```

```properties
# app.properties
app.name=polyconfig
# app2.properties
app.name=polyconfig-second
```

结果：`app.name=polyconfig`——**先声明先赢**。原因：

- `CompositePropertySource.getProperty` 按加入顺序取**第一个非 null**；
- `ResourcePropertySources.flatten:114` 用 `putIfAbsent`，与查询口径一致。

注意：`first/before/after` 只影响 **Environment 跨源**顺序，不会改变同一个 Composite 内部顺序。想让后面的 dataId 覆盖前面的，要么调整 `value` 顺序，要么拆成两个配置类再用 `after`/`before` 排位。

### 3.2 first/before/after 与目标不存在

```java
@NacosPropertySource(value = "app.properties", after = "systemEnvironment")
```

- `first=true` 直接首位，**忽略** `before/after`；
- 先看 `after`，目标存在就插其后；再看 `before`；都不满足→末尾；
- 目标名是 **PropertySource 的 name**，不是 dataId；
- 目标不存在**不报错**，只打 debug 日志，继续往下走——排位写错不会炸启动，但配置会静默落到末尾。

### 3.3 失败模式

| 场景 | 启动时 | 刷新时 |
|---|---|---|
| `getConfig` 返回 `null`，`ignoreResourceNotFound=false` | `IllegalStateException`，启动失败 | — |
| `getConfig` 返回 `null`，`ignoreResourceNotFound=true` | warn 后跳过；全部跳过则不加源 | — |
| `getConfig` 抛异常，`ignore=false` | `IllegalStateException`，启动失败 | — |
| `getConfig` 抛异常，`ignore=true` | warn 后跳过 | — |
| YAML/XML 解析失败 | `IllegalStateException`，启动失败 | error 日志；旧源保留；不发事件 |
| 配置内容为空 | 加入一个空 `MapPropertySource` | diff 把旧 key 全算删除，发事件（new=null） |
| `Environment` 不是 `ConfigurableEnvironment` | `IllegalStateException` | — |
| 注解不在配置类上 | warn，不加载 | — |

补充：`ConfigChangePublisher.publish:50` 对空 changes 有第二道跳过；`ResourcePropertySourcesRefresher.doRefresh:105` 对“无变化”和“重载失败”都返回 `null`，不发事件。

### 3.4 双路重复事件

条件：**声明式加载**（`@NacosPropertySource`，路 A）与 **Spring Cloud 标准刷新**（Nacos/Apollo/Config，路 B）同时启用。

```java
// 路 A：声明式刷新 → source = NacosPropertySource@配置类
// 路 B：Spring Cloud 环境刷新 → EnvironmentChangeEvent
//      → EnvironmentConfigChangeListener → source = "environment"
```

同一次服务端变更，两条感知路径都亮，就会各发一个 `ConfigPropertiesChangedEvent`。消费端两种处理：

```java
if ("environment".equals(event.getSourceName())) {
    // 信息降级：old 恒 null，new 可能为 null（删除/无法解析）
} else {
    // 声明式：old/new 都有
}
```

或者直接幂等：按属性名 + 新值去重，避免重复重建连接池。注意：**只有两条路径都启用时才重复**；只用其中一条不会。测试 `testBothPathsProduceTwoEvents` 用“声明式刷新 + 手动 publish EnvironmentChangeEvent”钉住了这条：一次刷新得到两个事件，source 分别为属性源名与 `environment`。

---

## §4 生产检查清单

### 4.1 ConfigService 缓存与密码

`NacosPropertySourceLoader.getConfigService:121`：

```java
String password = attributes.getString("password");
String cacheKey = serverAddress + "|" + namespace + "|" + username + "|" + Objects.hashCode(password);
return configServiceCache.computeIfAbsent(cacheKey, key -> createConfigService(attributes));
```

- key 必须包含**所有客户端级身份**：地址、命名空间、用户名、密码哈希；
- 密码只存哈希，不把明文放进 key；
- 缓存是 `static`，JVM 退出时 shutdown hook 统一 `shutDown()`；
- `group` / `timeout` 是每次 `getConfig` 的参数，不进客户端 key。

### 4.2 自定义发布器的边界

自动配置的 `ConfigChangePublisher` Bean 带 `@ConditionalOnMissingBean`，但它**只被 `EnvironmentConfigChangeListener` 使用**。声明式 Loader 在配置类解析期走 `createConfigChangePublisher():242` 自己创建发布器，所以：

- 自定义 `ConfigChangePublisher` Bean 不会改变 `@NacosPropertySource` 路径；
- 要统一扩展发布行为，改 Loader 或包装 `ApplicationEventPublisher`。

### 4.3 格式与编码矩阵

| 格式 | 解析器 | `encoding` 是否生效 |
|---|---|---|
| `.properties` | `PropertiesLoaderUtils` + `EncodedResource` | **是** |
| `.yml` / `.yaml` | SnakeYAML | 否，固定 UTF-8 |
| `.xml` | `Properties.loadFromXML` | 否，由 XML 声明决定 |
| 其他后缀 | 按 properties 解析 | 是 |

Nacos 的内容是 `ByteArrayResource`，没有文件名；`ResourcePropertySources.load:64` 用 **dataId** 判定格式，所以 `app.xml` 也能按 XML 解析。

### 4.4 版本与模块

- JDK 17 / Spring Boot 3.2.5 / Spring Cloud 2023.0.3 / nacos-client 2.3.2；
- `polyconfig-spring`（核心）← `polyconfig-spring-boot`（自动配置） / `polyconfig-nacos`（Nacos 后端）；
- 30 个测试：spring 22 / boot 3 / nacos 5。

### 4.5 事件 API 速查

| API | 返回 | 说明 |
|---|---|---|
| `getSourceName()` | `String` | 声明式是属性源名；环境适配是 `"environment"` |
| `getChanges()` | `List<ConfigPropertyChangedEvent>` | 不可变；按 diff 检测顺序 |
| `getChangedPropertyNames()` | `Set<String>` | 所有变更属性名（去重、保持顺序） |
| `getNewProperties()` | `Map<String,Object>` | 新值；环境适配的删除/未知可能为 null |
| `getOldProperties()` | `Map<String,Object>` | 旧值；环境适配恒为 null |
| `isChanged(name)` / `getChange(name)` | `boolean` / `ConfigPropertyChangedEvent` | 单属性过滤 |
| 构造校验 | — | `sourceName` 非空、`changes` 非空；列表不可变 |

---

## §5 Self-Test（6 题）

1. 锁外发布解决了什么？没解决什么？为什么不能说“推送线程不被拖住”？
2. `Listener.getExecutor()` 返回 `null` 时，Nacos 回调跑在哪个线程？
3. 新增一个后端必须实现哪两个抽象方法？需要自动刷新时还要重写什么？
4. 同一个注解里多个 dataId 有同名 key，谁赢？`first/before/after` 管哪一层？
5. `encoding` 对 `.properties` / `.yml` / `.xml` 分别怎样？
6. 双路重复事件在什么条件下发生？消费端怎么处理？

---

## §6 衔接

- PolyConfig 系列完结：01 概念 → 02 启动 → 03 刷新 → 04 使用 → 05 面试 → 06 进阶实验。
- 下一步：**polyzone**（②，26 类——polydata 依赖的另一半）。
