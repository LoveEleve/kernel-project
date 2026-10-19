# Prometheus Service Discovery via Nacos

## 一、为什么用 Nacos 做 Prometheus SD

Prometheus `static_configs` 中的 target 列表写死。微服务扩缩容、Pod 漂移后需手动更新。Nacos 作为注册中心天然知道当前有哪些运行中的实例--让 Prometheus 直接从 Nacos 拉取目标列表,避免手动维护。

## 二、完整配置

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'sca-lab-services'
    scrape_interval: 30s
    scrape_timeout: 10s

    nacos_sd_configs:
      - server: '{NACOS_ADDR}'           # Nacos Server 地址(含端口)
        namespace_id: 'sca-lab-dev'        # 只采集这个 namespace
        group_name: 'DEFAULT_GROUP'        # 只采集这个 group(可选)

    # relabel_configs: 标签重写和过滤
    relabel_configs:
      # 1. 服务名 -> application 标签
      - source_labels: [__meta_nacos_service]
        target_label: application

      # 2. 只采集标注了 prometheus.scrape=true 的实例
      - source_labels: [__meta_nacos_metadata_prometheus_scrape]
        regex: 'true'
        action: keep

      # 3. 自定义 metrics path(如管理端口)
      - source_labels: [__meta_nacos_metadata_prometheus_path]
        regex: '(.+)'
        target_label: __metrics_path__
        replacement: '${1}'

      # 4. 使用 metadata 中的自定义端口(分离管理端口和业务端口)
      - source_labels: [__address__, __meta_nacos_metadata_prometheus_port]
        regex: '([^:]+):\d+;(.+)'
        target_label: __address__
        replacement: '${1}:${2}'

      # 5. 注入环境标签
      - target_label: env
        replacement: 'dev'

      # 6. 注入 namespace 标签
      - source_labels: [__meta_nacos_namespace]
        target_label: namespace
```

## 三、Nacos metadata 到 Prometheus 标签的映射

配置好 Nacos SD 后,每个 Nacos 实例的元数据自动转为 Prometheus 标签:

| Nacos 数据 | Prometheus 标签 |
|---|---|
| IP | `__meta_nacos_ip` |
| 端口 | `__meta_nacos_port` |
| 服务名 | `__meta_nacos_service` |
| group | `__meta_nacos_group` |
| namespace | `__meta_nacos_namespace` |
| metadata 中任意 key | `__meta_nacos_metadata_{key}` |

### 实例注册时注入 Prometheus metadata

```java
// 注册时注入 Prometheus 需要的 metadata
instance.getMetadata().put("prometheus_scrape", "true");          // 控制是否被抓取
instance.getMetadata().put("prometheus_path", "/actuator/prometheus"); // 自定义路径
instance.getMetadata().put("prometheus_port", "18081");           // 管理端口(可选)
```

### 为什么需要 `prometheus_port`

生产环境通常会分离管理端口(`management.server.port=18081`)和业务端口(`server.port=28001`)。Nacos 注册的是业务端口,但 metrics 端点在管理端口。`prometheus_port` metadata 让 Prometheus 知道去哪抓 metrics。

## 四、relabel_configs 常见场景

| 场景 | relabel 规则 |
|------|------------|
| 只采集特定服务 | `source_labels:[__meta_nacos_service], regex:'nacos-lab' action:keep` |
| 过滤掉 infrastructure | `source_labels:[__meta_nacos_service], regex:'nacos|gateway' action:drop` |
| 注入 Prometheus 的 env 标签 | `target_label:env replacement:'sca-lab-dev'` |
| 按权重调节 scrape 频率 | `source_labels:[__meta_nacos_metadata_weight], target_label:__scrape_interval__`(Prometheus 2.37+) |

## 五、Scrape 配置与 Micrometer 对齐

```yaml
# Prometheus 侧
scrape_interval: 30s   # 和 micrometer step 对齐

# Spring Boot 侧（Boot 3 前缀: management.prometheus.metrics.export）
management:
  prometheus:
    metrics:
      export:
        step: 30s       # 本地聚合窗口,和 scrape_interval 一致
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  server:
    port: 18081         # 管理端口分离(安全)
```

`scrape_interval` 和 `step` 必须对齐。不对齐会导致:
- `step > scrape_interval`: 每次 scrape 看到相同值 -> 数据空洞
- `step < scrape_interval`: 本地聚合不充分 -> 丢失精度

## 六、Pushgateway(短生命周期实例)

Prometheus 默认 Pull 模式不适合短生命周期(Cron Job/K8s Job):

```yaml
# Spring Boot 配置 Push 模式
management:
  prometheus:
    metrics:
      export:
        pushgateway:
          enabled: true
          base-url: http://pushgateway:9091
          job: nacos-lab-metrics
          push-rate: 10s            # 推送频率
          grouping-key:
            instance: ${HOSTNAME}    # 区分不同实例
```

**Pushgateway 注意**:
- Pushgateway 无水平扩展(单实例内存存储)
- 重启丢失所有数据
- 适合短任务,不适合常驻服务(常驻用 Pull)

## 七、验证

```
# 1. 确认 Nacos 实例的 metadata 正确
curl "http://{NACOS_ADDR}/nacos/v1/ns/instance/list?serviceName=nacos-lab&namespaceId=sca-lab-dev"

# 2. 确认 Prometheus 发现了实例
curl http://localhost:9090/api/v1/targets | python3 -m json.tool | grep nacos-lab

# 3. 确认指标可查询
# Prometheus UI -> Graph -> application="nacos-lab" -> 选择任意指标
```

## 八、生产注意

- Prometheus 每 30s 轮询 Nacos SD--**不是实时**
- Nacos 挂了 -> Prometheus 可能拿不到新目标,但**已有 target 的 scrape 不受影响**
- metadata 中**不要放密码/密钥**--所有 `__meta_nacos_*` 都会被 Prometheus 存储并暴露
- **管理端口分离**: 生产用 `management.server.port` 隔离 metrics 和业务端口
- **标签命名**: Prometheus label 用小写+下划线,不要用大写或中划线
- **高可用**: Prometheus 本身单点,生产需配合 Thanos/Mimir/VictoriaMetrics 做长期存储
