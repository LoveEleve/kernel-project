# 10 集群深化：条件路由 / 最少活跃 / 服务分组

> 定位：Dubbo 系列第 10 篇（补全篇）——D-5（集群容错、路由、负载均衡）深化；承接 05（调用链/LB）、07（重试/标签路由）主线。
> lab：`scripts/11-routing-lb-group.sh`；证据：`dubbo-lab/evidence/11-routing-lb-group.log`。
> 课程对照：《Dubbo源码剖析与实战》23 集群 + 《RPC实战与核心原理》10 路由策略 / 11 负载均衡 / 16 业务分组。

---

## 一、先看实验：三组对照

**① 条件路由**——同一个规则，直连与注册中心两种模式。先看直连（负面对照）：

```
直连 + rule=port=20880（期望：随机分布，规则不生效）：
  分布: A=8 B=2
```

再看 Nacos 注册中心模式（同一规则精确命中）：

```
rule=port=20880：
[lab-consumer] call#1 -> OK: hello loop-1 from lab-provider-A
[lab-consumer] call#2 -> OK: hello loop-2 from lab-provider-A
[lab-consumer] call#3 -> OK: hello loop-3 from lab-provider-A
[lab-consumer] call#4 -> OK: hello loop-4 from lab-provider-A
rule=port=20881：
[lab-consumer] call#1 -> OK: hello loop-1 from lab-provider-B
[lab-consumer] call#2 -> OK: hello loop-2 from lab-provider-B
[lab-consumer] call#3 -> OK: hello loop-3 from lab-provider-B
[lab-consumer] call#4 -> OK: hello loop-4 from lab-provider-B
无 rule（roundrobin 对照）：
[lab-consumer] call#1 -> OK: hello loop-1 from lab-provider-A
[lab-consumer] call#2 -> OK: hello loop-2 from lab-provider-B
[lab-consumer] call#3 -> OK: hello loop-3 from lab-provider-A
[lab-consumer] call#4 -> OK: hello loop-4 from lab-provider-B
```

**② 最少活跃**（A 慢：`slow.extra=2000`；B 快；10 次 `slow(200)` 每 250ms 发一个）：

```
random（对照）：
  分布: A=5 B=5
leastactive（未配 actives）：
  分布: A=5 B=5
leastactive（actives=10，活跃数被维护）：
  分布: A=1 B=9
```

（第三组的 10 行调用明细见证据文件；分布对比是重点：不配 actives 与 random 完全一致，配了之后慢实例被避开。）

**③ 服务分组**（provider A=group-a / B=group-b）：

```
consumer group=group-a：
[lab-consumer] call#1 -> OK: hello loop-1 from lab-provider-A
[lab-consumer] call#2 -> OK: hello loop-2 from lab-provider-A
[lab-consumer] call#3 -> OK: hello loop-3 from lab-provider-A
consumer 无 group（check=false）：
[lab-consumer] sayHello(timeout-lab) -> FAIL: org.apache.dubbo.rpc.RpcException | No provider available from registry RegistryDirectory(registry: 127.0.0.1:8848)-Directory(invokers: 0[], validInvokers: 0[], invokersToReconnect: 0[]) for service com.myxhs.dubbo.lab.api.DemoService on consumer 172.18.0.1 use dubbo version 3.3.3, please check status of providers(disabled, not registered or in blocklist).
```

三组对照分别回答三个问题：**规则怎么写、leastactive 为什么"不生效"、分组和标签路由差在哪**。

---

## 二、朴素答案"路由就是选地址"死在哪

"把请求发到不同节点"听起来像一句 if-else。但生产上它要满足三件事：

| 要求 | if-else 的后果 |
|---|---|
| 规则要能**动态下发**（改规则不改代码、不重启） | 每次调流量都要发版 |
| 多条规则要能**组合**（黑白名单 + 灰度 + 机房） | 条件分支爆炸 |
| 决策要能**解释**（"注册了为什么没流量"） | 只能靠猜 |

所以 Dubbo 把"选地址"拆成 **RouterChain（路由链）+ LoadBalance（选一个）**：路由负责"候选集"，LB 负责"从候选集里选"。第 05 篇讲了 LB 的位置，这一篇补全路由的两种形态（条件/分组）和 LB 里最容易踩坑的 `leastactive`。

---

## 三、条件路由：从 URL 参数到 invoker 过滤

### 3.1 激活：`router=condition` 是一个"按名激活"的扩展

`RouterChain.buildSingleChain`（`RouterChain.java:59-79`）用 `getActivateExtension(url, ROUTER_KEY)`（`ROUTER_KEY = "router"`，`CommonConstants.java:478`）组装路由链。条件路由工厂没有 `@Activate` 注解，但 `ExtensionLoader.getActivateExtension` 有一个"按名添加"分支：URL 里点名（`router=condition`）的扩展会通过 `getExtension(name)` 直接加入（`ExtensionLoader.java:343-435`）。所以 lab 里只要在消费方参数里带上 `router=condition`，条件路由器就进链。

### 3.2 规则：`when => then`，从 URL 读取

`ConditionStateRouter` 的构造函数（`ConditionStateRouter.java:94-104`）直接从 URL 取规则：

```java
    public ConditionStateRouter(URL url) {
        super(url);
        this.setUrl(url);
        this.setForce(url.getParameter(FORCE_KEY, false));
        matcherFactories =
                moduleModel.getExtensionLoader(ConditionMatcherFactory.class).getActivateExtensions();
        this.enabled = url.getParameter(ENABLED_KEY, true);
        if (enabled) {
            init(url.getParameterAndDecoded(RULE_KEY));
        }
    }
```

`init`（`:106-125`）把规则按 `=>` 拆成 `when` / `then` 两组条件；lab 里用的是"无条件 → 只留 port=20880"：`=>port=20880`（`when` 为空 = 全部命中，`then` 过滤）。

### 3.3 执行：匹配的是"最终 invoker URL"

`doRoute`（`:205-278`）的主干：

- 提前返回全量有三个分支：路由器禁用（`:213-217`）、invoker 列表为空（`:220-224`）、`when` 不匹配（`:227-231`）；
- 否则 `result.removeIf(invoker -> !matchThen(invoker.getUrl(), url))`（`:246`）；
- **结果为空且 `force=false` → 兜底返回全部 invoker**（`:278`）。

`matchThen`/`doMatch`（`:308-330`）的匹配样本是 `url.toOriginalMap()`（`URL.java:1131-1134`），而 `toOriginalMap` 会调用 `addSpecialKeys` **补上 host/port/path 等特殊键**（`URL.java:1141-1161`）。

这解释了一个实验插曲：先用 `=>bind.port=20880` 完全不生效——`bind.port` 在注册 URL 里有，但**应用级发现重建后的 invoker URL 可能没有**；改用 `=>port=20880` 立即生效（`port` 由 URL 的端口字段兜底恒在）。**规则要匹配"最终 invoker URL 里稳定存在的键"**。

### 3.4 关键边界：直连模式没有路由链

实验①的负面对照不是摆设：**直连（`dubbo://` 显式地址）不建 RouterChain**。

- `ReferenceConfig.createInvoker` 在直连分支用 `Cluster.join(new StaticDirectory(curUrl, invokers), true)`（`ReferenceConfig.java:711`）；
- `StaticDirectory` 构造时 `routerChain` 传 `null` → `AbstractDirectory` 直接 `setRouterChain(null)`（`StaticDirectory.java:57-64`、`AbstractDirectory.java:197`）；
- `AbstractDirectory.list` 里 `if (routerChain != null)` 才走路由（`:212`）；
- `buildRouterChain()` 的调用者全是注册中心路径（`RegistryProtocol.java:665`、`RegistryDirectory.java:391`、`ServiceDiscoveryRegistryDirectory.java:585`、`MigrationInvoker.java:166`）。

所以**本地直连联调时，条件/标签路由规则永远不会生效**——"规则写了没用"的第一嫌疑人。

---

## 四、最少活跃：active 是从哪来的

`LeastActiveLoadBalance.doSelect`（`LeastActiveLoadBalance.java:41`）的算法：

1. 对每个 invoker 取活跃数：`RpcStatus.getStatus(invoker.getUrl(), methodName).getActive()`（`:64-65`）；
2. 选活跃数最小的（`:71-97`）；平票时按权重随机（`:97-113`）。

**关键问题：消费侧这个 active 是谁在加？** 不是 LB 自己，也不是框架自动——是 `ActiveLimitFilter`：`beginCount` 在调用开始（`ActiveLimitFilter.java:56`）、`endCount` 在响应/异常后（`:94`、`:110`）。而它只在 URL 带 `actives` 参数时才激活。

于是实验②出现教科书式结果：

- 不配 `actives`：所有 invoker 的 active 恒为 0 → 全平票 → **leastactive 退化成 random**（A=5 B=5，与 random 对照一模一样）；
- 配 `actives=10`：慢实例 A 的 active 持续为正 → LB 持续避开它（A=1 B=9）。

**"leastactive 不生效"十有八九是没配 `actives`**（第 08 篇发现的"`RpcStatus` 只在 executes/actives 等过滤器里维护"是同一根因的另一面）。

---

## 五、服务分组：service key 层面的隔离

provider 设 `group`（`ServiceConfig.setGroup`）、consumer 设 `group`（`ReferenceConfig.setGroup`）后，**service key**（`接口:group:version`）不同——provider 注册到不同 key，consumer 订阅对应 key。实验③：

- consumer `group=group-a`：目录里只有 A，3/3 全 A；
- consumer 不带 group：订阅的是无分组 key → 目录为空 → `invokers: 0[]` FAIL。

**分组 vs 标签路由**（第 07 篇）：

| 维度 | group/version | tag |
|---|---|---|
| 隔离层级 | **注册/订阅级**（目录里根本没有别组实例） | **路由级**（目录里有、路由时过滤） |
| 匹配不到时 | 目录为空，直接"无提供者" | 非 force 时**回退无标签实例** |
| 典型用途 | 多租户/多环境强隔离 | 灰度、蓝绿 |

多分组还有一个细节：`group="a,b"` 时 `RegistryDirectory` 会按组拆分后**合并**（`RegistryDirectory.java:379-395`）——每个组各自建链，再用 cluster 合并结果。

---

## 六、其余容错形态速览

| 策略/形态 | 行为 | 坐标 |
|---|---|---|
| Failover（默认） | 换实例重试（第 07 篇） | `FailoverClusterInvoker` |
| Failfast / Failsafe / Failback / Forking / Broadcast | 快速失败 / 忽略 / 定时重发 / 并行取先返回 / 广播 | `dubbo-cluster/.../support/*ClusterInvoker.java` |
| sticky（粘滞） | 同一调用方尽量固定同一实例 | `AbstractClusterInvoker.java:164-175` |
| Mock | 失败时走 mock 实现 | `MockClusterInvoker` / `MockClusterWrapper` |
| ZoneAware | 多注册中心/多机房优先同 zone | `support/registry/ZoneAwareClusterInvoker.java` |

---

## 七、实验解读

| 观测 | 结论 |
|---|---|
| 直连下 rule 不生效（A=8 B=2 随机） | 直连不建 RouterChain；路由只在注册中心模式 |
| Nacos 下 `=>port=20880/20881` 4/4 精确命中 | 条件规则匹配最终 invoker URL（host/port 由 addSpecialKeys 兜底） |
| leastactive 无 actives = random | active 由 `ActiveLimitFilter` 维护，必须配 `actives` |
| leastactive + actives → A=1 B=9 | 慢实例被持续避开（真实负载感知） |
| group 隔离 vs 无 group 空目录 | 分组是注册/订阅级隔离；tag 是路由级过滤 |

---

## 八、生产陷阱

1. **直连联调看不到路由效果**：`StaticDirectory` 没有 RouterChain；验证路由必须走注册中心；
2. **规则键要选稳的**：用 `host`/`port`/`application` 这类最终 URL 里必有的键；自定义注册参数（如 `bind.port`）可能被应用级发现重建丢掉；
3. **规则不命中 ≠ 生效**：`when` 不匹配直接放行（`:231`）、结果为空且 `force=false` 兜底放行全部（`:278`）——"写了规则没效果"先检查 key 和 `force`；
4. **leastactive 必须配 `actives`**：否则退化为 random（本次实验的 A=5 B=5 与 random 完全一致）；
5. **group 配错 = 空目录**：分组隔离在订阅层，报错形态是"注册了却 `No provider available`"，先对 service key（接口:group:version）。

---

## 附录 A：面试速答（附带）

> 路由链由 `RouterChain.buildSingleChain` 按 `router` 参数组装；条件路由工厂没有 @Activate，靠 URL 按名激活（`router=condition`），规则从 URL 的 `rule` 参数读取（`when => then`）。匹配样本是最终 invoker URL 的 `toOriginalMap()`（含 host/port 特殊键）；`when` 不匹配或结果为空且非 force 都会放行全部。**直连模式不建 RouterChain**（StaticDirectory 传 null，buildRouterChain 只被注册中心目录调用），所以条件/标签路由只在注册中心模式生效。leastactive 的 active 来自 `ActiveLimitFilter`（`actives` 参数激活）维护的消费侧 RpcStatus；不配 actives 时全平票，退化为 random。group/version 是 service key 的一部分（注册/订阅级隔离，配错会空目录）；tag 是路由级过滤（无匹配时非 force 回退无标签）。其余容错：Failover/Failfast/Failsafe/Failback/Forking/Broadcast、sticky、Mock、ZoneAware。

## 附录 B：实验与复现

```bash
cd dubbo-lab
export JAVA_HOME=/env/javaEnv/jdk17
mvn -DskipTests install
bash scripts/11-routing-lb-group.sh   # 输出见 evidence/11-routing-lb-group.log
# ①' 与 ③ 依赖 Nacos（docker compose up -d 或复用已有实例）
```

| 观测点 | 结果 |
|---|---|
| 条件路由（直连） | A=8 B=2（规则不生效） |
| 条件路由（Nacos） | `=>port=20880` → 4/4 A；`=>port=20881` → 4/4 B；无 rule → A,B,A,B |
| 最少活跃 | random A=5 B=5；leastactive 无 actives A=5 B=5；+actives=10 A=1 B=9 |
| 服务分组 | group-a → 3/3 A；无 group → `invokers: 0[]` FAIL |

## 附录 C：源码坐标（Dubbo 3.3.3 + lab）

| 内容 | 坐标 |
|---|---|
| 路由链组装 | `dubbo-cluster/.../RouterChain.java:59-79`；`ROUTER_KEY` `CommonConstants.java:478` |
| 按名激活 | `dubbo-common/.../extension/ExtensionLoader.java:343-435` |
| 条件路由 | `dubbo-cluster/.../router/condition/ConditionStateRouter.java:94-104`（规则来源）、`:106-125`（解析）、`:205-278`（doRoute/兜底）、`:308-330`（matchThen/doMatch） |
| 匹配样本特殊键 | `dubbo-common/.../URL.java:1131-1134`（toOriginalMap）、`:1141-1161`（addSpecialKeys） |
| 直连无路由链 | `dubbo-config/.../ReferenceConfig.java:711`；`dubbo-cluster/.../directory/StaticDirectory.java:57-64`、`:107-111`；`AbstractDirectory.java:197/:212`；调用点 `RegistryProtocol.java:665`、`RegistryDirectory.java:391` |
| 最少活跃 | `dubbo-cluster/.../loadbalance/LeastActiveLoadBalance.java:41-113` |
| active 维护 | `dubbo-rpc-api/.../filter/ActiveLimitFilter.java:51-110` |
| 服务分组 | `dubbo-common/.../config/AbstractInterfaceConfig.java:980`（setGroup）；`RegistryDirectory.java:379-395`（多分组合并） |
| 其余容错 | `dubbo-cluster/.../support/*ClusterInvoker.java`、`AbstractClusterInvoker.java:164-175`（sticky）、`support/registry/ZoneAwareClusterInvoker.java`、`support/wrapper/MockClusterInvoker.java` |
| lab | `scripts/11-routing-lb-group.sh`；`ConsumerApp -Dlab.router.rule/-Dlab.group/-Dlab.actives/-Dlab.submit.interval.ms`；`ProviderApp -Dlab.group/-Dlab.slow.extra/-Dlab.slow.echo.tag` |
