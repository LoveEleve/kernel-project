# 04 高级数据结构：Stream、Bitmap、HyperLogLog、Geo（核心点·源码解析）

> 定位：**源码解析与原理**·核心点版——每个结构只抓 1–2 条主线（它干了什么 → 核心源码 → 原理 → 边界）；不铺全命令表，API 细节查官方文档；面试速答见附录 A。
> 源码基线：Redis 7.2.11；实验证据：`redis-lab/evidence/05-advanced-7.log`（本课新增 `scripts/05-advanced.sh`）。
> 说明：Bloom 过滤器不属于 Redis 核心（RedisBloom 为模块），本文不展开——它的原理（bitmap + 多哈希）在本文 Bitmap 节已具备理解基础。

---

## 一、源码地图

| 文件 | 职责 | 本文引用点 |
|---|---|---|
| `stream.h` / `t_stream.c` | 消息流：rax + listpack 存储、消费组与 PEL | `stream`、`streamCG`、`streamNACK`、`streamAppendItem`、`xackCommand` |
| `bitops.c` | 位操作：位寻址、位计数 | `getBitOffsetFromArgument`、`setbitCommand`、`redisPopcount` |
| `hyperloglog.c` | 基数估计：稀疏/稠密编码与估算 | `hllhdr`、`HLL_DENSE_*`、`hllSparseSet`、`hllCount` |
| `geo.c` / `geohash.c` / `geohash_helper.c` | 地理索引：geohash 编码与邻域查询 | `interleave64`、`geoaddCommand`、`geohashCalculateAreasByShapeWGS84` |
| `config.c` | 本课涉及的默认值 | stream 节点 4096B/100 条、HLL 稀疏上限 3000B |

---

## 二、定位：四个结构各自解决什么

**先看一条命令的输出**（实验证据 §附录 B）：

```
SETBIT b 100 1        → STRLEN 13（1 个 bit 撑出 13 字节？）
PFADD 1000 个元素      → PFCOUNT=1001，内存 2232 字节（1000 个元素只花 2KB？）
GEOADD 北京            → ZSCORE = 4069885548668386（地理坐标变成 52 位整数？）
XADD s 1-0 f a        → 消息进 rax+listpack（为什么不用 List？）
```

| 结构 | 解决什么 | 基座 | 一句话原理 | 核心代价 |
|---|---|---|---|---|
| **Stream** | 可回放、可确认（ACK）的消息日志 | rax + listpack | 消息 ID 当 key，多个消息打包进 listpack 节点 | 未 ACK 消息在 PEL 里持续堆积 |
| **Bitmap** | 1 bit 粒度的布尔状态 | String（SDS） | `byte = offset>>3`，字节内 `7-(offset&7)` 位 | 稀疏高位会按最大 offset 撑大整个字符串 |
| **HyperLogLog** | 海量去重计数（只估个数） | String（SDS） | 哈希前导零长度记入 16384 个寄存器，调和平均估算 | 只估基数，不可枚举/不可逆 |
| **Geo** | 地理邻近查询 | ZSet | lat/long 交织成 52 位整数当 score，邻近查询变 zset 范围查询 | 邻域计算需覆盖 9 格 + 距离过滤 |

共同点：它们都不是新的"类型底座"，而是**在已有点上构建**——Stream 是新的值类型（内部用 rax/listpack），另外三个都是"普通 String/ZSet 的特殊编码用法"（Bitmap/HLL 是 String，Geo 是 ZSet）。

---

## 三、Stream：rax + listpack 的消息日志

**它干了什么**：`XADD s 1-0 f a` 追加一条消息；`XREADGROUP GROUP g c1 STREAMS s >` 让消费组读走它；`XACK` 确认处理完成。消息可回放、可多组消费、可确认——这是它和 List 队列的本质区别。

### 3.1 存储结构

**顶层结构**（`stream.h:11-24`）：

```c
/* Stream item ID: a 128 bit number composed of a milliseconds time and
 * a sequence counter. ... */
typedef struct streamID {
    uint64_t ms;        /* Unix time in milliseconds. */
    uint64_t seq;       /* Sequence number. */
} streamID;

typedef struct stream {
    rax *rax;               /* The radix tree holding the stream. */
    uint64_t length;        /* Current number of elements inside this stream. */
    streamID last_id;       /* Zero if there are yet no items. */
    streamID first_id;      /* The first non-tombstone entry, zero if empty. */
    streamID max_deleted_entry_id;  /* The maximal ID that was deleted. */
    uint64_t entries_added; /* All time count of elements added. */
    rax *cgroups;           /* Consumer groups dictionary: name -> streamCG */
} stream;
```

一个 stream = **rax（基数树）+ 若干 listpack 节点**：rax 的 key 是消息 ID（128 位，按大端写入以保持字典序），value 是一个 listpack，里面打包着多条消息。

**追加一条消息**（`t_stream.c:427-670`，关键段）：先生成 ID（显式指定或自动递增）：

```c
    streamID id;
    if (use_id) {
        if (seq_given) {
            id = *use_id;
        } else {
            /* The automatically generated sequence can be either zero (new
             * timestamps) or the incremented sequence of the last ID. ... */
            if (s->last_id.ms == use_id->ms) {
                if (s->last_id.seq == UINT64_MAX) {
                    errno = EDOM;
                    return C_ERR;
                }
                id = s->last_id;
                id.seq++;
            } else {
                id = *use_id;
            }
        }
    } else {
        streamNextID(&s->last_id,&id);
    }
```

然后定位/创建尾部节点（`t_stream.c:532-553` 节选）——节点是否"装得下"由两个参数控制：

```c
        size_t node_max_bytes = server.stream_node_max_bytes;
        if (node_max_bytes == 0 || node_max_bytes > STREAM_LISTPACK_MAX_SIZE)
            node_max_bytes = STREAM_LISTPACK_MAX_SIZE;
        if (lp_bytes + totelelen >= node_max_bytes) {
            new_node = 1;
        } else if (server.stream_node_max_entries) {
            unsigned char *lp_ele = lpFirst(lp);
            /* Count both live entries and deleted ones. */
            int64_t count = lpGetInteger(lp_ele) + lpGetInteger(lpNext(lp,lp_ele));
            if (count >= server.stream_node_max_entries) new_node = 1;
        }
```

默认 `stream-node-max-bytes = 4096`、`stream-node-max-entries = 100`（`config.c:3225`、`3210`），硬上限 `STREAM_LISTPACK_MAX_SIZE = (1<<30)`（`t_stream.c:54`）——**节点级分片**：一个节点写满就换下一个 rax 节点，而不是重写整棵结构。

**新节点的第一个 listpack 会写入 master entry**（`t_stream.c:497-527` 注释原图）：

```
     * +-------+---------+------------+---------+--/--+---------+---------+-+
     * | count | deleted | num-fields | field_1 | field_2 | ... | field_N |0|
     * +-------+---------+------------+---------+--/--+---------+---------+-+
```

master entry 记录"这一节点里消息的公共字段名"；后续消息的字段名如果与它一致，就只存值（**SAMEFIELDS** 压缩），字段名不重复存储。消息本体编码（`t_stream.c:618-639` 注释原图）：

```
     * +-----+--------+----------+-------+-------+-/-+-------+-------+--------+
     * |flags|entry-id|num-fields|field-1|value-1|...|field-N|value-N|lp-count|
     * +-----+--------+----------+-------+-------+-/-+-------+-------+--------+
     *
     * The entry-id field is actually two separated fields: the ms
     * and seq difference compared to the master entry.
```

即 **ID 差分 + 字段名省略 + lp-count 反向可遍历**。追加代码（`t_stream.c:645-659` 节选）：

```c
    for (int64_t i = 0; i < numfields; i++) {
        sds field = argv[i*2]->ptr, value = argv[i*2+1]->ptr;
        if (!(flags & STREAM_ITEM_FLAG_SAMEFIELDS))
            lp = lpAppend(lp,(unsigned char*)field,sdslen(field));
        lp = lpAppend(lp,(unsigned char*)value,sdslen(value));
    }
    /* Compute and store the lp-count field. */
    int64_t lp_count = numfields;
    lp_count += 3; /* Add the 3 fixed fields flags + ms-diff + seq-diff. */
```

**为什么不是 List**：消息需要"按 ID 随机定位 + 回放 + 确认"，List 的语义（两端推拉、无 ID、无确认）一个都不满足；而 rax 支持按 ID 定位，listpack 支持高效打包，两者组合同时拿到"顺序扫描快"和"内存省"。

### 3.2 消费组与 PEL

**结构**（`stream.h:55-97`，节选）：

```c
/* Consumer group. */
typedef struct streamCG {
    streamID last_id;       /* Last delivered (not acknowledged) ID ... */
    long long entries_read; /* ... */
    rax *pel;               /* Pending entries list. ... The key of the radix
                               tree is the ID as a 64 bit big endian number,
                               while the associated value is a streamNACK
                               structure.*/
    rax *consumers;         /* A radix tree representing the consumers by name ... */
} streamCG;

/* A specific consumer in a consumer group. */
typedef struct streamConsumer {
    ...
    rax *pel;                   /* Consumer specific pending entries list: ...
                                   values are the same streamNACK structure referenced
                                   in the "pel" of the consumer group structure
                                   itself, so the value is shared. */
} streamConsumer;

/* Pending (yet not acknowledged) message in a consumer group. */
typedef struct streamNACK {
    mstime_t delivery_time;     /* Last time this message was delivered. */
    uint64_t delivery_count;    /* Number of times this message was delivered.*/
    streamConsumer *consumer;   /* The consumer this message was delivered to ... */
} streamNACK;
```

**PEL 是两层 rax**：组级 `cg->pel`（ID→NACK）与消费者级 `consumer->pel`（ID→**同一个 NACK 指针**，共享）；NACK 记录"投给谁、投了几次、何时投的"。

**投递时写入 PEL**（`t_stream.c:1749-1797`，节选）——XREADGROUP 送达消息时：

```c
        if (group && !noack) {
            unsigned char buf[sizeof(streamID)];
            streamEncodeID(buf,&id);

            /* Try to add a new NACK. ... */
            streamNACK *nack = streamCreateNACK(consumer);
            int group_inserted =
                raxTryInsert(group->pel,buf,sizeof(buf),nack,NULL);
            int consumer_inserted =
                raxTryInsert(consumer->pel,buf,sizeof(buf),nack,NULL);
            ...
            if (group_inserted == 0) {
                streamFreeNACK(nack);
                nack = raxFind(group->pel,buf,sizeof(buf));
                ...
                raxRemove(nack->consumer->pel,buf,sizeof(buf),NULL);
                /* Update the consumer and NACK metadata. */
                nack->consumer = consumer;
                nack->delivery_time = commandTimeSnapshot();
                nack->delivery_count = 1;
                /* Add the entry in the new consumer local PEL. */
                raxInsert(consumer->pel,buf,sizeof(buf),nack,NULL);
            }
```

**确认时删除 PEL**（`t_stream.c:2865-2875`）——XACK：

```c
        /* Lookup the ID in the group PEL: it will have a reference to the
         * NACK structure that will have a reference to the consumer, so that
         * we are able to remove the entry from both PELs. */
        streamNACK *nack = raxFind(group->pel,buf,sizeof(buf));
        if (nack != raxNotFound) {
            raxRemove(group->pel,buf,sizeof(buf),NULL);
            raxRemove(nack->consumer->pel,buf,sizeof(buf),NULL);
            streamFreeNACK(nack);
            acknowledged++;
            server.dirty++;
        }
```

`streamCreateCG`（`t_stream.c:2495-2507`）负责建组与三件套初始化：`cg->pel = raxNew()`、`cg->consumers = raxNew()`、`cg->last_id = *id`。

**实验佐证**（`05-advanced-7.log`）：XADD 三条 → `XINFO STREAM`：`length 3`、`radix-tree-keys 1`、`radix-tree-nodes 2`（一个 listpack 节点 + 根）；`XREADGROUP c1` 读 2 条 → `XPENDING s g` = `2 1-0 2-0 c1 2`（2 条未确认，归 c1）；`XACK 2-0` 后 → `1 1-0 1-0 c1 1`。

### 3.3 原理与边界

1. **顺序 + 定位**：ID 单调递增且 128 位大端 → rax 字典序 = 时间序；按 ID 定位走 rax（O(log)），顺序扫描走 listpack 前缀；
2. **打包**：字段名复用（master entry）+ ID 差分 + 整数编码（承 03）→ 单条消息的开销远小于独立键；
3. **边界一（PEL 堆积）**：消费者读走但永不 ACK → `cg->pel` 永远保留，内存只增不减（要靠 `XPENDING`/`XCLAIM`/`XAUTOCLAIM` 处理）；
4. **边界二（删除与回放）**：`XDEL` 只是标记删除（tombstone），节点在整段被删后回收；`XADD` 必须大于 `last_id`，时钟回拨时 seq 继续递增而不是新建 ms；
5. **边界三（trim 才控内存）**：MAXLEN/MINID trim 是控制内存的正手段；不 trim 则"全量日志"无限增长。

---

## 四、Bitmap：String 上的位寻址

**它干了什么**：`SETBIT b 100 1` 把第 100 个 bit 置 1，`GETBIT` 读取，`BITCOUNT` 数 1 的个数——用 bit 表示布尔状态（签到表、用户画像位图）。

### 4.1 位寻址与自动扩长

**命令入口**（`bitops.c:536-576`，节选）：

```c
    int dirty;
    if ((o = lookupStringForBitCommand(c,bitoffset,&dirty)) == NULL) return;

    /* Get current values */
    byte = bitoffset >> 3;
    byteval = ((uint8_t*)o->ptr)[byte];
    bit = 7 - (bitoffset & 0x7);
    bitval = byteval & (1 << bit);
    ...
        byteval &= ~(1 << bit);
        byteval |= ((on & 0x1) << bit);
        ((uint8_t*)o->ptr)[byte] = byteval;
```

核心转换就两行：`byte = bitoffset >> 3`、`bit = 7 - (bitoffset & 7)`——**字节内从高位往低位编号（MSB-first）**。

**上限与自动扩长**（`bitops.c:414-441`、`481-498`，节选）：

```c
    /* Limit offset to server.proto_max_bulk_len (512MB in bytes by default) */
    if (loffset < 0 || (!mustObeyClient(c) && (loffset >> 3) >= server.proto_max_bulk_len))
    {
        addReplyError(c,err);
        return C_ERR;
    }
```

```c
robj *lookupStringForBitCommand(client *c, uint64_t maxbit, int *dirty) {
    size_t byte = maxbit >> 3;
    robj *o = lookupKeyWrite(c->db,c->argv[1]);
    ...
    if (o == NULL) {
        o = createObject(OBJ_STRING,sdsnewlen(NULL, byte+1));
        dbAdd(c->db,c->argv[1],o);
        ...
    } else {
        o = dbUnshareStringValue(c->db,c->argv[1],o);
        size_t oldlen = sdslen(o->ptr);
        o->ptr = sdsgrowzero(o->ptr,byte+1);
        ...
```

设置第 N 位时字符串**按需零填充**到 `byte+1` 字节；上限 512MB 字节 → 最大约 2^32 个 bit。注意 int 编码的值会被 `dbUnshareStringValue` 转成 raw 再操作（承 03 §三）。

### 4.2 BITCOUNT：popcount

`BITCOUNT` 数 1 的个数，走 `redisPopcount`（`bitops.c:40-92`，节选）：

```c
    /* Count bits 28 bytes at a time */
    p4 = (uint32_t*)p;
    while(count>=28) {
        uint32_t aux1, aux2, aux3, aux4, aux5, aux6, aux7;
        ...
        aux1 = aux1 - ((aux1 >> 1) & 0x55555555);
        aux1 = (aux1 & 0x33333333) + ((aux1 >> 2) & 0x33333333);
        ...
        bits += ((((aux1 + (aux1 >> 4)) & 0x0F0F0F0F) +
                    ...
                    ((aux7 + (aux7 >> 4)) & 0x0F0F0F0F))* 0x01010101) >> 24;
    }
    /* Count the remaining bytes. */
    p = (unsigned char*)p4;
    while(count--) bits += bitsinbyte[*p++];
```

前导字节用 256 项查表（`bitsinbyte`），主体用 SWAR（`0x55555555`/`0x33333333` 掩码逐级合并）一次算 28 字节——比逐位快一个数量级。

### 4.3 原理与边界

1. **成本模型**：GETBIT/SETBIT O(1)；BITCOUNT O(N/8) 级别（与字节数成正比）；
2. **基础用途**：签到、活跃标记；也是 Bloom 过滤器（bitmap + 多哈希）的原理基座（RedisBloom 是模块，非核心）；
3. **边界（稀疏陷阱，实验数据）**：`SETBIT b 100 1` → `STRLEN 13`（合理）；但 `SETBIT sparse 1000000 1` → `STRLEN 125001`（**为了 1 个 bit 分配 125KB**）。高位稀疏场景要么接受浪费，要么换结构（Hash 记录已用 offset）。

---

## 五、HyperLogLog：12KB 估基数

**它干了什么**：`PFADD hll a b c ...` 往"集合"里加元素，`PFCOUNT hll` 给出**近似**去重个数——1000 个元素只占 2KB（实验），最终固定约 12KB。它不存储元素本身，只存储"基数指纹"。

### 5.1 头部与两种编码

**头部**（`hyperloglog.c:182-205`，节选）：

```c
struct hllhdr {
    char magic[4];      /* "HYLL" */
    uint8_t encoding;   /* HLL_DENSE or HLL_SPARSE. */
    uint8_t notused[3]; /* Reserved for future use, must be zero. */
    uint8_t card[8];    /* Cached cardinality, little endian. */
    uint8_t registers[]; /* Data bytes. */
};
...
#define HLL_P 14 /* The greater is P, the smaller the error. */
#define HLL_Q (64-HLL_P) /* The number of bits of the hash value used for
                            determining the number of leading zeros. */
#define HLL_REGISTERS (1<<HLL_P) /* With P=14, 16384 registers. */
#define HLL_P_MASK (HLL_REGISTERS-1) /* Mask to index register. */
#define HLL_BITS 6 /* Enough to count up to 63 leading zeroes. */
#define HLL_REGISTER_MAX ((1<<HLL_BITS)-1)
#define HLL_HDR_SIZE sizeof(struct hllhdr)
#define HLL_DENSE_SIZE (HLL_HDR_SIZE+((HLL_REGISTERS*HLL_BITS+7)/8))
#define HLL_DENSE 0 /* Dense encoding. */
#define HLL_SPARSE 1 /* Sparse encoding. */
```

**稠密编码**：16384 个寄存器 × 6 bit = 12288 字节（+16B 头 = 12304B）——这就是"12KB"的来源。寄存器是 6bit 的非字节对齐值，读写用跨字节位宏（`hyperloglog.c:339-361`，节选）：

```c
#define HLL_DENSE_GET_REGISTER(target,p,regnum) do { \
    uint8_t *_p = (uint8_t*) p; \
    unsigned long _byte = regnum*HLL_BITS/8; \
    unsigned long _fb = regnum*HLL_BITS&7; \
    unsigned long _fb8 = 8 - _fb; \
    unsigned long b0 = _p[_byte]; \
    unsigned long b1 = _p[_byte+1]; \
    target = ((b0 >> _fb) | (b1 << _fb8)) & HLL_REGISTER_MAX; \
} while(0)
```

**稀疏编码**：大多数寄存器是 0 时，稠密太浪费。稀疏编码用**行程式操作码**表示寄存器序列（`hyperloglog.c:365-389`，节选）：

```c
#define HLL_SPARSE_XZERO_BIT 0x40 /* 01xxxxxx */
#define HLL_SPARSE_VAL_BIT 0x80 /* 1vvvvvxx */
#define HLL_SPARSE_IS_ZERO(p) (((*(p)) & 0xc0) == 0) /* 00xxxxxx */
#define HLL_SPARSE_IS_XZERO(p) (((*(p)) & 0xc0) == HLL_SPARSE_XZERO_BIT)
#define HLL_SPARSE_IS_VAL(p) ((*(p)) & HLL_SPARSE_VAL_BIT)
#define HLL_SPARSE_ZERO_LEN(p) (((*(p)) & 0x3f)+1)
#define HLL_SPARSE_XZERO_LEN(p) (((((*(p)) & 0x3f) << 8) | (*((p)+1)))+1)
#define HLL_SPARSE_VAL_VALUE(p) ((((*(p)) >> 2) & 0x1f)+1)
#define HLL_SPARSE_VAL_LEN(p) (((*(p)) & 0x3)+1)
```

含义：`ZERO` = 1~64 个 0 寄存器；`XZERO` = 1~16384 个 0 寄存器；`VAL` = 连续 1~4 个值为 v（1~32）的寄存器。全 0 的 HLL 只要几个字节。

### 5.2 写入路径：hash → 前导零 → 寄存器

**先哈希**（`hyperloglog.c:452-480`，节选）：

```c
int hllPatLen(unsigned char *ele, size_t elesize, long *regp) {
    ...
    hash = MurmurHash64A(ele,elesize,0xadc83b19ULL);
    index = hash & HLL_P_MASK; /* Register index. */
    hash >>= HLL_P; /* Remove bits used to address the register. */
    hash |= ((uint64_t)1<<HLL_Q); /* Make sure the loop terminates ... */
    bit = 1;
    count = 1; /* Initialized to 1 since we count the "00000...1" pattern. */
    while((hash & bit) == 0) {
        count++;
        bit <<= 1;
    }
    *regp = (int) index;
    return count;
}
```

低 14 位选寄存器（16384 个之一），其余 50 位数"前导零 + 1"的长度（1~51）。**寄存器只记录见过的最大值**（`hllDenseSet`，`hyperloglog.c:494-504`）：

```c
int hllDenseSet(uint8_t *registers, long index, uint8_t count) {
    uint8_t oldcount;

    HLL_DENSE_GET_REGISTER(oldcount,registers,index);
    if (count > oldcount) {
        HLL_DENSE_SET_REGISTER(registers,index,count);
        return 1;
    } else {
        return 0;
    }
}
```

稀疏版 `hllSparseSet`（`hyperloglog.c:667` 起）在写入长度超限时会**提升为稠密**（`hyperloglog.c:855`）：`sdslen(o->ptr) + deltalen > server.hll_sparse_max_bytes` → `goto promote`；阈值默认 **3000 字节**（`config.c:3227`）。

### 5.3 估算：调和平均

`PFCOUNT` 走 `hllCount`（`hyperloglog.c:1050-1085`，节选）：

```c
uint64_t hllCount(struct hllhdr *hdr, int *invalid) {
    double m = HLL_REGISTERS;
    double E;
    int j;
    int reghisto[64] = {0};

    /* Compute register histogram */
    if (hdr->encoding == HLL_DENSE) {
        hllDenseRegHisto(hdr->registers,reghisto);
    } else if (hdr->encoding == HLL_SPARSE) {
        hllSparseRegHisto(hdr->registers,
                         sdslen((sds)hdr)-HLL_HDR_SIZE,invalid,reghisto);
    } ...
    /* Estimate cardinality from register histogram. See:
     * "New cardinality estimation algorithms for HyperLogLog sketches"
     * Otmar Ertl, arXiv:1702.01284 */
    double z = m * hllTau((m-reghisto[HLL_Q+1])/(double)m);
    for (j = HLL_Q; j >= 1; --j) {
        z += reghisto[j];
        z *= 0.5;
    }
    z += m * hllSigma(reghisto[0]/(double)m);
    E = llroundl(HLL_ALPHA_INF*m*m/z);

    return (uint64_t) E;
}
```

**原理**：每个元素哈希后看"第一个 1 出现在第几位"——这等价于一次伯努利试验，插入 n 个元素后，寄存器最大值与 log2(n) 成正比；用 16384 个寄存器的**调和平均**降低方差，得到基数估计。常数 `HLL_ALPHA_INF = 0.7213475`（= 0.5/ln2，`hyperloglog.c:389`），Redis 7 用的是 Ertl 的改进估计器（`hllSigma`/`hllTau`，`hyperloglog.c:1008-1037`）而不是经典 alpha 修正。

**误差与内存**：标准误差 ≈ `1.04/√16384 ≈ 0.81%`；实测 PFCOUNT 1000 → **1001**（0.1%），2 万 → **19891**（0.55%）；内存从稀疏 2232 字节（1000 元素）→ 稠密 12355 字节（2 万元素后），此后**不再随元素数增长**。跨键聚合 `PFMERGE` 用"寄存器取 max"实现（`hllMerge`，`hyperloglog.c:1105` 起）。

### 5.4 原理与边界

1. **只估个数**：元素不存储，无法判断"某元素是否在集合里"、无法枚举——PFADD 的返回值也不代表"是否新增"；
2. **不可逆**：哈希后信息已丢失；PFCOUNT 单键 12KB 上限；稀疏→稠密单向；
3. **合并免费**：PFMERGE 是 max 归并，多键估算等于合并后估算——适合 UV 这类"并集去重计数"；
4. **边界**：基数很小时稀疏编码本身就很小；但一旦转稠密，**即使清空**也回不去稀疏（对应 3000 字节阈值的单向性）。

---

## 六、Geo：ZSet + geohash 52 位

**它干了什么**：`GEOADD geo 116.397128 39.916527 beijing` 存一个地点；`GEODIST` 算两地距离；`GEOSEARCH` 找某个坐标半径内的点——实测 `ZSCORE geo beijing = 4069885548668386`（一个 52 位整数）。

### 6.1 编码：把坐标交织成 52 位

**位交织**（`geohash.c:52-77`）：

```c
static inline uint64_t interleave64(uint32_t xlo, uint32_t ylo) {
    static const uint64_t B[] = {0x5555555555555555ULL, 0x3333333333333333ULL,
                                 0x0F0F0F0F0F0F0F0FULL, 0x00FF00FF00FF00FFULL,
                                 0x0000FFFF0000FFFFULL};
    static const unsigned int S[] = {1, 2, 4, 8, 16};

    uint64_t x = xlo;
    uint64_t y = ylo;

    x = (x | (x << S[4])) & B[4];
    ...
    y = (y | (y << S[0])) & B[0];

    return x | (y << 1);
}
```

**编码过程**（`geohash.c:121-151`，节选）：

```c
    double lat_offset =
        (latitude - lat_range->min) / (lat_range->max - lat_range->min);
    double long_offset =
        (longitude - long_range->min) / (long_range->max - long_range->min);

    /* convert to fixed point based on the step size */
    lat_offset *= (1ULL << step);
    long_offset *= (1ULL << step);
    hash->bits = interleave64(lat_offset, long_offset);
```

维度范围归一化 → 各取 26 位整数 → **bit 交织**（lat 占偶数位、long 占奇数位）→ 52 位整数（`GEO_STEP_MAX 26`，`geohash.h:46`）。**关键性质：地理上相邻的点，geohash 前缀相同**——于是"找附近的点"可以转化为"按分数范围查 zset"。

### 6.2 GEOADD = ZADD

`geoaddCommand`（`geo.c:485-502`，节选）把经纬度编码成 52 位整数，然后**构造一个 ZADD 命令直接调用**：

```c
        /* Turn the coordinates into the score of the element. */
        GeoHashBits hash;
        geohashEncodeWGS84(xy[0], xy[1], GEO_STEP_MAX, &hash);
        GeoHashFix52Bits bits = geohashAlign52Bits(hash);
        robj *score = createStringObjectFromLongLongWithSds(bits);
        ...
    /* Finally call ZADD that will do the work for us. */
    replaceClientCommandVector(c,argc,argv);
    zaddCommand(c);
```

`geohashAlign52Bits` 只是把 bits 左移到 52 位对齐（`geohash_helper.c:213-217`）。所以 Geo 的底层就是**一个普通 ZSet**：`ZRANGE`/`ZSCORE`/`ZREM` 对 Geo 数据全部可用（实验里 `ZSCORE` 直接看到 52 位整数）。

### 6.3 查询：9 格邻域 + 距离过滤

半径查询分两步（`georadiusGeneric` 主链）：

**第一步：算出覆盖搜索圆的 geohash 格子的分数区间**（`geohash_helper.c:121-211`，节选）：

```c
    steps = geohashEstimateStepsByRadius(radius_meters,latitude);

    geohashGetCoordRange(&long_range,&lat_range);
    geohashEncode(&long_range,&lat_range,longitude,latitude,steps,&hash);
    geohashNeighbors(&hash,&neighbors);
    geohashDecode(long_range,lat_range,hash,&area);
    ...
        if (area.longitude.min < min_lon) {
            GZERO(neighbors.west);
            GZERO(neighbors.south_west);
            GZERO(neighbors.north_west);
        }
```

半径 → 估算 geohash 精度（steps）→ 中心格 + 8 邻格（`geohashNeighbors`，`geohash.c:266`）→ 越界邻格置零。每个格子解码为经纬度范围，再编码回 52 位分数区间（`geo.c:349-351`），得到 **9 个 zset 分数区间**。

**第二步：按区间扫 zset + 精确距离过滤**（`geo.c:261-305`，节选）：

```c
    zrangespec range = { .min = min, .max = max, .minex = 0, .maxex = 1 };
    ...
        if ((eptr = zzlFirstInRange(zl, &range)) == NULL) {
            /* Nothing exists starting at our min.  No results. */
            return 0;
        }
        ...
            score = zzlGetScore(sptr);
            /* If we fell out of range, break. */
            if (!zslValueLteMax(score, &range))
                break;
            vstr = lpGetValue(eptr, &vlen, &vlong);
            if (geoWithinShape(shape, score, xy, &distance) == C_OK) {
                /* Append the new element. */
                char *member = (vstr == NULL) ? sdsfromlonglong(vlong) : sdsnewlen(vstr, vlen);
                geoArrayAppend(ga, xy, distance, score, member);
            }
```

区间扫描可能带回"格子内但半径外"的点，所以每个候选点再用 `geoWithinShape`（`geo.c:232`）做**精确距离判断**（球面距离 + 半径/矩形比较）→ 假阳性被过滤。

### 6.4 原理与边界

1. **一维化**：geohash 把二维邻近转化为前缀/范围问题，让 Geo 完全复用 zset 的排序结构（无需新索引）；
2. **9 格的原因**：搜索圆可能跨格，只查中心格会漏点；9 格必然覆盖圆（含边角），代价是"格内但圈外"的假阳性 → 用距离过滤兜底；
3. **精度**：坐标编码有量化误差，`GEOPOS` 返回的是解码后的近似坐标（实验：`116.39712899923324585`）；
4. **实验佐证**：北京/上海 `GEODIST ≈ 1069.23 km`；`GEOSEARCH 100km` 只返回 `beijing`。

---

## 七、原理总结

1. **四个结构都是"在已有点上构建"**：Stream 是唯一的新值类型（内部 rax+listpack）；Bitmap/HLL 是 String 的位/字节用法；Geo 是 ZSet 的一个 score 约定（§二）；
2. **Stream = 打包日志 + 双层 PEL**：消息按 ID 进 rax、多条打包进 listpack（字段名复用 + ID 差分）；消费确认靠组/消费者两层 rax 共享 NACK（§三）；
3. **Bitmap 的位寻址是"MSB-first 的 byte/bit 换算"**，成本陷阱在稀疏高位（按最大 offset 分配）（§四）；
4. **HLL 用 6bit 寄存器 + 调和平均换取固定 12KB**，稀疏/稠密两种编码覆盖"小数据省内存、大数据有上限"，只估基数不可逆（§五）；
5. **Geo 用 geohash 把二维查询一维化**，9 格邻域保证覆盖、距离过滤去假阳性，本质仍是 zset 范围查询（§六）。

---

## 附录 A：面试速答（附带）

> 高级结构四个，各自一句话：Stream 是可 ACK 的日志，底层 rax（ID→listpack 节点），消息在一个节点里打包（master entry 复用字段名、ID 差分编码），消费组是 rax（name→CG），每个组有 PEL（rax：ID→NACK）和消费者 rax，投递时组和消费者两层 PEL 同时写入共享的 NACK，XACK 两层同时删除——PEL 不确认就会堆积，这是运维要盯的点。Bitmap 就是 String 的位操作：byte=offset>>3、字节内 7-(offset&7)，设置高位会零填充撑大字符串（一个 bit 撑 125KB 的实测），BITCOUNT 用查表和 SWAR 加速。HyperLogLog 12KB 估基数：64 位 hash 的低 14 位选 16384 个寄存器、其余数前导零长度、寄存器存最大值，调和平均估算，标准误差约 0.81%；小数据用行程式稀疏编码（ZERO/XZERO/VAL），超过 3000 字节转稠密、不可逆；只能估个数不能判成员，PFMERGE 用寄存器 max 合并。Geo 就是 ZSet：经纬度各 26 位交织成 52 位整数当 score，GEOADD 内部构造 ZADD 执行；GEOSEARCH 按半径算 geohash 精度、取中心格+8 邻格共 9 个分数区间扫 zset，再用球面距离过滤假阳性。

## 附录 B：实验与复现

```bash
cd redis-lab && docker compose up -d
bash scripts/05-advanced.sh redis-lab-7     # 输出见 evidence/05-advanced-7.log
```

| 观测点 | 结果 |
|---|---|
| Stream 存储 | `XLEN=3`；`radix-tree-keys=1`、`radix-tree-nodes=2`（rax 根 + 1 个 listpack 节点） |
| PEL 生命周期 | READGROUP 读 2 条 → `XPENDING = 2 1-0 2-0 c1 2`；`XACK 2-0` 后 → `1 1-0 1-0 c1 1` |
| Bitmap 寻址/扩长 | `SETBIT b 100 1` → `STRLEN=13`；`SETBIT sparse 1000000 1` → `STRLEN=125001` |
| HLL 稀疏期 | PFADD 1000 → `PFCOUNT=1001`、`2232` 字节、`PFDEBUG ENCODING=sparse` |
| HLL 稠密期 | 累计 2 万 → `PFCOUNT=19891`、`12355` 字节、`PFDEBUG ENCODING=dense` |
| Geo score | `ZSCORE beijing=4069885548668386`（52 位）；`GEODIST=1069.2292 km`；`GEOSEARCH 100km → beijing` |

## 附录 C：源码坐标系（本文引用）

| 内容 | 坐标 |
|---|---|
| `streamID` / `stream` 结构 | `stream.h:11-14`、`stream.h:16-24` |
| `streamCG` / `streamConsumer` / `streamNACK` | `stream.h:55-73`、`stream.h:76-89`、`stream.h:92-97` |
| Stream 节点尺寸常量 | `t_stream.c:54`（`STREAM_LISTPACK_MAX_SIZE`）、`t_stream.c:48`（预分配） |
| 追加消息 `streamAppendItem` | `t_stream.c:427-670`（ID 生成 `429-461`、master entry 注释 `497-527`、节点切换 `532-553`、entry 追加 `645-659`、收尾 `661-670`） |
| 投递写入 PEL | `t_stream.c:1749-1797`（`streamReplyWithRange` `1689` 起） |
| NACK / CG 创建 | `t_stream.c:2466-2472`、`t_stream.c:2495-2507` |
| XACK 删除 PEL | `t_stream.c:2865-2875`（`xackCommand` `2833` 起） |
| Stream 默认参数 | `config.c:3210`（entries=100）、`config.c:3225`（bytes=4096） |
| 位偏移解析（512MB 上限） | `bitops.c:414-441` |
| 设置位 / 读取位 | `bitops.c:532-577`、`bitops.c:579-603` |
| 按需扩长 `lookupStringForBitCommand` | `bitops.c:481-498` |
| 位计数 `redisPopcount` | `bitops.c:40-92`（BITCOUNT 调用 `bitops.c:862`） |
| `hllhdr` 与常量 | `hyperloglog.c:182-205` |
| 稠密 6bit 寄存器宏 | `hyperloglog.c:339-361` |
| 稀疏操作码宏 | `hyperloglog.c:365-389`（`HLL_ALPHA_INF` `389`） |
| 哈希与前导零 `hllPatLen` | `hyperloglog.c:452-480` |
| 寄存器更新 `hllDenseSet` | `hyperloglog.c:494-504` |
| 稀疏写入与提升 | `hyperloglog.c:667` 起（promote 判定 `855`）；`config.c:3227`（3000B） |
| 估算 `hllCount` / `hllSigma` / `hllTau` | `hyperloglog.c:1050-1085`、`1008-1020`、`1025-1037` |
| 位交织 `interleave64` | `geohash.c:52-77` |
| 编码 `geohashEncode` | `geohash.c:121-151`；`GEO_STEP_MAX` `geohash.h:46` |
| 52 位对齐 `geohashAlign52Bits` | `geohash_helper.c:213-217` |
| GEOADD = ZADD | `geo.c:485-502`（`geoaddCommand` `445` 起） |
| 9 格邻域 | `geohash_helper.c:121-211`（`geohashCalculateAreasByShapeWGS84`）；`geohashNeighbors` `geohash.c:266` |
| 范围扫描 + 距离过滤 | `geo.c:261-323`（`geoGetPointsInRange`）；`geoWithinShape` `geo.c:232` |
