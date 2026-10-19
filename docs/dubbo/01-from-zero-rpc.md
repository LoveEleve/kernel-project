# 01 从0开始：RPC 为什么存在？手写最简 RPC 会死在哪？

> 定位：Dubbo 系列开篇——**问题驱动**（从读者世界出发，演化式搭建，不先给架构）；源码域规划（D-1~D-8）只作覆盖清单，见 [00-系列规划](00-系列规划.md)。
> lab：`lab-naive`（手写最简 RPC，零第三方依赖）；证据：`dubbo-lab/evidence/01-naive-rpc.log`。
> 课程对照：Dubbo 01 温故知新 / 12 分层；RPC 01 核心原理 / 07 设计一个灵活 RPC 框架。

---

## 一、从一行本地调用说起

你一定写过这样的代码：

```java
DemoService demo = new DemoServiceImpl();
String result = demo.sayHello("naive");
```

这一行在 JVM 里发生了什么：

1. `new DemoServiceImpl()` 在堆里创建一个对象，`demo` 是栈上的引用——**本质是一个内存地址**；
2. `demo.sayHello("naive")` 通过引用找到对象、定位方法、执行、返回；
3. 全程没有"网络"，也没有"序列化"——参数和返回值就是栈上和堆里的值，传的是引用。

现在问一个问题：**如果 `DemoServiceImpl` 在另一个进程（甚至另一台机器）里，这行代码还能用吗？**

不能。而且不是"改改就能用"，是**三个底层事实**卡死了它。我们一个一个看。

---

## 二、跨进程的三个断点

### 2.1 断点一：地址空间隔离（L0 物理事实）

进程有各自独立的虚拟地址空间。`demo` 这个引用里的地址，只在**当前进程**的页表里有意义——发给另一个进程，那边根本没有这个地址。

所以跨进程能传的只有**值**，不能传**引用**。对象必须先变成一段字节，到达对岸后再变回对象——这一步就是**序列化/反序列化**。

### 2.2 断点二：网络只能传字节，而且没有边界

即使序列化成了字节，Socket 也只提供"字节流"：你 write 两次 100 字节，对端可能一次读到 200 字节（粘包），也可能先读到 30 字节（半包）。

所以字节流上必须自己划**消息边界**。最朴素的做法：每条消息前加一个固定长度的"长度前缀"。

### 2.3 断点三：调用是双向的，而且"调用哪个方法"也得传

本地调用时，"调哪个方法、传什么参"由字节码指令和栈帧直接表达；跨进程后，这些也必须变成数据：

- 接口名（对岸要找到实现类）；
- 方法名 + 参数类型（对岸要定位到具体方法，Java 支持重载）；
- 参数值；
- 以及一条**回程通道**：结果或异常要原样送回来。

把这三点合起来，RPC 要解决的就是四件事：**代理（让调用看起来像本地）、序列化、网络传输、寻址（找到对端）**。

---

## 三、手写一个最简 RPC（核心约 170 行，不含注释）

先动手写一个能跑的玩具——后面所有"为什么 Dubbo 要这样设计"，都要从这个玩具的缺陷里长出来。

### 3.1 调用信息与返回信息

```java
public class RpcRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String interfaceName;
    private String methodName;
    private Class<?>[] parameterTypes;
    private Object[] arguments;

    public RpcRequest() {
    }

    public RpcRequest(String interfaceName, String methodName,
                      Class<?>[] parameterTypes, Object[] arguments) {
        this.interfaceName = interfaceName;
        this.methodName = methodName;
        this.parameterTypes = parameterTypes;
        this.arguments = arguments;
    }

    public String getInterfaceName() {
        return interfaceName;
    }

    public String getMethodName() {
        return methodName;
    }

    public Class<?>[] getParameterTypes() {
        return parameterTypes;
    }

    public Object[] getArguments() {
        return arguments;
    }
}
```

```java
public class RpcResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    private Object result;
    private Throwable error;

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    public Throwable getError() {
        return error;
    }

    public void setError(Throwable error) {
        this.error = error;
    }
}
```

### 3.2 消息边界：4 字节长度前缀

```java
final class Frames {

    private Frames() {
    }

    static void write(OutputStream out, Object obj) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(obj);
        }
        byte[] data = bos.toByteArray();
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(data.length);   // 先写长度
        dos.write(data);             // 再写内容
        dos.flush();
    }

    static Object read(InputStream in) throws IOException, ClassNotFoundException {
        DataInputStream dis = new DataInputStream(in);
        int len = dis.readInt();     // 先读长度
        byte[] data = new byte[len];
        dis.readFully(data);         // 按长度读满（解决半包）
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return ois.readObject();
        }
    }
}
```

### 3.3 服务端：反射执行

```java
public class NaiveRpcServer {

    private final Object serviceImpl;
    private final int port;

    public NaiveRpcServer(Object serviceImpl, int port) {
        this.serviceImpl = serviceImpl;
        this.port = port;
    }

    public void start() throws IOException {
        ServerSocket serverSocket = new ServerSocket(port);
        System.out.println("[naive-server] listening on " + port);
        while (true) {
            Socket socket = serverSocket.accept();          // BIO：一个连接
            new Thread(() -> handle(socket), "naive-worker").start();  // 一个线程
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            RpcRequest request = (RpcRequest) Frames.read(s.getInputStream());
            RpcResponse response = new RpcResponse();
            try {
                Method method = Class.forName(request.getInterfaceName())
                        .getMethod(request.getMethodName(), request.getParameterTypes());
                Object result = method.invoke(serviceImpl, request.getArguments());
                response.setResult(result);
                System.out.println("[naive-server] invoked " + request.getMethodName());
            } catch (Throwable t) {
                response.setError(t);
            }
            Frames.write(s.getOutputStream(), response);
        } catch (Exception e) {
            System.out.println("[naive-server] connection error: " + e);
        }
    }
}
```

### 3.4 客户端：JDK 动态代理

```java
public class NaiveRpcClient {

    private final String host;
    private final int port;
    private final Class<?> interfaceClass;

    public NaiveRpcClient(String host, int port, Class<?> interfaceClass) {
        this.host = host;
        this.port = port;
        this.interfaceClass = interfaceClass;
    }

    @SuppressWarnings("unchecked")
    public <T> T getProxy() {
        return (T) Proxy.newProxyInstance(
                interfaceClass.getClassLoader(),
                new Class<?>[]{interfaceClass},
                (proxy, method, args) -> {
                    RpcRequest request = new RpcRequest(
                            interfaceClass.getName(), method.getName(),
                            method.getParameterTypes(), args);
                    try (Socket socket = new Socket(host, port)) {   // 每次调用新建连接
                        Frames.write(socket.getOutputStream(), request);
                        RpcResponse response = (RpcResponse) Frames.read(socket.getInputStream());
                        if (response.getError() != null) {
                            throw response.getError();
                        }
                        return response.getResult();
                    }
                });
    }
}
```

### 3.5 一次调用的完整时序

`demo.sayHello("naive")` 进入代理后：

1. 代理把 `method` 与 `args` 打包成 `RpcRequest`（接口名/方法名/参数类型/参数值）；
2. 建立 Socket 连接到 `127.0.0.1:20990`；
3. 写入一帧：4 字节长度 + Java 序列化字节；
4. 服务端 `accept()`，起一个线程；
5. 读帧 → 反序列化出 `RpcRequest`；
6. `Class.forName` + `getMethod` + `invoke` 反射调用 `NaiveDemoServiceImpl`；
7. 结果包成 `RpcResponse`，序列化写回；
8. 客户端反序列化，返回给调用方——**调用方看到的仍是 `String`**。

到这里，RPC 的"四件事"全都有了最小实现：代理（第 1 步）、序列化（第 3/7 步）、网络传输（第 2/3 步）、寻址（写死的 `127.0.0.1:20990`）。

---

## 四、跑一遍

实验脚本：`dubbo-lab/scripts/01-naive-rpc.sh`；证据：`evidence/01-naive-rpc.log`。

```
naive-server 就绪（4s，端口 20990）
naive-client exit=0
--- client 侧结果 ---
[naive-client] sayHello = hello naive from naive-rpc
[naive-client] getUser  = User{id=1, name='user-1'}
[naive-client] done
--- server 侧日志 ---
[naive-server] listening on 20990
[naive-server] invoked sayHello
[naive-server] invoked getUser
naive-server 已停止，20990 已释放
```

值得注意：**同一个 `DemoService` 接口**，后面第 02 篇会用 Dubbo 再调一次（`lab-api` 里共享同一份接口定义）——两条路径的对照，就是本系列的演化主线。

---

## 五、它跑通了，然后呢？——七个死点

玩具能跑，但每一处"省事"都是生产事故的种子。把死点列全，**后续每篇解决一个**：

| # | 死点（现状） | 死在哪（具体场景） | 谁来解 |
|---|---|---|---|
| 1 | 地址写死 `127.0.0.1:20990` | provider 扩容/换机器，客户端要改配置重启 | 第 03 篇：注册中心与服务发现 |
| 2 | 每次调用新建连接、读阻塞无超时 | 一个慢 provider 让调用方线程全部挂死 | 第 06 篇：协议/传输/异步 Future |
| 3 | 裸协议（Java 序列化 + 自定义帧） | 跨语言不通、无版本兼容、反序列化有安全风险 | 第 06 篇：协议设计与编解码 |
| 4 | 零扩展点（`new NaiveRpcServer`） | 想换协议/序列化/负载均衡，只能改代码 | 第 04 篇：SPI 扩展体系 |
| 5 | 无容错（失败就抛给用户） | provider 抖动/宕机直接变业务故障 | 第 05/07 篇：集群容错/路由/LB |
| 6 | 无横切能力（日志/鉴权/限流没处插） | 每个方法手写重复代码 | 第 05/08 篇：Filter 链与治理 |
| 7 | 配置散落、无治理（线程池/上下线/诊断） | 超时为什么不生效？上线为什么掉流量？ | 第 02/08 篇：配置与生产治理 |

> 死点 4 是"元问题"：死点 2/3/5 的解法（多种传输/多种协议/多种负载均衡与容错）都需要**可插拔**——这就是 Dubbo 微内核 + SPI 的由来。

---

## 六、Dubbo 的全景：它把死点变成了什么

先给地图，不展开（演化式：后面每篇填一块）。

**四个角色**：

```
Provider（暴露服务）──注册──▶ Registry（注册中心）◀──订阅── Consumer（调用服务）
        │                                                        │
        └─────────────── 被调用（RPC）───────────────────────────┘
                        Monitor（调用统计，旁路）
```

**十层架构**（自上而下，括号内是本系列对应篇目）：

| 层 | 职责 | 对应死点 |
|---|---|---|
| Service | 业务接口与实现 | — |
| Config | 配置与启动（Bootstrap/ServiceConfig/ReferenceConfig） | 7 → 篇 02 |
| Proxy | 动态代理：接口调用 → Invocation | 调用链入口 → 篇 05 |
| Registry | 注册/订阅/通知 | 1 → 篇 03 |
| Cluster | 容错/路由/负载均衡 | 5 → 篇 05/07 |
| Monitor | 调用统计与监控 | 7 → 篇 08 |
| Protocol | 协议：export/refer/invoke | 3 → 篇 06 |
| Exchange | 请求响应与 Future | 2 → 篇 06 |
| Transport | Netty 传输 | 2 → 篇 06 |
| Serialize | 序列化 | 3 → 篇 06 |

**贯穿全系列的"元能力"**：微内核 + SPI（死点 4）→ 篇 04。

---

## 七、代价与边界（这一篇不承诺什么）

- **"像本地一样调用"是假的**：每次调用多了序列化、网络往返、超时语义；本地调用不会"部分失败"，远程调用会；
- 玩具版**没有**：超时、重连、连接池、并发安全（一连接一线程）、协议兼容、安全白名单——别拿去生产；
- 序列化是**双向信任边界**：Java 原生反序列化有已知反序列化漏洞面，生产必须用受控序列化（篇 06 讲白名单）；
- RPC 不是"把本地调用改远程"的银弹：跨进程 = 分布式，分布式就要面对部分失败、幂等、重试放大（篇 07）。

---

## 附录 A：面试速答（附带）

> RPC 要解决四件事：代理（让调用像本地）、序列化（引用不能跨进程，只能传值）、网络传输（字节流要划消息边界）、寻址（找到对端）。为什么不能直接传对象：进程有独立地址空间，引用只是本进程地址；为什么要有超时：远程调用会部分失败，没有超时会挂死调用方线程；为什么 Dubbo 要 SPI：协议/序列化/负载均衡/容错都要可替换，不能写死。RPC 和本地调用的本质差异是"网络不可靠"——本地调用只有成功，远程调用有超时、重试、乱序、重复。

## 附录 B：实验与复现

```bash
cd dubbo-lab
export JAVA_HOME=/env/javaEnv/jdk17
mvn -DskipTests install
bash scripts/01-naive-rpc.sh     # 输出见 evidence/01-naive-rpc.log
```

| 观测点 | 结果 |
|---|---|
| 服务端 | `listening on 20990` → `invoked sayHello` → `invoked getUser` |
| 客户端 | `sayHello = hello naive from naive-rpc`；`getUser = User{id=1, ...}` |
| 清理 | 服务端停止、20990 释放 |

## 附录 C：坐标与对照

| 内容 | 坐标 |
|---|---|
| 最简 RPC 代码 | `dubbo-lab/lab-naive/src/main/java/com/myxhs/dubbo/lab/naive/`（`NaiveRpcApp` / `NaiveRpcServer` / `NaiveRpcClient` / `Frames` / `RpcRequest` / `RpcResponse`） |
| 共享接口 | `dubbo-lab/lab-api/.../DemoService.java`（第 02 篇用 Dubbo 调同一接口） |
| 实验脚本/证据 | `dubbo-lab/scripts/01-naive-rpc.sh`、`dubbo-lab/evidence/01-naive-rpc.log` |
| 系列规划（覆盖清单） | `docs/dubbo/00-系列规划.md`；源码域 D-1~D-8 见 `docs/plan/framework/source-analysis/20-Dubbo源码分析规划.md` |
| 课程对照 | 《Dubbo源码剖析与实战》01 温故知新 / 12 分层；《RPC实战与核心原理》01 核心原理 / 07 架构设计 |
