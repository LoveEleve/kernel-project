# p9-domain2 · 类加载（P9 深度实验）

> P9 面试核心域 2/6。实验目录：`experiments/jvm-diagnostics/11-p9-classloader/`
> 覆盖：双亲委派 / 自定义 ClassLoader / 热部署 / 初始化时机 / Tomcat 隔离

---

## 实验 1：类加载器层级 + 双亲委派

### 实测结果

```
String/ArrayList → ClassLoader=null（Bootstrap）
HierarchyDemo（本类）→ AppClassLoader

层级链：
MyClassLoader → AppClassLoader → PlatformClassLoader → null(Bootstrap)
（JDK9+ ExtClassLoader 改名 PlatformClassLoader）
```

### 双亲委派机制

```
loadClass(name) 被调用：
  1. findLoadedClass(name) → 已加载？返回
  2. parent.loadClass(name) → 父加载器尝试
  3. 父返回 null → findClass(name) → 自己加载
```

**为什么要双亲委派**：
- 安全：防止 `java.lang.String` 被自定义 loader 替换（核心类保护）
- 一致：同一个类全 JVM 只有一份 Class 对象

### P9 追问链

- **Q：双亲委派怎么保证 String 安全？** → String 由 Bootstrap 加载，自定义 loader 委派到顶，无法替换
- **Q：JDK9 为什么改 Ext → Platform？** → 模块化后 Ext 职责弱化，改为 Platform 承载 Java SE 模块
- **Q：所有 ClassLoader 都遵守双亲委派吗？** → 不是（SPI/Tomcat/OSGi 打破，见实验 2）

---

## 实验 2：自定义 ClassLoader 打破双亲委派

### 测试

`BreakDelegationDemo.java`：两个 MyLoader（parent=null）各自加载同一字节码

### 实测结果

```
c1 (loader1) ≠ c2 (loader2) ≠ cApp (App loader)
appInstance instanceof c1 → false（跨 loader 的 instanceof 失败）
```

### 教学点

- **真正打破委派的机制 = 重写 `loadClass`**（对目标类直接 `findClass`，不调 super）
- `parent=null` 是辅因：防止意外委派到 App/Platform（`parent=null` 时标准 loadClass 仍会先问 Bootstrap，再 findClass）
- **同一个字节码被 N 个 loader 加载 = N 个不同的 Class 对象**（类隔离）
- 跨 loader 的 `instanceof`/强制转换失败（即使字节码完全相同）

### P9 追问链

- **Q：怎么打破双亲委派？** → 重写 `loadClass`（而非 `findClass`），对目标包不委派直接 defineClass
- **Q：为什么 Tomcat 要打破？** → 不同 webapp 可能依赖同一库的不同版本（Spring 5 vs 4），需要隔离
- **Q：SPI 为什么也要打破？** → `java.sql.Driver` 由 Bootstrap 加载接口，实现类在 classpath（App loader），Bootstrap 加载不到 → 线程上下文 ClassLoader 反转委派

---

## 实验 3：热部署原理（Tomcat 同款机制）

### 测试

`HotSwapDemo.java`：loader1 加载 v1 → 磁盘换 v2 → loader2 加载 v2

### 实测结果

```
loader1 → V1
磁盘已换成 v2 字节码
loader2 → V2（新 loader 不缓存）
loader1 再次加载 → V1（缓存命中，仍 v1）
```

### 热部署机制

```
ClassLoader 会缓存已加载的 Class（findLoadedClass）
→ 同一个 loader 重复 loadClass 返回缓存
→ 但"新 loader + 新字节码"= 完全重新加载 = 热部署
Tomcat 重部 webapp 时：销毁旧 webapp loader → 创建新 loader → 加载新 class
```

### P9 追问链

- **Q：热部署怎么做？** → 新 ClassLoader 加载新字节码（旧 loader 连同其加载的类一起废弃）
- **Q：为什么不能在原 loader 上改？** → loader 缓存 Class；且旧 Class 已被引用，替换会导致类型不兼容
- **Q：和 JVM 级热替换（Instrumentation.redefineClasses）区别？** → redefine 不换 Class 对象（同 loader 同 Class），只换方法体；loader 方案换 Class 对象（新类身份）

---

## 实验 4：类初始化时机（<clinit> 触发条件）

> 修正版 `InitTimingDemo2.java`：case 2/6/7 各用独立类，确保独立证明。

### 实测结果（全部独立证明）

| 场景 | 类型 | <clinit> 输出 | 独立证明 |
|---|---|---|---|
| `new Target()` | 主动 | `[Target] clinit` | ✅ |
| 访问 `StaticField.v`（非 final 静态字段） | 主动 | `[StaticField] clinit` | ✅ 独立类 |
| `new Target[3]`（数组定义） | **被动** | 无新增 clinit | ✅（按规范不触发） |
| 访问 `Target.CONST`（static final 常量） | **被动** | 无 clinit（常量内联） | ✅（javap 可证内联） |
| `C5.p`（子类引用父类 static） | **被动**（对 C5） | `[P5] clinit`、无 `[C5]` | ✅ 独立类 |
| `DirectParent.p`（直接访问） | 主动 | `[DirectParent] clinit` | ✅ 独立类 |
| `Class.forName(ReflectTarget)` | 主动 | `[ReflectTarget] clinit` | ✅ 独立类 |

### 主动引用 6 种（触发 <clinit>）

```
1. new
2. 访问静态字段（非 static final 编译期常量）
3. 调用静态方法
4. 反射 Class.forName
5. 子类初始化触发父类（Child init → Parent init）
6. JDK7 动态语言调用（MethodHandle，P9 低频可略）
```

### 被动引用 3 种（不触发）

```
1. 数组定义（MyClass[] arr = new MyClass[10]）
2. 访问 static final 编译期常量（编译期已内联）
3. 子类访问父类 static 字段（通过子类引用，只触发父类）
```

### P9 追问链

- **Q：数组定义为什么不算主动？** → 数组类型由 JVM 直接创建，不走目标类的 <clinit>
- **Q：static final 常量为什么不算？** → 编译期已内联进使用方的常量池，运行时不触发目标类初始化
- **Q：类加载和类初始化是同一时刻吗？** → 不是。加载（字节码→方法区）和初始化（执行 <clinit>）分离；被动引用时"已加载未初始化"

---

## 补充：Tomcat 类加载器隔离（叙事）

```
                      Bootstrap
                          │
                    PlatformClassLoader
                          │
                    AppClassLoader (common)
                          │
            ┌─────────────┼─────────────┐
            │             │             │
      WebappLoader1  WebappLoader2  WebappLoader3   ← 每个 webapp 独立
       (App A)        (App B)        (App C)
       Spring5        Spring4        Spring5
```

**Tomcat 打破双亲委派的方式**：
- 每个 webapp 一个 `WebappClassLoader`（parent = common loader）
- **优先自己加载**（先查 webapp 的 WEB-INF/classes + WEB-INF/lib）
- 找不到才委派给 parent
- 效果：App A 用 Spring 5、App B 用 Spring 4，互不干扰

**P9 答法**："Tomcat 用每个 webapp 独立的 WebappClassLoader，重写 loadClass 先本地后委派，实现同 JVM 内多版本共存。热部署时销毁旧 loader 建新 loader。"

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 实验背书 |
|---|---|---|
| 双亲委派 | 先委派父 loader，父失败才自己加载；防核心类替换 | 实验 1 |
| 打破双亲委派 | 重写 loadClass 不委派（Tomcat/SPI/OSGi） | 实验 2 |
| 类隔离 | 同字节码 N 个 loader = N 个 Class；跨 loader instanceof 失败 | 实验 2 |
| 热部署 | 新 loader + 新字节码 = 新 Class（loader 有缓存） | 实验 3 |
| <clinit> 主动引用 | new/静态字段/静态方法/反射/子类触发父类 | 实验 4 |
| <clinit> 被动引用 | 数组定义/static final 常量/子类引用父类 static | 实验 4 |
| Tomcat 隔离 | 每 webapp 独立 loader，先本地后委派 | 补充 |

## 相关文件

- `11-p9-classloader/HierarchyDemo.java`：类加载器层级
- `11-p9-classloader/BreakDelegationDemo.java`：打破双亲委派
- `11-p9-classloader/HotSwapDemo.java` + `v1-src/` + `v2-src/`：热部署
- `11-p9-classloader/InitTimingDemo.java`：初始化时机（初版）
- `11-p9-classloader/InitTimingDemo2.java`：初始化时机（修正版，独立类证明）
