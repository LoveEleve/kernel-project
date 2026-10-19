# spring.config.import 详解

## 一、为什么需要它

Spring Cloud 2025 废弃了 `bootstrap.yml` 和 `spring.cloud.nacos.config.shared-configs`——所有外部配置声明统一走 `spring.config.import`。

如果不声明 import，启动时会报：
```
No spring.config.import property has been defined
```

## 二、基本写法

```yaml
spring:
  config:
    import:
      - optional:nacos:nacos-lab.yaml
      - optional:nacos:shared-datasource.yaml?group=DATASOURCE_GROUP
```

**语法**：`[optional:]nacos:{dataId}[?group=xxx][&refreshEnabled=xxx]`

- `optional:` — 该 source 不存在也不报错
- `?group=XXX` — 指定 Group，默认 DEFAULT_GROUP
- `?refreshEnabled=true` — 默认是 true

## 三、与旧版 shared-configs 对比

| 旧写法（废弃） | 新写法 |
|---|---|
| `spring.cloud.nacos.config.shared-configs[0].data-id=xxx` | `spring.config.import: nacos:xxx.yaml?group=G` |
| 只在 bootstrap.yml 中配置 | 在 application.yml 中配置 |
| 只支持 Nacos | 支持 Nacos / Consul / Vault / ConfigMap 等任何配置源 |

## 四、生产注意

- import 顺序影响优先级——后面声明的覆盖前面声明的同名 key
- `optional` 不要滥用——生产应刻意处理缺失配置，而不是静默忽略
- 多个 import 会增加启动时间——每个 source 都需要一次网络请求
