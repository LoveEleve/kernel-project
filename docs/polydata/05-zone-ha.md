---
title: "05-原理四：多活选库与容错"
series: "PolyData 动态多数据源系列"
previous: "04-hot-swap.md"
next: "06-orm-sharding.md"
source_refs:
  - "已核：DynamicJdbcConfig.getDataSourcePropertiesList:246-264（ha 分支 :249→zone 查表 :251/缺省回退 defaultZone :253-257/非 ha 返回静态表 :259-263）调用方=DataSourceConfigurationPropertiesSynthesizer:71（管道 step5）+校验器 :73-107 / PropagatingDynamicJdbcConfigChangedEventListener（supportsEventType 双事件:66/onApplicationEvent:70/配置分支 key 匹配:79-87/zone 分支:90-100+isZoneChanged 判 "zone":102-111/publish:115+）→ DynamicJdbcConfigChangedEvent → 04 篇入口 :321"
cross_refs:
  - "polyzone ②（ZoneContext/切换链/originalZone 回滚——本篇是其消费端）+ polyconfig ①（配置事件）"
---

# 05-原理四：多活选库与容错

## §0 它干了什么（例子先行）

三地各有库的订单服务，**区域故障时怎么把流量挪走**：

```
配置（README 例子）：ha-datasource = {defaultZone:[us 库], cn-hangzhou:[cn 库], eu-west-1:[eu 库]}

正常态：ZoneContext.getZone()=cn-hangzhou → 查表命中 cn 库（:251）
故障态：运维在配置中心把 polyzone.zone 改成 eu-west-1
  → polyzone 切换链（02 篇已讲）发 ZoneContextChangedEvent
  → 本篇入口 PropagatingDynamicJdbcConfigChangedEventListener:66【双事件支持】
     isZoneChanged:107 确认是 "zone" 变了 → 遍历配置：只有 ha-datasource 的才处理:96
     → publish DynamicJdbcConfigChangedEvent
  → DynamicDataSource 重建（04 篇四步）：
     重建时 step5 synthesizer:71 调 getDataSourcePropertiesList:246
       → ZoneContext 已是 eu-west-1 → 查表取 eu 库 → 新池上线
回滚态：polyzone.zone=originalZone → 同一条链再走一遍 → 回 cn 库
底层兜底：某 zone 查不到 → 回退 defaultZone:253-257（不空手）
```

- **它干了什么**：**区域状态→选库→热切换**的接线——polydata 不发明区域逻辑，**只消费 polyzone 的状态与事件**。
- **本篇回答**：选库发生在哪个时点？双分支各处理什么？"故障恢复"到底是谁在恢复？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **ha-datasource** | 配置模型的"多活槽"：`{zone1:[...], zone2:[...]}` ——与静态 `datasource` 二选一（hasHighAvailabilityDataSource 判定）；校验器会遍历**所有 zone**，每 zone 默认恰好一个源（分片模块存在时允许多个），defaultZone 不能为空 |
| **选库时点** | **重建时**才查表——getDataSourcePropertiesList:246 被管道 step5 的 Synthesizer:71 调用（02 篇第 5 步的实战用途）；**不是每次 getConnection 查**（池建好就定，运行零开销） |
| **双分支监听** | 分支 A 配置变（ConfigPropertiesChangedEvent）：**按属性名精确匹配** `polydata.jdbc.configs.*`:79-87；分支 B 区域变（ZoneContextChangedEvent）：**判 "zone" 键**:107 + **只处理带 ha 的配置**:96 |
| **回退 defaultZone** | zone 查不到 → defaultZone:253-257——**换区失败也有库可连**（与 03 篇"偏好不致死"同哲学） |

## §2 关键推导（两问）

**① 为什么选库放"重建时"而不是"取连接时"？**
取连接时查=每次 getConnection 都读 ZoneContext+查表——**热路径加逻辑**（orm-pool 02 系列同款原则：热路径零开销）。放重建时=**区域状态的变化通过事件显式触发换池**，运行期只有 volatile 读（04 篇）。**代价**：区域变了但事件没到（如订阅失效）→ 池不切——所以监听器双分支都指向**唯一入口 DynamicJdbcConfigChangedEvent**，事件可靠性=切换可靠性。

**② "故障恢复"是谁在恢复？**
polydata 没有自动故障检测（README Roadmap:170"基于健康检查的自动故障转移**未实现**"）。恢复链=**人改配置**：polyzone.zone=originalZone（polyzone 04 的回滚语义）→ 事件 → 重建回原池。**所以准确说法是"人驱动的故障转移"**——面试点别说过头（这题说"自动容错"就翻车）。

**③ 与 02-04 篇的咬合**：
事件（本篇）→ 四步重建（04）→ 查表选库发生在重建的 step5（02）→ 新池从子上下文升迁（03）——**五篇是一条流水线拆开讲的**。

## §3 掌握卡（2 条）

1. **一条链**：双事件（配置变/区域变）→ DynamicJdbcConfigChangedEvent 单入口 → 四步重建 → step5 查 ha 表按 zone 出池——**选库时点=重建时**。
2. **两条诚实边界**：zone 缺省回退 defaultZone（不空手）；**故障转移是人驱动不是自动**（Roadmap 未做）——口径别说满。

## §4 边界（一句话翻篇）

- polyzone 的切换/回滚机制 → 02/04 篇已封卷，回链即可。
- ZoneResolver 怎么解析实例 zone（LB 侧）→ polyzone 03 已讲。
- 健康检查自动转移 → Roadmap，未实现不讲。

## §5 Self-Test（3 题）

- Q1：选库发生在什么时点？被管道哪一步调用？
- Q2：双分支各判什么？为什么都汇到一个事件？
- Q3：故障恢复是自动的吗？完整链路是什么？

## §6 衔接

- 多活收口；下篇 06 ORM 并存与分片集成：banned-modules 互斥、子上下文隔离并存、ShardingSphere YAML 工厂建池。
