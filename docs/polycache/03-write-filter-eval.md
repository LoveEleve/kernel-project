---
title: "03-写过滤与EVAL学习：哪些命令该复制"
series: "PolyCache 多机房复制系列"
previous: "02-nonintrusive-capture.md"
next: "04-Kafka传输.md"
source_refs:
  - "polycache-core .../command/RedisCommandRegistry.java（130 行，全文已读）"
  - "polycache-redisson .../capture/RedisScripts.java（131 行，全文已读）"
  - "polycache-redisson .../capture/RedisCommandCapture.java:154-185,247-253,278,295"
  - "polycache-core META-INF/redis-write-commands（182 行：8.4.0 ACL CAT write 生成 + 手工追加 EVAL/EVALSHA）"
---

# 03-写过滤与EVAL学习：哪些命令该复制

## §0 它干了什么（例子先行）

```
SET key v        → 写命令 → 复制到对端
GET key          → 读命令 → 不复制（对端副本有自己的读）
RMap.put(...)    → Redisson 内部走 EVAL → 在写目录里 → 复制
EVALSHA <sha>    → 脚本还没登记？原样复制；登记过？翻译成带脚本体的 EVAL 再复制
SCRIPT LOAD "..." → 不复制本身——只是把脚本体登记进本地脚本表
```

- **它干了什么**：给 02 看见的命令做裁决——写才复制；脚本类命令特殊处理（登记/翻译）。
- **本篇回答**：写命令表哪来的？为什么 EVAL 天然被覆盖？EVALSHA 怎么做到"对端不依赖脚本缓存"？

## §1 掌握卡（3 条）

1. **默认过滤器一行**：`commandFilter = RedisCommandRegistry::isWriteCommand`——判定基于**类路径资源** `META-INF/redis-write-commands`（Redis 8.4.0 官方 `ACL`（Redis 的权限/分类系统）`CAT "write"` 生成，182 行；文件末尾手工追加 `EVAL`/`EVALSHA`）。
2. **多文件合并可扩展**：Registry 用 `getResources` 枚举**所有同名资源**合并——业务方 jar 里放自己的追加表就能扩（`#` 注释、自动大写）。
3. **脚本两处理**：`SCRIPT LOAD` 不复制只登记（SHA1 按 Redis 同款算法算，LRU=最久未用先淘汰，上限 1024）；`EVALSHA` 登记过就**翻译成带脚本体的 EVAL**——重放不依赖对端 Redis 的脚本缓存。

## §2 为什么"只复制写"（推导）

**朴素答案**：看见的命令全复制？——读命令复制过去：浪费跨机房带宽（读流量远大于写）；对端被读命令刷屏毫无价值；还会干扰对端自己的读缓存。

**But**：裁决表谁来维护？Redis 命令几百个、还有模块命令（`BF.ADD`/`JSON.SET`…），手写白名单必漏。

**Therefore**：借 Redis 官方的分类——**ACL 的 write 类**是基础写命令集；但 Redis 把 `EVAL`/`EVALSHA` 归为 scripting，不在 ACL write 里，所以资源文件在 ACL 快照末尾**手工追加**了这两条（README 特性表原话"另外把 EVAL/EVALSHA 视为写命令"）。RMap.put 走的 EVAL 因此被覆盖。生成物放类路径，加载时多资源合并（`:102-117`），大写归一、`#` 注释。

**Cost**：目录基于 8.4.0 快照——Redis 新增命令要重新生成追加表；模块命令若不在 ACL write 类（个别模块自定义分类）要手工补。

## §3 脚本的特殊旅程（EVAL 系的三连）

Redisson 的很多数据结构（RMap/RAtomicLong 等）内部就是 Lua 脚本——不处理 EVAL 系，复制就漏半壁江山。

```
① SCRIPT LOAD "脚本体"
     → 不复制（对端自己会 load）
     → RedisScripts.register：SHA1（与 Redis 同算法）→ 存脚本体 → LRU 1024
② EVALSHA <sha> numkeys ...   （Redisson 日常发的是这个——省流量）
     → buildMessage(:247-253)：find(sha) 命中？
        命中 → 重组成 EVAL [脚本体, 其余参数]（首参替换，数组拷贝）
        未命中 → 原样复制 EVALSHA
③ 对端重放收到的是 EVAL（带完整脚本）→ 目标 Redis 无需有此脚本，直接执行
```

**为什么学 Redis 的 SHA1 算法**：登记的 key 必须和 Redisson 发来的 `EVALSHA` 参数同一口径，否则查不到——"同算法"不是洁癖是正确性。

## §4 负面空间（本篇两笔）

1. **EVALSHA 未命中就原样复制**：LRU 淘汰（>1024 脚本）或拦截晚于首次 EVALSHA——对端重放时若无该脚本会 `NOSCRIPT`（脚本不存在的标准错误）失败 → 重试 3 次仍败 → 告警跳过（05 的失败路径）。生产对策：脚本数控制在 1024 内 / 热脚本常驻。
2. **write 目录是快照**：8.4.0 生成——新命令/冷门模块命令要追加表补（Registry 的多文件合并就是给这个留的门）。

## §5 边界（一句话翻篇）

- `RedisCommandMessage` 消息模型字段 → 用时看 core（04 传输时用到）。
- 被追加的命令表怎么写格式 → Registry :119-128（一行一命令，`#` 注释）。
- 关闭开关 `enabled=false` → 07 配置项。

## §6 Self-Test（4 题）

- Q1：读命令为什么不能复制？写表为什么借 ACL 而不手写（为什么还要补 EVAL/EVALSHA）？
- Q2：RMap.put 走 EVAL 为什么不用特殊处理？（EVAL/EVALSHA 是手工追加的写命令）
- Q3：SCRIPT LOAD 和 EVALSHA 各自怎么处理？翻译后的重放为什么更稳？
- Q4：EVALSHA 缓存未命中时复制什么？对端会遇到什么错？

## §7 衔接

- 本篇回答"哪些复制"；下篇 04 回答"复制怎么走 Kafka"（按 key 分区保序、单线程队列背压、失败不复制、防回环标记）。
