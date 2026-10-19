# es-lab 交接文档（2026-10-05）

> 位置：`/data/workspace/source-code/xhs-demo/es-lab`
> 基线：**Elasticsearch 8.12.2**（对齐 xhs-project）+ xhs 索引同步模式（ExternalGte）
> 用途：ES 学习模块（倒排/分词/Mapping/DSL/分片/近实时/索引同步）——生产使用 + 核心原理 + 面试题
> 评审与验证记录：`docs/REVIEW-2026-10-05.md`

---

## 一、建设内容（新建 lab）

- **ES 运行环境**：`/data/workspace/tools/elasticsearch-8.12.2`（512m 堆、单节点、关安全、端口 **19200** 对齐 xhs 口径）
- **实验端点 A01-A05**：索引分词 / CRUD+Bulk / 查询 DSL / ExternalGte 版本控制 / 索引同步模式
- **文档**：D1-D5（例子先行）+ Z（面试收束）

## 二、验证状态（全部实测）

- A01-A05 端到端全部通过（含"近实时 count=0"与"乱序 409 拒绝"实证）——详见 REVIEW
- 端口：lab **28060**；ES **19200**；停止命令见 REVIEW §四

## 三、启动与停止（⚠️ 实验后务必停 ES）

```bash
# 启动 ES
cd /data/workspace/tools/elasticsearch-8.12.2
setsid nohup ./bin/elasticsearch > /tmp/es.log 2>&1 < /dev/null &

# 启动 lab
cd /data/workspace/source-code/xhs-demo
export JAVA_HOME=/env/javaEnv/jdk17; export MAVEN_OPTS="-Xmx512m -XX:MaxMetaspaceSize:256m"
setsid nohup mvn -pl es-lab spring-boot:run -q > /tmp/es-lab.log 2>&1 < /dev/null &

# 停止（实验完必做——ES 占 512m 堆）
kill $(ps -eo pid,args | grep -E "[e]s-lab|[e]lasticsearch" | grep -v grep | awk '{print $1}')
```

## 四、端点速查（实验手册）

| 实验 | 端点 | 说明 |
|---|---|---|
| A01 | `POST /a01/create` / `GET /a01/analyze?text=` / `GET /a01/mapping` | 建索引 / 分词 / mapping |
| A02 | `POST /a02/index?...` / `POST /a02/bulk?count=` / `GET /a02/count` / `GET /a02/get?id=` | 写入/批量/计数 |
| A03 | `GET /a03/match?q=` / `term?authorId=` / `bool?q=&minLikes=` / `page?from=&size=` | 查询 DSL |
| A04 | `POST /a04/index?id=&version=&title=` / `GET /a04/get?id=` | ExternalGte 版本链 |
| A05 | `POST /a05/db-update?...&updatedAt=` / `POST /a05/scripted?id=` / `GET /a05/doc?id=` | 同步/乱序实验 |

**curl 注意**：先 `unset http_proxy https_proxy`；中文参数用 `--data-urlencode`（GET 用 `-G`，POST 直接 `-X POST --data-urlencode`）。

## 五、审计语料

| 语料 | 位置 |
|---|---|
| xhs 搜索服务（模式蓝本） | `xhs-project/my-xhs-search`（4 个同步消费者 + ExternalGte） |
| ES 运行环境 | `/data/workspace/tools/elasticsearch-8.12.2`（512m 堆配置） |
| ES 官方 Java Client | m2：`co.elastic.clients:elasticsearch-java:8.12.2` |

## 六、口径约定

1. 版本基线 **ES 8.12.2**（= xhs-project）；
2. 单节点实验口径（副本 0）——分布式语义（分片/副本/故障转移）为概念级；
3. 未装 IK 插件——standard 演示分词原理，生产 IK 口径见 xhs。

## 七、已知边界

- ES 为本地单节点（非集群）；IK 插件未装；未接 Canal（下一单元：数据同步线）；
- 实验后必须停 ES（内存敏感）。
