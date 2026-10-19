---
title: "07-使用：MyBatis-Plus"
series: "连接池与 ORM 系列"
previous: "06-plugin-cache.md"
next: "08-池与SQL故障排查.md（待写）"
source_refs:
  - "MP 源码已核：BaseMapper（core/mapper）/QueryWrapper+AbstractWrapper（conditions）/TableLogic（annotation）"
  - "xhs 实锤：Order.java:44-45 @TableLogic deleted；各服务 BaseMapper<Entity> 接口（30 处）"
---

# 07-使用：MyBatis-Plus

## §0 它干了什么（例子先行）

xhs 的真实写法（Order.java:44-45 + LocalMessageMapper 实锤）：

```java
// 实体：一个注解换来"逻辑删除"（物理上 UPDATE deleted=1，查询自动过滤）
public class Order { @TableLogic private Integer deleted; }

// Mapper：继承 BaseMapper 白拿一套 CRUD
public interface LocalMessageMapper extends BaseMapper<LocalMessage> { }

// 查询：wrapper 链式拼条件（不用写 XML）
List<Order> xs = orderMapper.selectList(
    new LambdaQueryWrapper<Order>()
        .eq(Order::getUserId, 10001).ge(Order::getPayAmount, 100));
```

- **它干了干什么**：**在内核之上长便捷层**——CRUD 白拿（BaseMapper）/条件链式拼（Wrapper）/逻辑删除等横切（注解）——**SQL 仍在内核执行（05/06 的机制全照旧）**。
- **本篇回答**：MP 加了三层什么糖？逻辑删除怎么实现的？什么时候还得写原生 SQL？

## §1 概念铺垫（What：MP 的三层糖）

| 层 | 能力 | 例 |
|---|---|---|
| **CRUD 层** | `BaseMapper<T>`：selectById/selectList/insert/updateById/delete… | 单表操作零 SQL |
| **条件层** | `Wrapper`（QueryWrapper/LambdaQueryWrapper）——链式拼 WHERE | `eq/ge/like/orderBy` |
| **横切层** | `@TableLogic`（逻辑删除）/`@TableField(fill=…)`（自动填充 create_time/update_time）/分页插件 | 注解级横切 |

**与内核的关系**（05/06 回收）：BaseMapper 的方法最终走 `SqlSession → Executor`；Wrapper 被翻译成 SqlSource；**分页插件=StatementHandler 派插件**——**MP 没有绕开内核，只是把常用路径做成了模板**。

## §2 逻辑删除的机制（工程最常用）

```
@TableLogic deleted 字段：
  deleteById(id) → 实际执行 UPDATE t SET deleted=1 WHERE id=?（不是 DELETE！）
  selectXxx     → 自动追加 WHERE deleted=0
  ⚠️ 代价：① 唯一索引要把 deleted 纳入（否则删后重插撞唯一键）
          ② 表会膨胀——定期归档
          ③ 手写 SQL/其他工具直连不认这个约定——绕过即失效
```

**与 Sharding 系列的咬合**：逻辑删除的 UPDATE 在分片场景同样走路由（Sharding 04 的表改写照常）——**两套机制各管各层，别互相假设**。

## §3 工程使用（xhs 30 处的模式总结）

| 用法 | 说明 |
|---|---|
| `BaseMapper<Entity>` | 每表一个接口——单表 CRUD 全靠它（xhs 主流） |
| LambdaQueryWrapper | 类型安全（方法引用——重命名字段编译期报错） |
| 复杂查询 | 还是 XML/注解 SQL——**MP 管便捷不管复杂**（多表 JOIN/子查询别硬拼 wrapper） |
| 自动填充 | MetaObjectHandler 统一填审计字段（create_time/update_time）——插入/更新时框架回调，与业务代码解耦 |

## §4 掌握卡（2 条）

1. **MP=内核的模板层**：CRUD/Wrapper/横切三糖——**执行链、插件链、缓存全走 MyBatis 原生**（06 的四大目标它也不绕）。
2. **逻辑删除三代价**：唯一索引要带 deleted/表膨胀/绕过约定即失效——**用了就要全链路认这个约定**。

## §5 边界（一句话翻篇）

- MP 代码生成器/多租户插件 → 用时查官方。
- 乐观锁插件（@Version）→ 标准插件，思路自推（StatementHandler 派）。
- MP 与 JPA 对比 → 不做（xhs 栈无 JPA）。

## §6 Self-Test（3 题）

- Q1：MP 的三层糖各是什么？它绕开 MyBatis 内核了吗？
- Q2：逻辑删除的 deleteById 实际执行什么 SQL？唯一索引为什么要改？
- Q3：什么查询不该用 Wrapper 硬拼？（多表 JOIN/子查询——写原生 SQL）

## §7 衔接

- ORM 线收口（05/06/07）；下篇 08 深水：池与 SQL 故障排查——泄漏/耗尽/慢 SQL 的定位路径。
