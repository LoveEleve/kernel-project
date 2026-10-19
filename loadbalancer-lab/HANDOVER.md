# loadbalancer-lab 交接文档（2026-10-05 重写）

> 位置：`/data/workspace/source-code/xhs-demo/loadbalancer-lab`
> 版本基线：**SC 2023.0.3 / spring-cloud-loadbalancer 4.1.4**（= xhs-project）
> 用途：Spring Cloud LoadBalancer 学习模块（接口体系/装饰器链/自动装配/阻塞客户端/生命周期/拦截器）——生产使用 + 核心原理 + 面试题
> 评审记录：`docs/REVIEW-2026-10-05.md`

---

## 一、迁移与范围调整（2026-10-05）

- 自 `source-code/sca-lab/loadbalancer-lab` **迁入** xhs-demo；pom 改造：继承 xhs-demo 父 POM、版本由 SC BOM 管理、注册为模块。
- 用户决定：**砍掉 C 类手写**——C-01 动态响应时间 WeightFunction / C-02 NacosMetadata 权重 / C-03 一致性哈希 LB（3 文档 + 5 代码 + 6 测试 + 2 装配 config）。
- 恢复途径：sca-lab git 历史。
- **版本重定基**：sca-lab 基线 SC 2025.0.2/commons 4.3.2 → **SC 2023.0.3/4.1.4**；文档全量重定基（行数/版本注记/ApiVersion 落差段）。

## 二、已完成（A 文档 + D1-D7 + 保留代码）

| 内容 | 状态 |
|---|---|
| A-类官方功能（A-01~A-18 清单 + 版本落差） | ✅ 审校（C 引用清除/ApiVersion 段重写） |
| D1 接口体系（Reactive/Reactor/RoundRobin/Random/Response） | ✅ 审校 |
| D2 Supplier 装饰器链（14 个/Builder/HealthCheck） | ✅ 审校（withXxx 20/15 修正） |
| D3 自动装配（Factory/Registrar/8 Condition） | ✅ 审校 |
| D4 Weighted 权重展开 | ✅ 审校 |
| D5 BlockingLoadBalancerClient（execute/URI 重构） | ✅ 审校 |
| D6 LoadBalancerLifecycle（MicrometerStats/Tags） | ✅ 审校 |
| D7 Interceptor 家族（RestTemplate/Retry/RestClient） | ✅ 审校 |
| config/ 4 个（Random/Hint/HealthCheck/BuilderDemo）+ controller（8 端点）+ support | ✅ 保留 |
| tests 3 个（A01RealNacosIntegration/A01RoundRobin/HealthCheckSupplier） | ✅ 保留（real 组默认排除） |

## 三、验证状态

- 编译 ✓；文档评审完成（详见 REVIEW）
- **依赖 nacos-lab 提供实例**（`@LoadBalancerClient(name = "nacos-lab")`）

## 四、构建与运行

```bash
cd /data/workspace/source-code/xhs-demo
export JAVA_HOME=/env/javaEnv/jdk17
export MAVEN_OPTS="-Xmx512m -XX:MaxMetaspaceSize=256m"
mvn -pl loadbalancer-lab compile

# 启动（脚本已适配 xhs-demo）
./loadbalancer-lab/start-lb-lab.sh                     # 默认 round-robin
./loadbalancer-lab/start-lb-lab.sh -Dscalab.lb.strategy=random
# 或 mvn -pl loadbalancer-lab spring-boot:run（端口 28010）

# 验证（需先起 nacos-lab 双实例）
curl http://127.0.0.1:28010/lb/distribution?calls=10   # 分布
curl http://127.0.0.1:28010/lb/supplier-chain          # 链结构
curl http://127.0.0.1:28010/lb/instances               # 实例列表
```

## 五、审计语料

| 语料 | 位置 |
|---|---|
| **spring-cloud-commons 4.1.4**（评审基线） | `/data/workspace/source-code/code/spring/spring-cloud-commons-4.1.4`（worktree，tag v4.1.4） |
| spring-cloud-commons v4.2.2（对照） | `/data/workspace/source-code/code/spring/spring-cloud-commons` |

## 六、口径约定

1. 版本基线 **SC 2023.0.3 / 4.1.4**（= xhs-project）；4.3.2/5.x 特性一律"版本注记"（如 ApiVersion 在 4.1.4 不存在）；
2. **xhs-project 对照**：自研 `LeastConnectionsLoadBalancer`（ReactorServiceInstanceLoadBalancer 实现）+ `LeastConnectionsLifecycle` + `ZoneLoadBalancerConfig`——非框架内置策略；
3. 引文逐字；示例必须标注；
4. 环境依赖：Nacos `21.130.247.89:18848`（sca-lab-dev）+ nacos-lab 实例。

## 七、已知边界

- C 类手写已移除（如需恢复见 sca-lab git）；
- A/B 端点保留（sca-lab 实测过，本轮未重跑；apiversion 端点在 4.1.4 不可用，已注记）；
- `/lb/apiversion-test` 仅作版本落差演示。
