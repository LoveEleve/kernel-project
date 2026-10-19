# 03 服务怎么被"发布"和"订阅"？——注册中心与动态目录

> 定位：Dubbo 系列第 03 篇——**问题驱动**（consumer 怎么知道 provider 在哪）；源码域 D-4（注册中心与服务发现）+ D-1（export/refer）作为覆盖清单。
> lab：`scripts/04-registry-nacos.sh`（Nacos 复用现有容器）；证据：`dubbo-lab/evidence/04-registry-nacos.log`。
> 课程对照：《Dubbo源码剖析与实战》19 发布流程 / 20 订阅流程 / 25 注册扩展。

---

## 一、先看实验结果

第 02 篇的直连模式里，provider 的地址写死在 consumer 的配置里（`dubbo://127.0.0.1:20880`）。这一篇把它换成注册中心，做一个实验：**consumer 不重启，provider 杀掉再拉起**（call#1-3 正常；call#4 起 provider 被 kill；call#10 起 provider 已重启）：

```
[lab-consumer] call#1 -> OK: hello loop-1 from lab-provider
[lab-consumer] call#2 -> OK: hello loop-2 from lab-provider
[lab-consumer] call#3 -> OK: hello loop-3 from lab-provider
[lab-consumer] call#4 -> FAIL: RpcException
[lab-consumer] call#5 -> FAIL: RpcException
[lab-consumer] call#6 -> FAIL: RpcException
[lab-consumer] call#7 -> FAIL: RpcException
[lab-consumer] call#8 -> FAIL: RpcException
[lab-consumer] call#9 -> FAIL: RpcException
[lab-consumer] call#10 -> OK: hello loop-10 from lab-provider
[lab-consumer] call#11 -> OK: hello loop-11 from lab-provider
[lab-consumer] call#12 -> OK: hello loop-12 from lab-provider
```

consumer 进程从头到尾没动过，中间它自己"发现"了 provider 消失，又"发现"了 provider 回来。这篇要回答：**这个发现是怎么发生的**。

---

## 二、朴素答案"写死 IP"死在哪

直连模式（02 篇）把地址写死在 consumer 侧。四个现实场景会让它死掉：

| 场景 | 写死 IP 的后果 |
|---|---|
| provider 扩容（加实例） | 新实例没人调用——要改所有 consumer 配置 |
| provider 缩容/下线 | 请求继续打到已下线的 IP |
| provider 故障/漂移 | 地址变了，consumer 不知道 |
| 灰度/多机房 | 想按规则分流，没有"地址清单"可操作 |

根因：**"服务地址"是会变的**，而写死的配置不会变。需要一个地方替所有 consumer 维护"当前有哪些可用地址"，并且在变化时通知它们——这就是注册中心。

---

## 三、注册中心的模型：注册 / 订阅 / 通知

三个动作，对应三方：

```
Provider ──①注册（写地址）──▶ Registry（注册中心）
Consumer ──②订阅（拉取+订变更）──▶ Registry
Registry ──③通知（地址变了推给你）──▶ Consumer
```

关键认知：**consumer 拿到的不是"一个 IP"，而是一份动态目录（Directory）**。

- 启动时：consumer 从注册中心**拉取**全量 provider 地址，构建本地目录（地址 → Invoker）；
- 运行中：provider 上下线，注册中心**推送**变更，consumer 更新目录；
- 调用时：集群层（Cluster）从目录里选一个 Invoker（第 05/07 篇展开路由与负载均衡）。

所以实验里 `call#10` 能恢复，不是因为 consumer "重试成功"，而是因为它的**目录被通知更新了**。

---

## 四、实验：Nacos 注册 + kill/restart

脚本 `scripts/04-registry-nacos.sh`，复用现有 Nacos（`127.0.0.1:8848`）：

1. provider 以 `-Dlab.registry=nacos://127.0.0.1:8848` 启动并注册；
2. Nacos 服务列表出现 **两个服务名**：

```json
{"count":2,"doms":["providers:com.myxhs.dubbo.lab.api.DemoService::","lab-provider"]}
```

一个是**接口级**服务名（`providers:<接口>:<版本>:`），一个是**应用级**服务名（`lab-provider`）。Dubbo 3 默认**双注册**（`CommonConstants.java:244` `DEFAULT_REGISTER_MODE = "all"`），兼容两代服务发现模型——**这是两条并行的注册路径**：接口级走 `NacosRegistry.doRegister`（`:178`），应用级走 `NacosServiceDiscovery.doRegister`（`:94`），下文分别看。

3. consumer 循环调用（12 次 × 2s），中途 kill → 重启 provider，得到第一节的 OK→FAIL→OK；
4. 收尾观察：provider 停止后，Nacos 服务列表**仍显示服务**——临时实例的注销/过期有延迟（生产上排查"已下线还在被调用"时要注意这个窗口）。

---

## 五、源码：发布与订阅的主链

### 5.1 发布三环节：配置 → 导出 → 注册

第 02 篇讲完"配置"，这节看后两环。`RegistryProtocol.export`（`RegistryProtocol.java:270`，节选）：

```java
    public <T> Exporter<T> export(final Invoker<T> originInvoker) throws RpcException {
        URL registryUrl = getRegistryUrl(originInvoker);
        // url to export locally
        URL providerUrl = getProviderUrl(originInvoker);

        // Subscribe the override data
        ...
        providerUrl = overrideUrlWithConfig(providerUrl, overrideSubscribeListener);
        // export invoker
        final ExporterChangeableWrapper<T> exporter = doLocalExport(originInvoker, providerUrl);

        // url to registry
        final Registry registry = getRegistry(registryUrl);
        final URL registeredProviderUrl = customizeURL(providerUrl, registryUrl);

        // decide if we need to delay publish (provider itself and registry should both need to register)
        boolean register = providerUrl.getParameter(REGISTER_KEY, true) && registryUrl.getParameter(REGISTER_KEY, true);
        if (register) {
            register(registry, registeredProviderUrl);
        }
        ...
```

三件事一次看清：**本地导出**（`doLocalExport`，真正打开 20880 端口）→ **拿注册中心**（`getRegistry`）→ **注册**（`register(...)`，内部 `registry.register(registeredProviderUrl)` `:256`）。注意 provider 也会"订阅"（override 配置），用于动态覆盖。

`registerStatedUrl`（`RegistryProtocol.java:264`）把"注册状态"挂到 ProviderModel 上（供运维/诊断使用）：

```java
    private void registerStatedUrl(URL registryUrl, URL registeredProviderUrl, boolean registered) {
        ProviderModel model = (ProviderModel) registeredProviderUrl.getServiceModel();
        model.addStatedUrl(new ProviderModel.RegisterStatedURL(registeredProviderUrl, registryUrl, registered));
    }
```

具体注册动作落在注册中心实现上。`NacosRegistry.doRegister`（`NacosRegistry.java:178`，节选）：

```java
    public void doRegister(URL url) {
        try {
            if (PROVIDER_SIDE.equals(url.getSide()) || getUrl().getParameter(REGISTER_CONSUMER_URL_KEY, false)) {
                Instance instance = createInstance(url);

                Set<String> serviceNames = new HashSet<>();
                // by default servicename is "org.apache.dubbo.xxService:1.0.0:"
                String serviceName = getServiceName(url, false);
                serviceNames.add(serviceName);
                ...
                for (String service : serviceNames) {
                    namingService.registerInstance(service, getUrl().getGroup(Constants.DEFAULT_GROUP), instance);
                }
```

`createInstance(url)` 把 Dubbo 的 URL 转成 Nacos 的 `Instance`（ip/port/metadata），`registerInstance` 写入注册中心。注意这里只产出**接口级**服务名；**应用级**服务名走另一条路——`NacosServiceDiscovery.doRegister`（`NacosServiceDiscovery.java:94`）：

```java
    public void doRegister(ServiceInstance serviceInstance) {
        execute(namingService, service -> {
            Instance instance = toInstance(serviceInstance);
            service.registerInstance(instance.getServiceName(), group, instance);
        });
    }
```

同一个进程、同一份实例信息，从两条路径各注册一次——这就是实验里看到两个服务名的原因。

### 5.2 订阅与通知：目录如何更新

consumer 侧从 `ReferenceConfig.createProxy`（02 篇）走到 `RegistryProtocol.refer`（`RegistryProtocol.java:556`），最终 `doRefer`（`:577`）创建 **Cluster + RegistryDirectory**：

```java
        Cluster cluster = Cluster.getCluster(url.getScopeModel(), qs.get(CLUSTER_KEY));
        return doRefer(cluster, registry, type, url, qs);
```

目录收到变更后做什么？`RegistryDirectory.notify`（`RegistryDirectory.java:200`，节选）：

```java
    public synchronized void notify(List<URL> urls) {
        ...
        Map<String, List<URL>> categoryUrls = urls.stream()
                .filter(Objects::nonNull)
                .filter(this::isValidCategory)
                .filter(this::isNotCompatibleFor26x)
                .collect(Collectors.groupingBy(this::judgeCategory));
        ...
        // providers
        List<URL> providerURLs = categoryUrls.getOrDefault(PROVIDERS_CATEGORY, Collections.emptyList());
        ...
        refreshOverrideAndInvoker(providerURLs);
    }
```

注册中心的 URL 是**分类**的（providers / configurators / routers）——目录按类别处理：providers 走 `refreshOverrideAndInvoker` → `toInvokers`（`:440`）把地址列表转成 Invoker 列表；configurators/routers 更新覆盖与路由规则（第 05/07 篇展开）。

注册中心侧的订阅实现在 `NacosRegistry.doSubscribe`（`NacosRegistry.java:247`，节选）：

```java
    public void doSubscribe(final URL url, final NotifyListener listener) {
        NacosAggregateListener nacosAggregateListener = new NacosAggregateListener(listener);
        ...
        Set<String> serviceNames = getServiceNames(url, nacosAggregateListener);

        doSubscribe(url, nacosAggregateListener, serviceNames);
    }
```

内部先 `getAllInstances` **拉全量**并回调 `notifySubscriber`，再 `subscribeEventListener` **订变更**——"拉 + 订"两步，正是第三节的模型。

### 5.3 一次上下线的时序

```
provider 启动：ServiceConfig → RegistryProtocol.export → NacosRegistry.doRegister → Nacos
consumer 启动：ReferenceConfig → RegistryProtocol.refer → RegistryDirectory.subscribe
              → NacosRegistry.doSubscribe → 拉全量 → notify → toInvokers → 目录就绪
provider 下线：Nacos 感知 → 推送 → RegistryDirectory.notify → 目录移除该 Invoker
provider 上线：Nacos 感知 → 推送 → RegistryDirectory.notify → 目录新增 Invoker
```

---

## 六、生产陷阱

1. **注册成功 ≠ 可调用**：端口通、注册也在，但 service key（path/group/version）不一致时，consumer 目录里找不到匹配项——排查要看"注册 URL 的 service key"与"引用 URL 的 service key"是否一致（课程 20 的案例）；
2. **空目录与 `check=false`**：consumer 启动时无 provider，默认 `check=true` 会启动失败；配 `check=false` 能启动，但调用时抛 `No provider available`——两者都别当成"网络问题"；
3. **注册中心是新的依赖**：`FailbackRegistry` 会对失败的注册/订阅做重试；consumer 侧还有本地地址缓存（`AbstractRegistry.java:338` `loadProperties` / `:363` `getCacheUrls`，注册中心整体挂掉后仍能用最后一次目录）；
4. **注销有延迟**（本实验收尾可见）：临时实例过期/推送需要时间；**优雅下线应该"先摘流量（反注册）再停进程"**，而不是直接 kill（第 07/08 篇实测）；
5. **注册列表数量 ≠ 实际流量**：路由/覆盖会改写候选集，不能用注册数推断负载（第 05/07 篇）。

---

## 附录 A：面试速答（附带）

> consumer 找 provider 靠注册中心，三个动作：provider 注册地址、consumer 订阅（拉全量 + 订变更）、注册中心通知。consumer 本地维护的是动态目录（Directory），不是单个 IP；provider 上下线时目录被通知更新，调用从目录选 Invoker。Dubbo 3 默认双注册（`register-mode=all`）：接口级服务名 + 应用级服务名。发布流程是"配置 → 本地导出 → 注册"：`RegistryProtocol.export` 先 `doLocalExport` 开端口，再 `registry.register` 写注册中心（Nacos 里是 `registerInstance`）。注册成功不等于可调用：service key（path/group/version）不一致、空目录、注册中心故障、注销延迟都会让"注册了却调不通"。

## 附录 B：实验与复现

```bash
cd dubbo-lab
export JAVA_HOME=/env/javaEnv/jdk17
mvn -DskipTests install
bash scripts/04-registry-nacos.sh     # 输出见 evidence/04-registry-nacos.log
```

| 观测点 | 结果 |
|---|---|
| Nacos 服务列表 | `providers:com.myxhs.dubbo.lab.api.DemoService::` + `lab-provider`（双注册） |
| 调用序列 | OK×3 → FAIL×6（provider 被 kill）→ OK×3（provider 重启后目录通知恢复） |
| consumer | 全程未重启 |
| 收尾 | 服务列表仍有条目（注销延迟） |

## 附录 C：源码坐标（Dubbo 3.3.3 + lab）

| 内容 | 坐标 |
|---|---|
| 发布主链 | `dubbo-registry/.../integration/RegistryProtocol.java:270`（`export`）、`:289`（`doLocalExport`）、`:238`（私有 `register`）、`:298`（调用）、`:256`（`registry.register`）、`:264`（`registerStatedUrl`） |
| Nacos 接口级注册 | `dubbo-registry/dubbo-registry-nacos/.../NacosRegistry.java:178`（`doRegister`）、`createInstance`/`registerInstance` |
| Nacos 应用级注册 | `dubbo-registry/dubbo-registry-nacos/.../NacosServiceDiscovery.java:94`（`doRegister`） |
| consumer 本地缓存 | `dubbo-registry/.../support/AbstractRegistry.java:338`（`loadProperties`）、`:363`（`getCacheUrls`） |
| 引用主链 | `RegistryProtocol.java:556`（`refer`）、`:577`（`doRefer`，Cluster + Directory） |
| 目录通知 | `dubbo-registry/.../integration/RegistryDirectory.java:200`（`notify`）、`:274`（`refreshInvoker`）、`:440`（`toInvokers`） |
| Nacos 订阅 | `NacosRegistry.java:247`（`doSubscribe`：拉全量 + `subscribeEventListener`） |
| 双注册默认 | `dubbo-common/.../constants/CommonConstants.java:244`（`DEFAULT_REGISTER_MODE = "all"`） |
| lab | `dubbo-lab/scripts/04-registry-nacos.sh`；`ProviderApp/ConsumerApp` 的 `-Dlab.registry` / `-Dlab.loop` |
| 课程对照 | 《Dubbo源码剖析与实战》19 发布流程 / 20 订阅流程 / 25 注册扩展 |
