# redis-lab：Redis 学习实验环境

> 用途：L2-2「Redis 单元」（权威域4 · 9 篇）的实验沙箱。
> 版本策略：**7 = 对齐 xhs 生产**（`deploy/docker/my-xhs-deploy-zip/docker-compose.yml` 用 `redis:7-alpine`）；**8 = 现役代对照**。
> 首次使用：`docker compose up -d`（端口：本机 16379→7、16388→8）。
> 镜像：`redis-lab:7`（7.2.11，对齐生产）/ `redis-lab:8`（8.8.0）——本地构建（registry 拉取受限时绕行：容器内经宿主代理 `apk add redis`）：
> `docker build --build-arg http_proxy=$http_proxy --build-arg https_proxy=$https_proxy -t redis-lab:7 -f docker/redis7.Dockerfile .`

## 版本对照表（为什么是 7+8）

| 版本 | 角色 | 关键差异（本 lab 会验证） |
|---|---|---|
| **7** | 主实例（对齐生产） | 多部 AOF（manifest）、Functions、listpack 默认用于 hash/set；`io-threads` 可配 |
| **8** | 对照（现役代） | 新哈希实现、更多 IO 线程默认、许可证/Valkey 分叉语境；题库"Redis 7/8"题 |

## 实验脚本

| 脚本 | 对应域4篇 | 实验内容 |
|---|---|---|
| `scripts/01-blocking-ping.sh` | 01 架构与线程模型 | 长脚本执行期间 PING 被阻塞（≈ 脚本时长）→ 证明"命令串行执行" |
| `scripts/02-bigkey-del-vs-unlink.sh` | 01 / 06 | 大 key `DEL` 阻塞 vs `UNLINK` 立即返回（后台异步释放） |
| `scripts/03-encoding.sh` | 02 核心数据结构 | `OBJECT ENCODING` 切换点（String/Hash/Set/List/ZSet）+ 内存账（String×100 vs Hash×1） |
| `scripts/04-slow-ops.sh` | 02 慢操作 | `HGETALL` vs `HSCAN`、`KEYS` vs `SCAN` 的阻塞对照（30 万级，结束自动清空） |
| `scripts/05-advanced.sh` | 04 高级结构 | Stream PEL 生命周期、Bitmap 位寻址、HLL 稀疏→稠密、Geo score=geohash |
| `scripts/06-persistence-replication.sh` | 05 持久化与复制 | BGSAVE/fork/COW 观测、AOF 多部文件（base/incr/manifest）与重写、PSYNC 主从全量+增量 |
| （后续）`06-pipeline-vs-loop.sh` | 07 | 逐条 vs Pipeline 的 RTT 放大对照 |

## 运行方式

```bash
cd redis-lab
docker compose up -d          # 起 7/8 两个实例
bash scripts/01-blocking-ping.sh       # 默认打 7（CT=redis-lab-7）
CT=redis-lab-8 bash scripts/01-blocking-ping.sh   # 换 8 对照
docker compose down           # 收工
```

> 所有 redis 命令经 `docker exec` 执行（宿主机无需装 redis-cli）。

> **实验规模纪律**：默认 ≤30 万级；脚本结束自动 `FLUSHALL`；`maxmemory 128MB` 为上限（非预占）。
