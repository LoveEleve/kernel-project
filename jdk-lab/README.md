# jdk-lab · JDK 机制实验室

> 目的：把 JDK 机制（反射、注解、类加载、动态代理……）拆成**能跑、能改、能观察**的最小样例。
> 每段代码只演示一个机制，配有运行命令；结论都能用输出直接验证。

## 与其它模块的关系

- `demo-provider` / `demo-gateway`：框架学习（Spring 生态）
- `jdk-lab`：JDK 机制学习，**零第三方依赖**（不引 Spring），用于回答"JDK 到底怎么实现的"

## 编译

```bash
# 在 xhs-demo 根目录
mvn -pl jdk-lab compile
```

产物在 `jdk-lab/target/classes`，全限定类名以 `com.myxhs.demo.jdk` 开头。

也可不用 Maven，直接 javac（任选 JDK 11/17）：

```bash
cd jdk-lab
javac -d /tmp/jdklab $(find src/main/java -name '*.java')
```

## 实验清单与运行命令

### reflect（反射）

| 类 | 演示什么 | 运行命令（在 xhs-demo 根目录） |
|---|---|---|
| `ReflectionBasics` | 迷你 MyBatis：按类名字符串造对象、按字段名填值；`setAccessible` 前后对比 | `java -cp jdk-lab/target/classes com.myxhs.demo.jdk.reflect.ReflectionBasics` |
| `ClassInternals` | 翻开 Class 对象：`reflectionData` 懒加载缓存（需 --add-opens） | `java --add-opens java.base/java.lang=ALL-UNNAMED -cp jdk-lab/target/classes com.myxhs.demo.jdk.reflect.ClassInternals` |
| `InflationDemo` | 反射 accessor 两代切换：Native → Generated（第 16 次，需 --add-opens） | `java --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/jdk.internal.reflect=ALL-UNNAMED -cp jdk-lab/target/classes com.myxhs.demo.jdk.reflect.InflationDemo` |
| `PerfDemo` | 四路径耗时：直接调用 / 缓存反射 / 每次重查找 / 多态调用点 | `java -cp jdk-lab/target/classes com.myxhs.demo.jdk.reflect.PerfDemo` |
| `PerfDemo`（native 对照） | 强制不通胀，永远走 native 通道（慢约 16 倍） | `java -Dsun.reflect.inflationThreshold=100000000 -cp jdk-lab/target/classes com.myxhs.demo.jdk.reflect.PerfDemo` |
| `ExceptionDemo` | 反射四种异常：ClassNotFound / NoSuchMethod / IllegalAccess / InvocationTarget（拆包） | `java -cp jdk-lab/target/classes com.myxhs.demo.jdk.reflect.ExceptionDemo` |
| `RetentionDemo` | 注解保留策略：SOURCE / CLASS / RUNTIME 在 class 文件与运行时的可见性 | `java -cp jdk-lab/target/classes com.myxhs.demo.jdk.reflect.RetentionDemo` |
| `TypeDemo` | 类型体系：Class / ParameterizedType / TypeVariable / WildcardType / GenericArrayType 与变量代换 | `java -cp jdk-lab/target/classes com.myxhs.demo.jdk.reflect.TypeDemo` |

### proxy（动态代理）

| 类 | 演示什么 | 运行命令 |
|---|---|---|
| `TxDemo` | 迷你 Spring 事务：注解 + 动态代理 + 反射调用（BEGIN/COMMIT/ROLLBACK） | `java -cp jdk-lab/target/classes com.myxhs.demo.jdk.proxy.TxDemo` |

### classloading（类加载）

| 类 | 演示什么 | 运行命令 |
|---|---|---|
| `MiniContainer` | 容器编译完成之后，才出现业务类；运行时按名字加载并调用 | `java -cp jdk-lab/target/classes com.myxhs.demo.jdk.classloading.MiniContainer com.myxhs.demo.jdk.classloading.MyBiz` |

## JDK 版本注意

- 本模块 class 目标版本为 **11**，可直接在 JDK 11 / 17 / 21 运行。
- `InflationDemo`、`PerfDemo` 涉及的 accessor 通胀机制是 **JDK 8~17** 的实现；JDK 18 起核心反射改为方法句柄实现（JEP 416），这两个实验在 18+ 上看到的会是另一套实现。
- 跨 JDK 对照实验（如 JDK8 的 `sun.reflect.Reflection` vs JDK11 的 `StackWalker`）用不同 JDK 分别编译运行本模块的代码验证。
