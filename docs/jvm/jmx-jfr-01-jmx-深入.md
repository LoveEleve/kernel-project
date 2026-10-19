# jmx-jfr-01 · JMX 深入（自定义 MBean + 远程客户端 + 通知 + 认证）

> Session A+B 实战记录。实验目录：`experiments/jvm-diagnostics/09-jmx-jfr/`

## 一、Session A：自定义 MBean

### 知识点

- **PlatformMBeanServer 是全局注册中心**（一个 JVM 只有一个），JDK 自带的 25 个 MBean + 你注册的都在这里
- **ObjectName 命名**：`域名:键=值`（如 `com.drill.jmx:type=Hello`），全局唯一
- **StandardMBean 包装**：接口驱动，`getCount()` → JMX 属性 `Count`（JavaBean 首字母大写规则）
- **远程客户端链路**（jconsole 本地连接同款）：attach → `startLocalManagementAgent()`（JDK9+，反射调）→ JMX URL → `JMXConnector` → `MBeanServerConnection`

### 实测证据

```
attach -> JMX URL = service:jmx:rmi://127.0.0.1/stub/...
Hello.Count = 1 → 1.5s 后 = 2（远程看到动态增长）
Hello.greet("jmx-client") = "hello jmx-client from CustomMBeanDemo"
注册中心 MBean 总数 = 26（含 java.lang:type=Memory 等 25 个 JDK 自带）
HeapMemoryUsage used=10485760 max=4179623936
```

### 坑记录

- JDK8 的 `loadAgent("lib/management-agent.jar")` 在 JDK9+ 没了 → 反射调 `startLocalManagementAgent()`
- JMX 属性名首字母大写（`Count` 而非 `count`）

## 二、Session B：通知机制（订阅-推送）

### 知识点

- 通知三角色：`NotificationEmitter`（发布）/ `NotificationListener`（订阅）/ `Notification`（事件）
- JDK 内置发布者：`GarbageCollectorMXBean` 实现 `NotificationEmitter`
- 监听器注册在客户端，事件经 RMI 推送
- `System.gc()` 只触发 Old 的 major GC（Young 的要等自然 young GC）

### 实测证据

```
已订阅 java.lang:name=G1 Young Generation,type=GarbageCollector
已订阅 java.lang:name=G1 Old Generation,type=GarbageCollector
远程调用 MemoryMXBean.gc() ...
[GC-Notify] G1 Old Generation | cause=System.gc() | duration=13ms | action=end of major GC
[GC-Notify] G1 Old Generation | cause=System.gc() | duration=9ms  | action=end of major GC
```

## 三、Session B：远程认证（双文件 = 认证 + 授权分离）

### 知识点

| 文件 | 作用 | 实测失败信息 |
|---|---|---|
| `jmxremote.password.file` | 认证（你是谁） | `Authentication failed! Credentials required` / `Invalid username or password` |
| `jmxremote.access.file` | 授权（readwrite/readonly） | `Access denied! No entries...`（缺文件时） |

### 启动参数全家桶（生产可抄）

```bash
-Dcom.sun.management.jmxremote.port=9998
-Dcom.sun.management.jmxremote.authenticate=true
-Dcom.sun.management.jmxremote.password.file=...   # chmod 600
-Dcom.sun.management.jmxremote.access.file=...     # chmod 600（drill readwrite）
-Dcom.sun.management.jmxremote.ssl=false           # 生产建议 true
-Djava.rmi.server.hostname=127.0.0.1                # 绑本机防暴露
```

### 实测证据

```
无凭证连接失败 ✓ 认证生效: SecurityException -> Authentication failed! Credentials required
凭证连接成功 ✓ Hello.Count = 1
远程调用 greet = hello auth from CustomMBeanDemo
错密码连接失败 ✓: SecurityException -> Authentication failed! Invalid username or password
```

### 客户端 URL 格式

`service:jmx:rmi://host:port/jndi/rmi://host:port/jmxrmi`

## 四、相关文件

- `09-jmx-jfr/CustomMBeanDemo.java`：注册自定义 MBean（靶子）
- `09-jmx-jfr/JmxClientDemo.java`：远程读自定义 MBean + JDK MBean
- `09-jmx-jfr/GcNotifyClientDemo.java`：远程订阅 GC 通知
- `09-jmx-jfr/JmxAuthClientDemo.java`：认证/授权失败+成功三例
