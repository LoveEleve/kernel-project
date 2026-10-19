# Bootstrap 废弃 — Spring Cloud 2025 的配置加载变革

## 一、bootstrap.yml 的终结

Spring Cloud 2020.0 开始弃用 `bootstrap.yml`，2025.0 正式移除 `spring-cloud-starter-bootstrap`。配置加载方式从两步变成一步：

**旧（Spring Cloud Hoxton 及之前）**：
```
bootstrap.yml（Bootstrap Context）→ 加载外部配置 → 传 PropertySource
    ↓
application.yml（Application Context）→ 覆盖外部配置 → 最终生效
```

**新（Spring Cloud 2025）**：
```
application.yml → spring.config.import → 直接声明外部配置源 → 一次加载
```

## 二、旧写法 vs 新写法

```yaml
# ❌ 旧：bootstrap.yml（废弃）
spring:
  cloud:
    nacos:
      config:
        server-addr: 21.130.247.89:18848
        namespace: sca-lab-dev
        file-extension: yaml

# ✅ 新：application.yml
spring:
  config:
    import: optional:nacos:nacos-lab.yaml
  cloud:
    nacos:
      discovery:
        server-addr: 21.130.247.89:18848
        namespace: sca-lab-dev
      config:
        server-addr: 21.130.247.89:18848
        namespace: sca-lab-dev
```

## 三、共享配置的声明

旧方法用 `shared-configs`（bootstrap.yml 专属），新方法用 `spring.config.import`：

```yaml
# ✅ 新写法
spring:
  config:
    import:
      - optional:nacos:nacos-lab.yaml
      - optional:nacos:shared-datasource.yaml?group=DATASOURCE_GROUP
      - optional:nacos:shared-redis.yaml?group=REDIS_GROUP
```

`?group=XXX` 指定 Group，`optional:` 保证配置不存在时不报错。

## 四、为什么废弃 bootstrap context

1. **复杂度**：两个 Spring Context（Bootstrap + Application）的管理开销
2. **时序问题**：Bootstrap Context 先启动，无法引用 Application Context 中的 Bean
3. **统一性**：spring.config.import 是统一的"外部配置声明"机制——Nacos、Consul、Vault、ConfigMap 都走同一个入口
