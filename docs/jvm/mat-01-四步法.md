# mat-01 · MAT 使用四步法

## 一、安装与环境

- 本机：`/data/workspace/tools/mat/mat/`（1.14.0，Java 11 兼容；`mat.zip` 95MB 可删）
- 注意：opencode shell 无图形会话，**GUI 在桌面开**（`./MemoryAnalyzer`）；
  无显示环境的 headless CLI 在此环境会卡（原生启动器进 SWT 事件循环），慎用
- Windows：1.14.0 需 Java 11+ / 1.17.0 需 Java 17+（MAT 不自带 JRE，解压即用）
- 分析的堆转储：`/tmp/opencode/jmap-demo.hprof`（4.3MB）与 OOME 的 `/tmp/opencode/oom/*.hprof`（拷到 Windows 也行，跨平台）
- 大 dump 调 `MemoryAnalyzer.ini` 里 `-Xmx`（默认 1024m）；JDK11 已无 `jhat`

## 二、Details 五个体检指标

| 指标 | 含义 |
|---|---|
| Size: 15.6 MB | dump 里**所有对象浅堆大小之和**（不是文件大小；实测与解析器 15.60MB 一致） |
| Classes: 689 | 类对象数（与 hprof CLASS_DUMP 729 有口径差，属正常） |
| Objects: 15.8k | 对象总数（实例+数组+类对象） |
| Class Loader: 4 | = 3 个实例（Boot/Platform/App）+ bootstrap(null) |
| Unreachable Objects Histogram | **可点链接**：不可达对象直方图（见下） |

## 三、"不可达"是什么

**不可达 = 从 GC Roots 出发顺引用链走不到的对象**（已是垃圾，等下次 GC 回收）。
dump 是内存快照，"变成垃圾"和"被回收"之间的时间窗内抓快照就有它们；
OOME 自动 dump（不先 GC）必然含，`jmap -dump:live`（先 full GC）通常没有。

- 本 dump：171 个 / 5KB（byte[] 52、String 53、Class 40、SoftReference 等）——零头
- 左侧 Inspector 对它们显示 `no GC root`（shallow/retained 都是 0）
- 底部日志 `Removed 171 unreachable objects …` = 解析时从对象图剔除（默认行为）
- **不可达 ≠ 泄漏**（反过来）：
  不可达=已是垃圾（下次 GC 收）；泄漏=意外还可达（永远不收）。
  排查泄漏看"可达对象里谁不该活着"（Path to GC Roots），不是这个表。

## 四、两块关键视图

**Inspector（对象检视器）**：选中对象的身份+大小+字段值。
- 只看 `ref` 非 null 的行（引用链出口）；int/long/boolean/null/eetop 无视
- 示例：`Thread main` 的 `Shallow Size: 120 B` / `Retained Size: 15MB` /
  `GC root: Thread` / 字段 `threadLocals`（生产 ThreadLocal 泄漏就藏在这条链）

**Biggest Objects by Retained Size（饼图）**：Top 支配者的可视化。
- 本 dump：一片 15MB（main 线程 ~96%）；点击跳转对应对象

## 五、看 dump 的标准四步

1. **Histogram**：什么类多/大（等价 jmap -histo）；右键 `List Objects → with incoming references`（谁引用我）、
   `Merge Shortest Paths to GC Roots → exclude weak/soft`（为什么活着）
2. **Dominator Tree**：谁"持有"它们（按 Retained Size）；从根展开，每层选 Retained 最大的孩子
3. **Path to GC Roots**（右键 → exclude weak/soft）：根在哪。预期链：
   `Thread main → Java frame main() → local variable bomb → ArrayList → elementData → byte[] ×2392`
4. **翻译成代码**：链条尽头（字段/局部变量）= 凶手 → 改它

实测结论（OomDemo dump）：2392 个 `byte[]`/15.8MB，全被 `bomb` 引用着；
`elementData` 是 `Object[15]`（15 个 1MB 数组）；`Locked…`/`-locked` 细节略。
注意直方图"按类"的误导：2392 个 byte[] 里只有 15 个是 bomb 的（支配树才看得出谁负责）。

## 六、两个尺寸概念

- **Shallow Size**：对象自身大小（Histogram 用它）
- **Retained Size**：把它回收能释放的总量（Dominator/Leak Suspects 用它）；**找泄漏永远看它**

## 七、只需掌握的 5 件事

1. dump 是什么、怎么产生（jmap/jcmd/OOME）
2. GC Root + 可达性（唯一必须刻进脑子的）
3. Shallow vs Retained（找凶手看 Retained）
4. 四步法
5. OOME 三件套配置
其余（格式细节、界面按钮、Inspector 字段表、OQL）= 用到再查。

## 八、坑

- Leak Suspects 是"猜测"不是结论（用 Path to GC Roots 验证）
- 分析生产 dump：先找**业务类**，别被 char[]/HashMap$Node 基础设施带跑
- 首次打开大 dump 建索引慢是正常的
