# p9-domain4 · JIT 与去优化（P9 深度实验）

> P9 面试核心域 4/6。实验目录：`experiments/jvm-diagnostics/13-p9-jit/`
> 覆盖：分层编译 / OSR / 内联 / 去优化

## 生产基线

```
-Xms512m -Xmx512m -XX:+UseG1GC -XX:+AlwaysPreTouch
```

---

## 实验 1+2：分层编译 + OSR

### 实测（TieredDemo + PrintCompilation）

```
hotMethod 编译演进：
  level 3 (C1) → level 4 (C2+OSR %) → made not entrant → level 4 (新 C2)

osrLoop 编译演进：
  level 3 (C1+OSR %) → level 4 (C2+OSR %) → made not entrant

字段解读：
  第 2 列 = 编译 ID
  %      = OSR 编译（On-Stack Replacement，循环内替换）
  第 3 列 = 编译层级（1=C1，3=C2，4=C2 tiered）
  made not entrant = 旧编译版本失效
```

### 分层编译流程

```
解释器（字节码计数）
  → 调用计数 > CompileThreshold（默认 10000）
  → C1 编译（level 3，客户端，快速低质量）
  → C1 编译后继续计数
  → iicount > TieredCompileThreshold
  → C2 编译（level 4，服务端，慢速高质量）
  → C2 完成 → 替换 C1 版本（C1 made not entrant）
```

### OSR（On-Stack Replacement）

```
问题：长循环在解释器/C1 执行，C2 编译好了但栈上还是旧版本
解法：OSR 在循环回边（backedge）替换栈帧 → 直接跳到 C2 编译代码
日志特征：`%` 标记 + `@ 字节码偏移`（如 `osrLoop @ 4`）
```

### P9 追问链

- **Q：分层编译为什么？** → C1 快速出码（先用着），C2 慢工出细活（热点再优化）
- **Q：OSR 解决什么？** → 长循环编译期间还在解释器跑，OSR 在回边替换栈帧
- **Q：made not entrant 什么意思？** → 旧编译版本失效（被新版本替换或 deopt）

---

## 实验 3：内联证据

### 实测（PrintInlining 日志）

```
inline (hot):
  java.lang.String::isLatin1 (19 bytes)        inline (hot)
  java.lang.StringLatin1::hashCode (42 bytes)  inline (hot)

not inlineable:
  java.util.HashMap$TreeNode::putTreeVal (314 bytes)  not inlineable  ← 字节码超限
  java.lang.StringUTF16::hashCode (not loaded)        not inlineable  ← 未加载
  java.lang.NullPointerException::<init>              don't inline Throwable constructors
```

### 内联规则

| 条件 | 结果 |
|---|---|
| 字节码 < 325B（`-XX:MaxInlineSize`）+ 热点 | inline (hot) |
| 字节码 > 325B 或 `FreqInlineSize` 限制 | not inlineable |
| 未加载类 | not inlineable |
| Throwable 构造器 | don't inline |

### P9 追问链

- **Q：内联阈值？** → `MaxInlineSize=325`（普通）、`FreqInlineSize=325`（热点，可调大）
- **Q：内联的好处？** → 消除调用开销、暴露优化机会（常量传播/死代码消除）
- **Q：内联的坏处？** → 代码膨胀（i-cache miss）、编译时间增加

---

## 实验 4：去优化触发

### 实测（LogCompilation XML）

```
<uncommon_trap reason='unstable_if' action='reinterpret' compiler='c2' level='4'/>
<uncommon_trap bci='5'  reason='null_check'   action='maybe_recompile'/>
<uncommon_trap bci='13' reason='predicate'     action='maybe_recompile'/>

process 方法编译链：
  task_queued compile_id=227 count=640 level=3   ← C1 编译
  nmethod compile_id=227 count=4703              ← C1 完成
  task_queued compile_id=259 count=9728          ← 再次排队（准备 C2）
```

### 去优化触发原因

| reason | 场景 |
|---|---|
| `unstable_if` | 投机分支预测失效（类型假设变） |
| `null_check` | 空指针检查（对象变 null） |
| `predicate` | 谓词失效（循环条件变化） |

### 去优化流程

```
C2 投机优化（基于 profile 假设）
  → 运行时假设失效（uncommon trap）
  → 去优化：回退到解释器/C1 执行
  → 收集新 profile → 重新编译（更保守的版本）
```

### P9 追问链

- **Q：什么是投机优化？** → C2 基于历史 profile 假设（如"一直是 String"），生成特化代码
- **Q：去优化触发条件？** → 类型假设失效/空指针/谓词变化（uncommon trap）
- **Q：deopt 后怎么办？** → 回解释器/C1 执行，收集新 profile 重新编译
- **Q：怎么减少 deopt？** → 避免类型频繁变化、减少分支抖动

---

## 补充：JIT 编译阈值（P9 了解级）

```
-XX:CompileThreshold=10000        ← 解释器→C1 调用计数阈值
-XX:TieredCompileThreshold=...    ← C1→C2 阈值（自适应）
-XX:OnStackReplacePercentage=...  ← OSR 回边阈值
-XX:MaxInlineSize=325             ← 内联最大字节码
-XX:FreqInlineSize=325            ← 热点内联最大字节码
```

## 补充：C1 vs C2 编译器对比

| | C1（客户端） | C2（服务端） |
|---|---|---|
| 目标 | 快速编译，低质量代码 | 慢速编译，高质量代码 |
| 层级 | level 1-3 | level 4 |
| 优化 | 局部优化、简单内联 | 全局优化、逃逸分析、循环优化 |
| 编译时间 | 毫秒级 | 秒级 |
| 适用 | 短生命周期应用 | 长生命周期服务端 |

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 实验背书 |
|---|---|---|
| 分层编译 | 解释器→C1(快)→C2(好)，计数触发 | 实验 1 |
| OSR | 循环回边替换栈帧，让长循环用上 C2 代码 | 实验 2（`%` 标记） |
| made not entrant | 旧编译版本失效（被替换/deopt） | 实验 1 |
| 内联阈值 | MaxInlineSize=325B；热点+小方法才内联 | 实验 3（inline (hot)） |
| 内联好处/坏处 | 消除调用开销+暴露优化；代码膨胀+i-cache | 实验 3 |
| 去优化触发 | unstable_if/null_check/predicate（投机失效） | 实验 4（uncommon_trap） |
| 投机优化 | C2 基于 profile 假设生成特化代码 | 实验 4 |
| deopt 后 | 回解释器/C1，收集新 profile 重新编译 | 实验 4 |
| C1 vs C2 | C1=快低质量；C2=慢高质量 | 补充（对比表） |

## 相关文件

- `13-p9-jit/TieredDemo.java`：分层编译+OSR 靶子
- `13-p9-jit/InlineDemo.java`：内联演示
- `13-p9-jit/DeoptDemo.java`：去优化触发演示
- `/data/workspace/arthas-output/{inline,hotspot}.log/xml`：编译/内联/去优化日志
