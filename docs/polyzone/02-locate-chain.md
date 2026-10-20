---
title: "02-原理一：区域定位链"
series: "PolyZone 异地多活路由系列"
previous: "01-what-is-polyzone.md"
next: "03-preference-algorithm.md"
source_refs:
  - "已核：CompositeZoneLocator（:44/AnnotationAwareOrderComparator.sort:55/首成功循环:85/缓存首返:71-72/fastFail:81-109/setProperty:119）/ 四定位器 DEFAULT_ORDER：ECS 文件:51=5、ECS Task V4:50=10、EC2:48=15、Default:40=20；EC2 supports() 恒 true:56-58；timeout 配置:87"
---

# 02-原理一：区域定位链

## §0 它干了什么（例子先行）

容器启动时，"我在哪个区"是怎么问出来的（CompositeZoneLocator:44 源码链）：

```
CompositeZoneLocator.locate(environment):70（以下四层在引入 polyzone-aws 时成立；不引入时只有 Default）
  → sort：按 Ordered 排序（:55 AnnotationAwareOrderComparator）
  → for 依序试（:85）：
      ECS 文件(Order 5)      有 ECS_CONTAINER_METADATA_FILE？→ 命中即返回
      ECS Task V4(Order 10)  有 METADATA_URI_V4？            → 命中即返回
      EC2(Order 15)          问 169.254.169.254              → 命中即返回
      Default(Order 20)      读 Spring 属性 polyzone.zone     → 兜底 defaultZone
  → 结果两处落盘：缓存 this.zone（下次直接返:71-72）
                 + System.setProperty("polyzone.current-zone"):119
```

- **它干了什么**：**责任链式探测，首个成功者胜**——环境差异被四层 Order 吃掉，上层只问 `locate()`。
- **本篇回答**：四层顺序为什么这么排？缓存与系统属性给谁看？非 AWS 环境那个 3 秒坑从哪来？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **CompositeZoneLocator** | 组合定位器——排序(:55)+依次试(:85)+落败处理(日志:90/:100，全败:113) |
| **Order 5/10/15/20** | ECS 文件 < ECS Task V4 < EC2 < 属性兜底——**越具体越优先，兜底最后** |
| **缓存首返** | 首次成功后 `this.zone` 定死（:71-72 直接返回）——**运行期不再探测** |
| **系统属性** | `polyzone.current-zone`:119——给**进程外**看（脚本/日志/排障），进程内走 ZoneContext（04 篇） |
| **fast-fail** | 全部失败时：默认只警告(:113 继续跑)；`fast-fail=true` 抛异常阻止启动(:109) |

## §2 关键推导（两问）

**① 为什么 EC2 排在属性兜底之前、却又有 3 秒坑？**
顺序设计没错：**云上元数据比手工配的准**（自动漂移到新可用区）。坑在 `Ec2AvailabilityZoneEndpointZoneLocator.supports()` **恒 true**（:56-58）——它不在"支持性"上把关，直接开问 `169.254.169.254`，非 AWS 环境**直到超时（默认 3s）**才轮到属性定位（仅在引入 `polyzone-aws` 时出现）。README 注意事项原话：非 AWS 生产**显式配 `polyzone.zone`**（正常启动由 `ZoneContextChangedListener` 直接采用，不走定位链），或接受首次同步的 3s 开销。

**② 缓存首返换来什么、失去什么？**
得：零运行期探测开销（微秒级 locate）。失：**运行中环境变化不感知**——要改区域不走定位链，走**配置中心切换**（04 篇的正路）。**分工明确**：定位管"出生地"，切换管"迁徙"。

**同族回链**：四层 Order 的"越具体越优先+兜底最后"，与 polyconfig 排位（first/before/after=覆盖优先级）是同一设计语言——**家族统一的"顺序即优先级"**。

## §3 掌握卡（2 条）

1. **定位链**：sort→依序试→首胜缓存+系统属性——Order 5/10/15/20 = 具体到兜底。
2. **3 秒坑的机制**：supports 恒 true → 无条件问元数据端点 → 超时回退——**处方=非 AWS 显式配 zone，或不引入 `polyzone-aws`**（README 注意事项 1）。

## §4 边界（一句话翻篇）

- AWS 三类端点协议细节 → 翻篇（知道 URI 环境变量即可）。
- spring.factories 扩展定位器 → README 设计要点一句，够用。
- HttpUtils 实现 → 平平无奇，翻篇。

## §5 Self-Test（3 题）

- Q1：四层 Order 各是谁？为什么这个顺序？
- Q2：缓存首返的得与失？改区域走哪条路？
- Q3：非 AWS 环境 3 秒坑怎么来的？怎么避免？

## §6 衔接

- 定位收口；下篇 03 同区域优先算法：ZonePreferenceFilter 十步决策与"偏好永不致死"。
