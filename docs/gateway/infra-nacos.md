# 基础设施笔记：本地 Nacos（**Docker 版**）

> 用途：为 **L16（自定义负载均衡：灰度/Zone）** 与 **L44（Gateway × Nacos）** 提供注册中心。
> 口径（2026-09-27 用户指定）：**后续所有中间件实验一律走 docker**（不装宿主机）。
> 状态：✅ 已用 **docker 容器** 运行；**宿主机 0 残留**

---

## 一、当前部署

| 项 | 值 |
|---|---|
| 容器名 | **`nacos`** |
| 镜像 | `nacos/nacos-server:v2.2.3`（814MB） |
| 端口 | **8848**（HTTP/控制台）· **9848/9849**（2.x gRPC） |
| 模式 | `MODE=standalone`，`JVM_XMS/XMX=512m`，`NACOS_AUTH_ENABLE=false` |
| 重启策略 | `--restart unless-stopped`（Docker 重启后自动拉起） |
| 控制台 | http://localhost:8848/nacos/ （nacos/nacos） |
| 数据 | 容器内 Derby（``docker rm`` 即清空） |

**启动命令（复现用）**：

```bash
docker run -d --name nacos \
  -p 8848:8848 -p 9848:9848 -p 9849:9849 \
  -e MODE=standalone -e JVM_XMS=512m -e JVM_XMX=512m -e NACOS_AUTH_ENABLE=false \
  --restart unless-stopped \
  nacos/nacos-server:v2.2.3
```

**⚠️ 2026-09-30 踩坑：docker 端口转发失效 → 用 `--network host` 绕过**

- 症状：Nacos 容器重启后，**宿主**（IPv4/IPv6）连 8848/9848 一律卡死（curl 000）；`docker exec` 进容器 curl 却 200；
  同期 **docker redis(16379) 也不通** → 是**宿主↔docker 网桥转发**层面坏了（不是 Nacos 本身）。重建 bridge 容器也没修好。
- 绕过（host 网络，Nacos 直接绑宿主端口，不走 NAT）：

```bash
docker rm -f nacos
docker run -d --name nacos --network host \
  -e MODE=standalone -e JVM_XMS=512m -e JVM_XMX=512m -e NACOS_AUTH_ENABLE=false \
  --restart unless-stopped \
  nacos/nacos-server:v2.2.3
```

- 验证：`curl http://127.0.0.1:8848/nacos/v1/console/health/readiness` → 200
- 注意：host 模式下**不需要 `-p` 参数**；docker 里其他走 bridge 的容器（redis-lab-7/8）仍然不通 —— 需要时同样用 `--network host` 或修 docker 网络（需 root）。

**日常操作**：

```bash
docker logs -f nacos                 # 看日志
docker stop nacos / docker start nacos
docker rm -f nacos                   # 删除（数据一并清空）
curl -s --noproxy '*' http://localhost:8848/nacos/v1/console/health/readiness   # → OK
```

---

## 二、★ 关键经验：拉镜像的"配方"（后续 MySQL/Redis/Sentinel 都会用）

### 现象

`docker pull nacos/nacos-server:v2.2.3` **反复失败**（各层 `Waiting` / `Retrying`），
但**本机历史上拉成功过** `nginx:latest`(192MB) 和 `mysql:8.0`(**799MB**) → 说明通道本身没问题。

### 诊断（按顺序排除）

| 检查 | 结果 | 结论 |
|---|---|---|
| `~/.docker/config.json` 的 `proxies` | 只影响**容器运行时**，不影响拉镜像 | 不是它 |
| shell 的 `http_proxy` | dockerd 是系统服务，**不读**环境变量 | 不是它 |
| 给 dockerd 配代理（`daemon.json` 的 `proxies`） | 仍失败：`Download failed, retrying: unexpected EOF` | 代理对**并发分层下载**不稳（而 curl 单流 142MB 能成功） |
| `curl --noproxy '*' https://<mirror>/v2/` | 3 个镜像源**直连都 401（可达）** | 网络通，问题在**镜像源对该镜像的回源** |
| **换镜像源**：`swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/...` | ✅ **8 层全部拉完** | ★ **破局点** |

### 结论（可复用配方）

> **daemon.json 里配的那 3 个镜像源，对"没缓存过的镜像"会回源失败。**
> **换用华为 SWR 的 Docker Hub 完整镜像路径，显式前缀拉取即可**：

```bash
# 通用配方：把 <image> 换成任意 Docker Hub 镜像
docker pull swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/<image>:<tag>

# 然后打回标准名（否则命令里得一直写长前缀）
docker tag swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/<image>:<tag> <image>:<tag>

# 例：本次
docker pull swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/nacos/nacos-server:v2.2.3
docker tag  swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/nacos/nacos-server:v2.2.3 nacos/nacos-server:v2.2.3
```

**排查顺序（下次拉不动时照做）**：
1. 先试默认（走 daemon.json 的镜像源）
2. 不行 → **换 swr 前缀显式拉**（本次验证有效）
3. 还不行 → 用 `curl --noproxy '*' https://<mirror>/v2/` 测各源直连可达性，再挑

---

## 三、daemon.json 现状（改过的配置 + 为什么）

```json
{
    "registry-mirrors": [
        "https://docker.1ms.run",
        "https://docker.xuanyuan.me",
        "https://docker.m.daocloud.io"
    ],
    "max-concurrent-downloads": 1,
    "max-download-attempts": 10
}
```

| 配置 | 为什么 | 备注 |
|---|---|---|
| `registry-mirrors`（原有 3 个） | 本机原有配置 | 对 nacos 这类未缓存镜像会回源失败 |
| **`max-concurrent-downloads: 1`** | 并发分层下载在不稳网络下容易互相掐死 → 降到单路 | 新增（**网络好时可改回 3**） |
| **`max-download-attempts: 10`** | 增加单层重试次数 | 新增 |
| ~~`proxies`~~ | 试过给 dockerd 配代理 → `unexpected EOF`，**已移除** | 不要给 dockerd 配这个代理 |

> **改回默认**：删掉后两项 → `sudo systemctl restart docker`

---

## 四、与 xhs-project 的差异（面试口径）

| | 本项目学习环境 | xhs-project |
|---|---|---|
| 部署 | **docker 容器** `nacos/nacos-server:v2.2.3`（standalone） | 独立 Nacos 集群 |
| 地址 | `localhost:8848` | `192.168.0.142:18848`（当前不在线） |
| 命名空间 | `public` | `my-xhs` |
| 存储 | 容器内 Derby | 生产级（MySQL 持久化） |
| 鉴权 | 关闭 | 有（P-D1 安全待办） |

> ⚠️ **版本口径**：学习环境用 **2.2.3**；xhs 客户端是 SCA 2023.0.1.2（nacos-client 2.3.x）。
> 客户端行为（`NacosServiceInstance` / metadata）一致，**回答"我们用的版本"要分开说**。

---

## 五、本课（L16）已跑通的部分

| 步骤 | 状态 |
|---|---|
| Nacos（docker）运行中 | ✅ |
| `demo-provider` 注册（metadata `zone=shanghai, version=v1`） | ✅ |
| `demo-gateway` + `spring-cloud-starter-loadbalancer` + 路由 `lb://demo-provider` | ✅ `curl /lb/hello` → 200 |
| `/globalfilters` 出现 `ReactiveLoadBalancerClientFilter`(10150) + `LoadBalancerServiceInstanceCookieFilter`(10151) | ✅ |

**待做**：第二实例（`zone=beijing, version=v2`）+ 自定义 `ServiceInstanceListSupplier`（灰度 / 同 Zone 优先）。
