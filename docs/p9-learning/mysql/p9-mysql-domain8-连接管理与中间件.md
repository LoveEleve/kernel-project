# p9-mysql-domain8 · 连接管理与中间件（P9 深度实验）

> MySQL 轨道域 8/9。对照 vol-mysql 16 章。
> 覆盖：连接池/ProxySQL/连接数优化

## 实验环境

```
Docker: mysql-p9
```

---

## 实验 1：连接数参数（实测）

```
max_connections = 151           ← 最大连接数
thread_cache_size = 9          ← 线程缓存
wait_timeout = 28800           ← 空闲连接超时（8 小时）
Threads_connected = 1          ← 当前连接数
```

### 连接数优化

```
连接数计算：
  max_connections = 应用实例数 × 连接池大小 + 监控/运维连接
  → 过大：内存/线程开销（每连接 ~256KB 栈+线程结构）
  → 过小：连接拒绝（Too many connections）

连接使用率监控：
  Threads_connected / max_connections
  → >80%：告警（考虑扩容或连接池调优）
```

### P9 追问链

- **Q：max_connections 怎么设？** → 应用实例×连接池大小+运维余量（不要盲目调大）
- **Q：连接数过多有什么问题？** → 内存/线程开销（每连接 ~256KB）；上下文切换
- **Q：wait_timeout？** → 空闲连接超时（默认 8 小时；生产可调小如 300 秒）

---

## 实验 2：连接状态（实测）

```
current_conn = 1 / max_conn = 151（连接使用率 0.7%）
```

### 连接监控

```sql
-- 连接数
SHOW STATUS LIKE 'Threads_connected';
-- 连接列表
SELECT * FROM information_schema.processlist;
-- P_S 线程
SELECT * FROM performance_schema.threads WHERE TYPE='FOREGROUND';
```

---

## 补充：连接池原理（P9 高频）

### 应用端连接池（HikariCP/Druid）

```
连接池核心参数（HikariCP）：
  maximumPoolSize：最大连接数（=max_connections/实例数）
  minimumIdle：最小空闲连接
  connectionTimeout：获取连接超时（如 30 秒）
  idleTimeout：空闲连接回收
  maxLifetime：连接最大存活（< MySQL wait_timeout）

工作流程：
  ① 获取连接：池有空闲→直接返回；无空闲且<max→新建；无空闲且=max→等待
  ② 归还连接：不关闭（归还池）
  ③ 心跳检测：保活（避免 wait_timeout 断开）
```

### P9 追问链

- **Q：连接池解决什么？** → 连接复用（避免每次建连开销）；控制并发数
- **Q：HikariCP 关键参数？** → maximumPoolSize/connectionTimeout/idleTimeout/maxLifetime
- **Q：连接池 max 和 MySQL max_connections 关系？** → Σ(池 max) < MySQL max_connections（留余量）

---

## 补充：ProxySQL（P9 中间件）

### ProxySQL 核心功能

```
① 连接代理：应用连 ProxySQL → ProxySQL 转发到 MySQL
  → 连接复用（前端长连接，后端短连接/池）
  → 应用无感知

② 读写分离：
  写：路由到主库
  读：路由到从库（按权重/延迟）

③ 查询重写/过滤：
  SQL 改写（如强制索引）
  黑名单（拦截危险 SQL）

④ 故障切换：
  监控主库健康 → 自动切换到从库
  → 应用不改连接配置

⑤ 查询缓存：
  SELECT 结果缓存（可选）
```

### ProxySQL vs 应用端连接池

| | 应用端连接池 | ProxySQL |
|---|---|---|
| 位置 | 应用进程内 | 独立代理层 |
| 读写分离 | 应用代码实现 | ProxySQL 配置（应用无感知） |
| 故障切换 | 应用重连逻辑 | ProxySQL 自动切换 |
| 性能 | 无网络跳转 | 多一跳（本机代理开销小） |

### P9 追问链

- **Q：ProxySQL 干什么？** → 连接代理/读写分离/查询重写/故障切换（应用无感知）
- **Q：ProxySQL vs 应用端池？** → ProxySQL=集中管理（读写分离/切换）；应用池=无代理开销
- **Q：读写分离怎么做？** → ProxySQL 按 SQL 类型路由（SELECT→从库，其他→主库）

---

## 补充：连接数优化实践（P9 生产）

```
问题：Too many connections（1040）

原因：
  ① 连接池 max 设置过大（应用数×池大小 > max_connections）
  ② 连接泄漏（应用没归还连接）
  ③ wait_timeout 太长（空闲连接堆积）

优化：
  ① 连接池 max = max_connections × 0.8 / 应用实例数
  ② 监控连接泄漏（Threads_connected 趋势）
  ③ 调小 wait_timeout（如 300 秒）
  ④ max_connections 适度调大（注意内存开销）
  ⑤ ProxySQL 连接复用（前端长连接后端池）
```

### P9 追问链

- **Q：Too many connections 怎么办？** → 检查连接池 max/泄漏/调 max_connections
- **Q：怎么发现连接泄漏？** → Threads_connected 持续增长不回落
- **Q：生产连接池怎么配？** → max = max_connections×0.8/实例数；监控使用率

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **连接数参数** | max_connections=151/thread_cache=9/wait_timeout=28800 | 实测 |
| **连接池原理** | 连接复用+控制并发；HikariCP max/connectionTimeout/maxLifetime | 补充（叙事） |
| 池 max vs MySQL max | Σ(池 max) < MySQL max_connections（留余量） | 补充 |
| **ProxySQL** | 连接代理/读写分离/查询重写/故障切换（应用无感知） | 补充（叙事） |
| 连接数优化 | 池 max=max_conn×0.8/实例数；监控泄漏；调 wait_timeout | 补充 |
| 连接监控 | Threads_connected/processlist/使用率 | 实测（1/151） |

## 相关文件

- Docker: mysql-p9
- 实测: max_connections=151/Threads_connected=1/wait_timeout=28800

## 参考来源（权威核对）

```
官方文档：
  → 最大连接数（max_connections）
  → 线程缓存（thread_cache_size）
  → 连接超时（wait_timeout）

经典书籍：
  《高性能 MySQL》第三版 → 连接池/ProxySQL
  ProxySQL 官方文档 → proxysql.com

权威来源核对：
  max_connections 默认 151（官方）
  wait_timeout 默认 28800（官方）
```
