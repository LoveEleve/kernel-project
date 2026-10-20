# PolyConfig 吃透指南（源码地图 + 全链路 + 动手路径）

> 对象：`/data/workspace/source-code/poly/polyconfig`（3 模块 / 11 主类 / 30 测试，2026-10 全绿）
> 代码：https://github.com/LoveEleve/poly-project/tree/main/polyconfig
> 用法：按 §7 的路径学；学完本篇再看 PolyData——它有一个统一事件的真实消费者。

---

## 1. 它解决什么问题（先看例子）

**Spring 自带的两种配置方式各缺一块**：
- `@PropertySource`：只能读 classpath 里的文件，不支持配置中心，更不支持热更新；
- Spring Cloud 原生刷新：Nacos/Apollo 客户端各管各的，没有统一声明，变更感知只有一个"属性名列表"（`EnvironmentChangeEvent`），没有旧值、没有来源区分。

**PolyConfig 的做法**：一个注解声明接入，服务端变更自动刷新，所有来源收敛到一种事件：

```java
@NacosPropertySource(value = "application.properties", group = "DEFAULT_GROUP",
                     serverAddress = "127.0.0.1:8848")
@Configuration
public class ApplicationConfig {
}
// Nacos 上改配置 → Environment 自动刷新 → 统一事件：
public class MyListener implements ApplicationListener<ConfigPropertiesChangedEvent> {
    public void onApplicationEvent(ConfigPropertiesChangedEvent event) {
        if (event.isChanged("my.datasource.url")) {
            // 新值 event.getNewProperties()、旧值 event.getOldProperties() 都有
        }
    }
}
```

一句话总结：**配置中心只管存，PolyConfig 管"怎么声明、怎么刷新、怎么通知"——业务只认一种事件。**

---

## 2. 全链路：三条数据流

### 2.1 启动加载（注解 → Environment）

```
Spring 解析 @Configuration
 ① 发现 @Import(NacosPropertySourceLoader.class) → 实例化 loader（借 ImportSelector 的触发时机）
 ② selectImports() 读 @NacosPropertySource 属性（@AliasFor 已映射到通用属性）
 ③ resolveResources()：ConfigService.getConfig(dataId, group, timeout)
    → 返回 null = 配置不存在 = 空数组（后继按 ignoreResourceNotFound 处理）
 ④ ResourcePropertySources.load()：.yml/.yaml 走 SnakeYAML，.xml 走 Properties.loadFromXML，
    其余走 Properties + encoding；Nacos 内容是 ByteArrayResource（无文件名）→ 用 dataId 判定格式
 ⑤ 组装 CompositePropertySource（name 默认 NacosPropertySource@配置类名）
 ⑥ 定位：first → 首位；after 目标存在 → 其后；before 目标存在 → 其前；否则末尾
 ⑦ autoRefreshed（Nacos 默认 true）→ 建 Refresher → configureRefresher() 注册 Nacos Listener
```

### 2.2 运行时刷新（Nacos 推送 → 统一事件）

```
Nacos 服务端变更
 ① Listener.receiveConfigInfo(新内容) → refresher.refresh(dataId, 新 ByteArrayResource)
 ② 按格式重载（properties 用 encoding；YAML 固定 UTF-8；XML 由声明决定）
    → 新旧 flatten 成 Map → 逐属性 Objects.equals diff
 ③ 无变化 → 只打日志，不发事件；重载失败 → 打 error 日志，不发事件
 ④ 有变化 → 锁内重建 Composite + replace → 锁外 publish
    ConfigPropertiesChangedEvent（source = 属性源名）
 ⑤ 业务监听器 event.isChanged("...") → 按需重建（如下游 PolyData 的数据源）
```

### 2.3 环境适配（无注解路径，兼容标准刷新）

```
Spring Cloud 标准刷新（Nacos/Apollo/Config 均可）
 → EnvironmentChangeEvent（只带属性名，无旧值）
 → EnvironmentConfigChangeListener：逐属性查 Environment 取新值，旧值填 null
 → ConfigPropertiesChangedEvent（source = "environment"）
```

一句话总结：**启动时"注解变 Environment"，运行时"推送变事件"，标准刷新"翻译成同一种事件"——三个入口，一个出口。**

---

## 3. 代码地图：11 个主类

| 模块 | 类 | 一句话职责 | 关键方法 |
|---|---|---|---|
| spring | `PropertySourceExtension` | 元注解：8 个通用属性（name/value/autoRefreshed/first/before/after/ignoreResourceNotFound/encoding） | 注解定义 |
| spring | `PropertySourceExtensionLoader` | Loader 模板：4 步加载 + 属性读取兜底 + 发布器装配 | `selectImports()` / `loadPropertySource()` / `addPropertySource()` |
| spring | `ResourcePropertySources` | 资源解析：YAML 与 Properties 双格式 + flatten | `load()` / `flatten()` |
| spring | `ResourcePropertySourcesRefresher` | 刷新器：重载 → diff → 替换 → 锁外发布事件 | `refresh()` / `diff()` / `rebuildAndReplace()` |
| spring | `ConfigChangePublisher` | 事件发布：空变更直接跳过 | `publish()` |
| spring | `ConfigPropertiesChangedEvent` | 统一事件：来源名 + 不可变变更列表 + 查询 API | `isChanged()` / `getChange()` / `getNew/OldProperties()` |
| spring | `ConfigPropertyChangedEvent` | 单属性变更值对象：属性名 + 新旧值（旧值可 null） | equals/hashCode/toString |
| nacos | `NacosPropertySource` | Nacos 声明：通用属性 @AliasFor 映射 + 连接属性（默认 autoRefreshed=true） | 注解定义 |
| nacos | `NacosPropertySourceLoader` | Nacos 后端：拉取 + 注册监听 + ConfigService 缓存 | `resolveResources()` / `configureRefresher()` / `getConfigService()` |
| boot | `PolyConfigAutoConfiguration` | 自动配置：发布器 Bean + 条件装配环境适配 | `configChangePublisher()` |
| boot | `EnvironmentConfigChangeListener` | 标准刷新翻译器（source="environment"） | `onApplicationEvent()` |

测试分布：spring 22 / boot 3 / nacos 5（合计 30，全绿）。

一句话总结：**spring 是"模型"（与任何配置中心无关），nacos 是"第一个后端"，boot 是"装配与翻译"——加新后端不动前两层。**

---

## 4. 关键机制深挖

### 4.1 三层结构与"两个抽象方法 + 一个钩子"扩展点

```
第 1 层：声明 — @PropertySourceExtension（元注解）→ 后端注解（@AliasFor 映射通用属性）
第 2 层：加载 — PropertySourceExtensionLoader<A>（模板）→ 后端 Loader
             实现 2 个抽象方法：getAnnotationType / resolveResources
             可选钩子：configureRefresher（autoRefreshed=true 时必须）
第 3 层：事件 — ConfigPropertiesChangedEvent（统一出口，不随后端增加）
```

新增一个后端（如 Apollo）= 写一个注解（@AliasFor 映射通用属性 + `@Import(XxxLoader)`）+ 写一个 Loader（`getAnnotationType` 样板、`resolveResources` 拉取、需要刷新时 `configureRefresher` 注册监听）。核心模块零改动。完整示例见 `06-thread-model-and-labs.md` §2。

### 4.2 first / before / after 定位语义

`addPropertySource()` 的优先级：`first=true` 直接首位（忽略 before/after）→ `after` 目标存在则插其后 → `before` 目标存在则插其前 → 否则末尾。**目标 PropertySource 不存在时不报错**，只打 debug 日志然后继续往下走——注解写错位置名不会炸启动，这是故意的宽容。

注意两层优先级：**同一个注解内多 dataId 先声明先赢**（Composite 取第一个非 null，`flatten` 用 `putIfAbsent`）；`first/before/after` 只决定 **Environment 跨源**的顺序，不改变同一个 Composite 内部顺序。

### 4.3 格式判定：文件名缺失怎么办

`ResourcePropertySources.load()` 先看 `resource.getFilename()`：`.yml`/`.yaml` 走 SnakeYAML，`.xml` 走 `Properties.loadFromXML`，其余走 Properties。Nacos 下发的内容包成 `ByteArrayResource`，**没有文件名**——此时用 dataId（`resourceValue`，如 `order-service.yaml` / `order-service.xml`）判定格式。所以 dataId 的后缀不是装饰，是格式信息。

SnakeYAML 不在 classpath 时抛 `LinkageError`，这里转成 `IOException("YAML support requires SnakeYAML...")`——缺依赖时的报错是可读的。

### 4.4 编码一致性（当前实现）

Nacos 内容 `String → byte[]`（`resolveResources`）与 `byte[] → Properties`（`load` 的 `EncodedResource`）**必须用同一个 encoding**，当前实现是全链路透传注解的 `encoding` 属性；测试 `testPropertiesEncoding` 用 GBK 内容钉住往返一致。注意作用域：**`encoding` 只对 `.properties` 生效**；YAML 固定 UTF-8（SnakeYAML 行为）；XML 由 XML 声明决定（`Properties.loadFromXML` 不读 `encoding` 参数）。

### 4.5 diff 语义：只对"真变化"发事件

`diff()` 把新旧 flatten 后的 Map 取并集逐属性 `Objects.equals`：新增、删除、修改都算变化并记新旧值；**完全无变化 → 不发事件；重载失败 → 不发事件**。`ConfigChangePublisher.publish()` 还有第二道空检查。两处断言在测试里都被钉住（`testRefreshWithUnchangedContentPublishesNothing`、`testPublishWithEmptyChangesIsSkipped`）。

### 4.6 线程安全：锁内更新、锁外发布（但事件仍是同步的）

Nacos 客户端通知线程调 `refresh()`：`doRefresh()` 全程 `synchronized`（重载 → diff → 更新 `resourceSources` → `rebuildAndReplace`），算出的 changes 出锁后才 `publish()`——**监听器永远不在持锁状态下执行**，所以不会占锁阻塞其他刷新，也不会因监听器回调 `refresh()` 而死锁。`getResourceValues()` 返回快照而非 live 视图。

**关键修正**：锁外 ≠ 异步。Spring 事件默认同步，`publishEvent` 在当前线程依次调用监听器；监听器仍在 **Nacos 客户端通知线程**上执行，慢监听器会阻塞这次通知所在的线程（只是不占锁）。要真正异步，需给 `ApplicationEventMulticaster` 配 `TaskExecutor` 或在监听器内异步；实测与方案见 `06-thread-model-and-labs.md` §1。

### 4.7 属性读取兜底：三方注解不必写全通用属性

`getString/getBoolean/getStringArray()` 在后端注解**没声明**某个通用属性时回退默认值；`getStringArray` 还兼容 `Object[]`。测试 `testBackendAnnotationWithoutOptionalAttributes` 专门覆盖"只写 value 的三方注解也能工作"。

### 4.8 ConfigService 缓存：原子创建与 key 设计（当前实现）

`configServiceCache` 以 `地址|命名空间|用户名|密码哈希` 为 key，用 `computeIfAbsent` 原子创建（受检 `NacosException` 用私有运行时异常包装后还原抛出），避免并发先查后建创建多个客户端。密码必须进 key：同地址/命名空间/用户名但不同密码不能复用一个 `ConfigService`；只存哈希，不把明文放进 key。另有 JVM shutdown hook 统一 `shutDown()` 全部缓存客户端。测试 `testConfigServiceCacheKeyIncludesPassword` 钉住“同密码复用、不同密码新建”。

### 4.9 统一事件模型

`ConfigPropertiesChangedEvent` 构造即断言（sourceName 非空、changes 非空），变更列表不可变；查询 API：`isChanged(name)`、`getChange(name)`、`getChangedPropertyNames()`、`getNew/OldProperties()`。`sourceName` 区分来源：声明式路径是属性源名，环境适配是 `"environment"`——后者的 `oldValue` 恒为 null（`EnvironmentChangeEvent` 只带属性名，见 §2.3）；属性被删除或无法解析时 `newValue` 也为 null，适配器无法区分“删除”和“未知”。

---

## 5. 与现有方案的关系

| | Spring `@PropertySource` | Spring Cloud 原生刷新 | PolyConfig |
|---|---|---|---|
| 配置来源 | classpath 文件 | 配置中心（各客户端自管） | 配置中心（统一抽象） |
| 声明方式 | 注解 | 无 | 注解（后端注解 + @AliasFor） |
| 热更新 | 不支持 | `@RefreshScope` / `EnvironmentChangeEvent` | 自动刷新 + 统一事件（含新旧值） |
| 变更感知 | 无 | 仅属性名 | 属性名 + 新旧值 + 来源 |
| 后端扩展 | - | 各客户端独立 | Loader 模板，新增后端实现 2 个抽象方法 + 可选刷新钩子 |

定位：**不替代各配置中心客户端**，只统一"加载声明"与"变更事件"两件事。Apollo / etcd 在 Roadmap。

---

## 6. 工程陷阱簿（当前实现与边界）

1. **ConfigService 缓存**：`computeIfAbsent` 原子创建 + shutdown hook；key 必须含 `地址|命名空间|用户名|密码哈希`，否则不同密码会复用同一客户端（见 §4.8）。
2. **编码作用域**：全链路透传 `encoding` 只对 properties 有效；YAML 固定 UTF-8；XML 由声明决定（见 §4.4）。
3. **双事件重复**：同时用声明式加载与 Spring Cloud 标准刷新（两条路径都启用）时，同一次变更可能产生两个事件（source 分别为属性源名与 `"environment"`）——消费端按 `source` 过滤或做幂等。
4. **environment 来源信息降级**：`oldValue` 恒 null；属性被删除/无法解析时 `newValue` 也 null。以 `getNewProperties()` 为准，需要可靠旧值就用声明式路径。
5. **YAML/XML 编码**：`encoding` 对 yml 无效；XML 也不读 `encoding`，由 XML 声明决定。
6. **Nacos 监听线程**：`getExecutor()` 返回 null 时，回调在 **Nacos 客户端通知线程**（日志：`execute in nacos thread`）同步执行。`refresh()` 锁内只做状态更新、事件锁外发，避免监听器持锁执行；但事件默认同步，慢监听器仍会阻塞这次通知所在的线程，**不是“推送线程不被拖住”**。
7. **自定义发布器 Bean 的边界**：自动配置的 `ConfigChangePublisher` 只服务环境适配；声明式 Loader 在解析期自建发布器（`createConfigChangePublisher:242`），自定义 Bean 不改变 `@NacosPropertySource` 路径（见 06 §4.2）。

---

## 7. 动手学习路径

**步骤 0 — 跑起来**
```bash
cd /data/workspace/source-code/poly
mvn -f polyconfig/pom.xml test          # 30 个测试，全绿
```

**步骤 1 — 读 6 个测试类（每个对应一类行为）**
- `ConfigPropertiesChangedEventTest`：事件不可变、构造校验、`isChanged/getChange/新旧值`
- `ConfigChangePublisherTest`：发布与空跳过
- `ResourcePropertySourcesRefresherTest`：diff/替换/无变化不发事件/线程安全
- `PropertySourceExtensionLoaderTest`：加载/首位/自动刷新/资源缺失/YAML/XML/编码/多资源优先级/三方注解缺省属性
- `PolyConfigAutoConfigurationTest` + E2E（含真实 ApplicationContext 端到端、环境适配、声明式+环境适配双路事件）
- `NacosPropertySourceLoaderTest`：Nacos 加载、XML、自动刷新、ConfigService 缓存 key

**步骤 2 — 四个实验**
- 实验 A：实现一个最小内存 Map 后端（注解 + Loader 两个抽象方法），验证"新增后端不动现有代码"；
- 实验 B：`before/after` 指向存在与不存在的 PropertySource，对比 Environment 顺序；
- 实验 C：同时启用声明式加载与环境适配，观察同一次变更的两个 source；
- 实验 D：推送无变化内容 → 无事件；推送坏内容 → error 日志且无事件。

**步骤 3 — 源码阅读顺序**
`event/`（值对象，30 分钟）→ `env/`（load/flatten/refresher，1 小时）→ `context/annotation/`（元注解 + 模板，1.5 小时，对照 §4）→ `nacos/`（后端实现，1 小时）→ `boot/`（装配，20 分钟）。

---

## 8. 理解自测（能口头答出 = 真吃透）

1. `@NacosPropertySource` 标在 `@Configuration` 上后，是谁、在什么时候实例化 Loader 并调用 `selectImports`？
2. `selectImports` 为什么返回空数组？（答：只借触发时机，不导入配置类）
3. 属性源 name 的默认生成规则是什么？
4. `after` 指向的 PropertySource 不存在时，启动报错吗？最终位置是哪里？
5. Nacos 下发的 `ByteArrayResource` 没有文件名，格式怎么判定？（properties/YAML/XML 各走哪条分支？）
6. 基类 `autoRefreshed` 默认 false，`@NacosPropertySource` 为什么默认 true？
7. 推送了无变化的内容 / 重载失败时，发事件吗？测试依据是哪个？
8. 为什么事件要在锁外发布？（答：监听器不在持锁状态执行，避免阻塞其他刷新/死锁；**但事件仍同步，监听器仍占用 Nacos 客户端通知线程**）
9. `source="environment"` 的事件旧值是什么？为什么？new 什么时候也为 null？
10. 声明式加载与标准刷新同时用时，消费端怎么处理重复事件？
11. 新增 Apollo 后端要写哪几个方法？要改现有类吗？（答：1 个注解 + 2 个抽象方法 + 可选 `configureRefresher`；不改核心类）
12. 简历里三句话分别对应哪段代码？（见 §9）

## 9. 简历 bullet ↔ 代码对照

| 简历原话 | 对应实现 | 证据（测试） |
|---|---|---|
| 声明式配置：注解接入、自动刷新、优先级定位、多格式 | `NacosPropertySource` + `NacosPropertySourceLoader` + §4.2 定位 + §4.3 多格式 | `testLoadPropertySource` / `testFirstPropertySource` / `testYamlConfigContent` / `testXmlConfigContent` / `testPropertiesEncoding` / `testMultipleResourcesFirstDeclaredWins` |
| 统一变更事件：属性粒度新旧值 + 来源，兼容原生刷新 | `ConfigPropertiesChangedEvent` + `Refresher.diff` + `EnvironmentConfigChangeListener` | `testRefreshUpdatesEnvironmentAndPublishesEvent` / `testRefreshWithUnchangedContentPublishesNothing` / `testEnvironmentChangeEventIsTranslated` |
| 多后端扩展：新增配置中心仅需实现两个抽象方法（可选刷新钩子） | `PropertySourceExtensionLoader` 模板（`getAnnotationType` + `resolveResources` + 可选 `configureRefresher`） | `testBackendAnnotationWithoutOptionalAttributes` / `testConfigServiceCacheKeyIncludesPassword` |

下游验证：`polydata-jdbc-spring-boot` 的 `PropagatingDynamicJdbcConfigChangedEventListener` 正是这个统一事件的消费者——学 PolyData 篇时会看到"配置一改、数据源重建"的完整闭环。
