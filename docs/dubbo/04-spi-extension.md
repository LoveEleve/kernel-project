# 04 为什么处处可扩展？——SPI / Adaptive / Wrapper

> 定位：Dubbo 系列第 04 篇——**问题驱动**；源码域 D-2（SPI、Adaptive 与扩展装配）作为覆盖清单。
> lab：`scripts/05-spi-extension.sh`；证据：`dubbo-lab/evidence/05-spi-extension.log`。
> 课程对照：《Dubbo源码剖析与实战》14 SPI / 15 Wrapper / 16 Compiler / 17 Adaptive / 18 实例注入。

---

## 一、先看实验：加两个文件，框架"自己长了眼睛"

给 lab 加两个类（两端各一个 Filter）+ 两个 SPI 文件：

```
provider: trace=com.myxhs.dubbo.lab.provider.filter.TraceFilter
consumer: clientTrace=com.myxhs.dubbo.lab.consumer.filter.ClientTraceFilter
```

**没有改一行框架代码**，重新跑直连调用，两端日志自动多出：

```
consumer 侧（client-trace）:
[client-trace] 发起调用 sayHello（SPI 自定义 Filter 生效）
[client-trace] 调用完成 sayHello in 2346ms
provider 侧（trace）:
[trace] provider 收到 sayHello（SPI 自定义 Filter 生效）
[trace] provider 完成 sayHello in 163ms
```

同时打印框架内部装配结果：

```
[spi] Protocol adaptive class = org.apache.dubbo.rpc.Protocol$Adaptive
[spi] Protocol 'dubbo' instance class = org.apache.dubbo.rpc.protocol.InvokerCountWrapper
[spi] Filter supported names = [..., trace, ...]
```

三个现象对应 SPI 的三件事：**按名字加载**（`trace` 出现在列表里）、**自适应**（`Protocol$Adaptive`）、**包装链**（`InvokerCountWrapper`）。这篇回答：这套"微内核 + 插件"是怎么实现的。

---

## 二、朴素答案"工厂 + if-else"死在哪

假设 Dubbo 内部这样选实现：

```java
if ("dubbo".equals(protocolName)) {
    protocol = new DubboProtocol();
} else if ("tri".equals(protocolName)) {
    protocol = new TripleProtocol();
} else if ("rest".equals(protocolName)) {
    ...
}
```

协议、序列化、负载均衡、集群容错、过滤器……每一类扩展都这么写，后果：

| 问题 | 后果 |
|---|---|
| 新增一个实现 | 必须改框架源码 → 框架重新发布 |
| 三方想扩展 | 只能 fork 框架 |
| 想按环境切换 | 分支越堆越多 |

**所以框架必须"微内核 + 插件"**：核心只定义"扩展点接口 + 装配规则"，具体实现由外部注册、运行时选择。JDK 自带 SPI（`ServiceLoader`）就是干这个的——但 Dubbo 没有直接用，因为它不够。

---

## 三、JDK SPI 差在哪

| 能力 | JDK `ServiceLoader` | Dubbo SPI |
|---|---|---|
| 配置文件 | `META-INF/services/<接口全名>`：**只有类名列表** | `META-INF/dubbo/<接口全名>`：**`name=class` 键值对** |
| 选择 | 只能遍历/全量 | 按名字精确选：`getExtension("dubbo")` |
| 实例管理 | 每次遍历新建 | 单例缓存（按 class） |
| 依赖注入 | 无 | setter 注入（IOC） |
| 包装增强 | 无 | Wrapper 包装链 |
| 条件激活 | 无 | `@Activate`（group/value/order） |
| 自适应选择 | 无 | `@Adaptive` + 代码生成 |

一句话：**JDK SPI 只解决"发现"，Dubbo SPI 解决"发现 + 选择 + 装配 + 增强 + 自适应"。**

---

## 四、Dubbo SPI 的五件事

### 4.1 键值对加载与分类

Dubbo 按三个位置加载（`DubboLoadingStrategy.java:28`、`ServicesLoadingStrategy.java:28`）：

```java
        return "META-INF/dubbo/";
        return "META-INF/services/";
```

（后者兼容 JDK SPI。）

（另有内部目录 `META-INF/dubbo/internal/` 供框架自身扩展使用，`DubboInternalLoadingStrategy.java:28`。）加载时按类型分流，`loadClass`（`ExtensionLoader.java:1264`，节选）：

```java
        if (clazz.isAnnotationPresent(Adaptive.class)) {
            cacheAdaptiveClass(clazz, overridden);
        } else if (isWrapperClass(clazz)) {
            cacheWrapperClass(clazz);
        } else {
            if (StringUtils.isEmpty(name)) {
                name = findAnnotationName(clazz);
                ...
            }
            ...
                cacheActivateClass(clazz, names[0]);
                for (String n : names) {
                    cacheName(clazz, n);
                    saveInExtensionClass(extensionClasses, clazz, n, overridden);
                }
```

三类扩展在加载期就被识别：`@Adaptive` 类、Wrapper 类、普通扩展（带名字和 `@Activate` 信息）。我们加的 `trace=...` 走第三条路。

### 4.2 单例 + 懒加载 + 注入

`getExtension`（`ExtensionLoader.java:548`）先查缓存；真正创建在 `createExtension`（`:772`，节选）：

```java
            T instance = (T) extensionInstances.get(clazz);
            if (instance == null) {
                extensionInstances.putIfAbsent(clazz, createExtensionInstance(clazz));
                instance = (T) extensionInstances.get(clazz);
                instance = postProcessBeforeInitialization(instance, name);
                injectExtension(instance);
                instance = postProcessAfterInitialization(instance, name);
            }
```

- **单例**：`extensionInstances` 按 class 缓存——同一 Loader（Scope）内一份；
- **注入**：`injectExtension`（`:854`）遍历 setter，按类型/属性名注入其他扩展（这就是"实例注入"，课程 18）。

### 4.3 Wrapper：包装链

判定很简单——**只要有一个"以扩展接口为参数的构造器"**（`ExtensionLoader.java:1408`）：

```java
    protected boolean isWrapperClass(Class<?> clazz) {
        Constructor<?>[] constructors = clazz.getConstructors();
        for (Constructor<?> constructor : constructors) {
            if (constructor.getParameterTypes().length == 1 && constructor.getParameterTypes()[0] == type) {
                return true;
            }
        }
        return false;
    }
```

创建时把普通实现**层层包起来**（`createExtension`，节选）：

```java
            if (wrap) {
                List<Class<?>> wrapperClassesList = new ArrayList<>();
                if (cachedWrapperClasses != null) {
                    wrapperClassesList.addAll(cachedWrapperClasses);
                    wrapperClassesList.sort(WrapperComparator.COMPARATOR);
                    Collections.reverse(wrapperClassesList);
                }
                ...
                        if (match) {
                            instance = injectExtension(
                                    (T) wrapperClass.getConstructor(type).newInstance(instance));
```

实验里 `getExtension("dubbo")` 返回的是 `InvokerCountWrapper`——**包装链的最外层**。按 SPI 注册，Protocol 的 Wrapper 实际有六个（分布在各模块的 `META-INF/dubbo/internal/org.apache.dubbo.rpc.Protocol` 文件中）：

| Wrapper | @Activate order | 职责 |
|---|---|---|
| `InvokerCountWrapper` | `Integer.MIN_VALUE + 1000` | 调用计数（最外层） |
| `ProtocolFilterWrapper` | 100 | 挂 Filter 链 |
| `ProtocolListenerWrapper` | 200 | Exporter/Invoker 监听 |
| `ProtocolSerializationWrapper` | 默认 | 序列化检查 |
| `ProtocolSecurityWrapper` | 200 | 序列化安全 |
| `QosProtocolWrapper` | 200 | QoS 命令 |

它们按 `WrapperComparator` 排序后层层包住真正的 `DubboProtocol`——Filter/监听/计数/安全这些横切能力就是这样"无侵入"插入的。

### 4.4 @Activate：自动激活

不是所有扩展都要手动 `getExtension`。`getActivateExtension`（`:343`）按 `@Activate` 的 **group**（PROVIDER/CONSUMER）和 **order** 过滤/排序——我们的 `TraceFilter` 只标了 `@Activate(group = PROVIDER)`，就自动进了提供方的 Filter 链；消费方的 `ClientTraceFilter` 同理。**这就是"加个文件就生效"的机制**。

### 4.5 @Adaptive：运行时选择 + 代码生成

`@Adaptive` 有两种形态：

- **标在类上**：该类就是自适应实现（框架里少数几个，如 `AdaptiveCompiler`）；
- **标在接口方法上**：没有自适应类时，**框架现场生成一个**（`ExtensionLoader.java:1447`、`:1455`，节选）：

```java
    private Class<?> getAdaptiveExtensionClass() {
        getExtensionClasses();
        if (cachedAdaptiveClass != null) {
            return cachedAdaptiveClass;
        }
        return cachedAdaptiveClass = createAdaptiveExtensionClass();
    }

    private Class<?> createAdaptiveExtensionClass() {
        ...
        String code = new AdaptiveClassCodeGenerator(type, cachedDefaultName).generate();
        org.apache.dubbo.common.compiler.Compiler compiler = extensionDirector
                .getExtensionLoader(org.apache.dubbo.common.compiler.Compiler.class)
                .getAdaptiveExtension();
        return compiler.compile(type, code, classLoader);
    }
```

生成器 `AdaptiveClassCodeGenerator.generate`（`:106`）按模板产出 `Xxx$Adaptive`：方法里读 URL 参数（如 `protocol`）选扩展名，取不到就用 `@SPI` 默认名（`SPI.java:61` `value() default ""`），再 `getExtension(name)` 转发。实验里打印的 `Protocol$Adaptive` 就是它——**接口没有任何实现类时，框架用"生成代码"补上选择逻辑**。

---

## 五、实验对照表

| 观测（证据） | 对应机制 |
|---|---|
| `Filter supported names = [..., trace, ...]` | 键值对加载 + 名字注册（4.1） |
| 两端 Filter 自动执行 | `@Activate` + Wrapper 链（4.3/4.4） |
| `Protocol 'dubbo' instance = InvokerCountWrapper` | Wrapper 包装链（4.3） |
| `Protocol$Adaptive` | `@Adaptive` + 代码生成（4.5） |

---

## 六、生产陷阱

1. **同名扩展覆盖**：多个 jar 注册同一个名字时，加载顺序决定谁生效（`overridden` 逻辑）——排查"配置生效但行为不符"先看 SPI 文件；
2. **Wrapper 顺序**：`WrapperComparator` + `@Activate(order)` 决定包装顺序，顺序错会导致"日志/鉴权/限流"执行时机不符预期；
3. **Scope/ClassLoader**：`ExtensionLoader` 缓存与 ScopeModel 绑定，多应用模型/热部署下用错 Loader 会"类加载泄漏"或拿到旧实现；
4. **注入失败是静默的**：`injectExtension` 的 catch 只打日志——扩展可能拿到 null 依赖，直到运行时才炸；
5. **`@Activate` 条件不满足**：`value`/`group` 不匹配时扩展"注册了但不激活"，别只看 SPI 文件有没有写。

---

## 附录 A：面试速答（附带）

> Dubbo 的扩展体系 = 微内核 + SPI：核心只定义扩展点接口，实现在 `META-INF/dubbo/<接口>` 里以 `name=class` 注册。相比 JDK SPI（只有类名列表、只能全量遍历），Dubbo SPI 支持按名字选、单例缓存、setter 注入、Wrapper 包装链、@Activate 自动激活、@Adaptive 自适应。Wrapper 的判定是"有一个以扩展接口为参数的构造器"，创建时层层包装（Protocol 最终拿到的是 InvokerCountWrapper/ProtocolFilterWrapper 这类最外层）；@Adaptive 标在方法上且无实现类时，框架用 AdaptiveClassCodeGenerator 现场生成 `Xxx$Adaptive`，按 URL 参数选实现、缺省用 @SPI 默认名。生产坑：同名覆盖、Wrapper 顺序、Scope/ClassLoader 缓存、注入失败静默、@Activate 条件不匹配。

## 附录 B：实验与复现

```bash
cd dubbo-lab
export JAVA_HOME=/env/javaEnv/jdk17
mvn -DskipTests install
bash scripts/05-spi-extension.sh     # 输出见 evidence/05-spi-extension.log
```

| 观测点 | 结果 |
|---|---|
| 自定义扩展加载 | `Filter supported names` 含 `trace` |
| 两端 Filter | `[client-trace]` / `[trace]` 各两次（sayHello、slow） |
| Adaptive | `org.apache.dubbo.rpc.Protocol$Adaptive` |
| Wrapper | `Protocol 'dubbo' instance class = org.apache.dubbo.rpc.protocol.InvokerCountWrapper` |

## 附录 C：源码坐标（Dubbo 3.3.3 + lab）

| 内容 | 坐标 |
|---|---|
| SPI 注解/默认名 | `dubbo-common/.../extension/SPI.java:61` |
| 加载策略（目录） | `DubboLoadingStrategy.java:28`（`META-INF/dubbo/`）、`DubboInternalLoadingStrategy.java:28`（`META-INF/dubbo/internal/`）、`ServicesLoadingStrategy.java:28`（`META-INF/services/`） |
| 加载分类 | `ExtensionLoader.java:1264`（`loadClass`：Adaptive/Wrapper/普通） |
| 取扩展/创建/注入 | `ExtensionLoader.java:548`（`getExtension`）、`:772`（`createExtension`：单例 + Wrapper 链）、`:854`（`injectExtension`） |
| Wrapper 判定/排序 | `ExtensionLoader.java:1408`（`isWrapperClass`）、`:791`（`WrapperComparator` 排序） |
| 自动激活 | `ExtensionLoader.java:343`（`getActivateExtension`） |
| 自适应/代码生成 | `ExtensionLoader.java:1447`（`getAdaptiveExtensionClass`）、`:1455`（`createAdaptiveExtensionClass`）；`AdaptiveClassCodeGenerator.java:106`（`generate`） |
| lab | `lab-provider/.../filter/TraceFilter.java`、`lab-consumer/.../filter/ClientTraceFilter.java` + 两个 `META-INF/dubbo/org.apache.dubbo.rpc.Filter`；`scripts/05-spi-extension.sh` |
| 课程对照 | 《Dubbo源码剖析与实战》14 SPI / 15 Wrapper / 16 Compiler / 17 Adaptive / 18 实例注入 |
