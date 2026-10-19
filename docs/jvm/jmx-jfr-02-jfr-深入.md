# jmx-jfr-02 · JFR 深入（事件模型 + 自定义 settings + 自定义事件 API + streaming）

> Session C+D 实战记录。实验目录：`experiments/jvm-diagnostics/09-jmx-jfr/`

## 一、Session C：事件模型

### 知识点

| 要素 | 含义 | 例子 |
|---|---|---|
| 事件类型 | JDK 内置 ~108-114 种 | `jdk.ExecutionSample`/`jdk.GCPhasePause`/`jdk.ObjectAllocationSample` |
| threshold | 阈值（超了才记） | `jdk.JavaMonitorEnter` default 20ms / profile 10ms |
| period | 采样周期 | `jdk.ExecutionSample` 每 10ms 采一次 |
| stackTrace | 是否带栈 | true=带调用栈 |

### default vs profile（实测对比）

```
default: 108 个事件（生产连续录，<1% 开销）
profile: 114 个事件（+6 个重事件：ObjectAllocationInNewTLAB/OutsideTLAB、JavaMonitorInflate 等）
         竞争类阈值统一 20ms → 10ms
```

### 预设文件位置

JDK11: `$JAVA_HOME/lib/jfr/{default,profile}.jfc`（XML 格式，非 JSON）

## 二、Session C：自定义 settings（按需录制）

### 知识点

- `.jfc` 是 XML 格式，按需启用事件 + 调阈值
- 录制：`jcmd <pid> JFR.start settings=custom.jfc duration=20s filename=...`
- 读取版本差异：JDK17 `jfr summary/print`；**JDK21+ 才有 `jfr view`**

### custom-minimal.jfc（实战 4 事件）

```xml
<event name="jdk.ExecutionSample">       period=10ms
<event name="jdk.GCPhasePause">          threshold=0ms
<event name="jdk.ObjectAllocationSample"> stackTrace=true
<event name="jdk.JavaMonitorEnter">      threshold=5ms（比 profile 更严）
```

### 实测证据

```
summary: GCPhasePause=3, ExecutionSample=1, Metadata=69KB
JDK21 jfr view gc-pauses: Total 3.68ms / 3 pauses / min 0.08ms / median 1.17ms
```

## 三、Session D：自定义事件 API（业务埋点）

### 知识点

- 继承 `jdk.jfr.Event` + 注解（`@Name/@Label/@Description`、字段 `@Label`）
- `begin/end` 自动计时；`commit()` 提交
- **`getDuration()` 只在读回的 `RecordedEvent` 上有**，实时事件对象没有
- API 版本：`Recording`/`RecordingFile` = JDK9+；**`RecordingStream` = JDK14+**

### 实测证据（打点 5 请求，duration 与 sleep 严格一致）

```
path=/api/order/0 userId=1000 duration=100ms
path=/api/order/1 userId=1001 duration=180ms
...
path=/api/order/4 userId=1004 duration=420ms
```

## 四、Session D：Streaming API（实时流，JDK14+）

### 知识点

- `RecordingStream` = 边录边收，无需 dump 文件，适合线上临时挂流
- 混合订阅：自定义事件 + JDK 内置事件

### 实测证据（JDK21 编译运行）

```
[Stream] path=/stream/order/0 userId=2000 dur=60ms
[Stream] path=/stream/order/1 userId=2001 dur=100ms
...
[Stream/GC] jdk.GCPhasePause dur=4ms       ← 混合消费内置事件
```

### 何时用哪个

- 业务语义埋点（订单超时/缓存击穿）→ 自定义事件 + `Recording`
- 线上实时挂流盯事件 → `RecordingStream`
- 事后深度分析 → `Recording` dump + `jfr view`（Session C）

## 五、相关文件

- `09-jmx-jfr/custom-minimal.jfc`：自定义 4 事件 settings
- `09-jmx-jfr/CustomEventDemo.java`：自定义事件打点 + dump + 读回
- `09-jmx-jfr/JfrStreamDemo.java`：streaming 实时消费（JDK21）
