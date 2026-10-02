# 02 键空间与对象模型：dict、robj 与编码切换（源码解析）

> 定位：**源码解析与原理**文章——引用核心源码（抓主链，不逐行）；面试速答见附录 A。
> 源码基线：Redis 7.2.11；实验证据：`redis-lab/evidence/`（编码切换、内存账、慢操作）。

---

## 一、源码地图

| 文件 | 职责 | 本文引用点 |
|---|---|---|
| `server.h` | 服务器/数据库/对象/跳表结构体 | `redisServer.db`、`redisDb`、`redisObject`、`zset` |
| `dict.h` / `dict.c` | 哈希表：查找、插入、扩容、渐进式 rehash | `dict`、`dictEntry`、`dictFind`、`dictInsertAtPosition`、`dictRehash` |
| `object.c` | 对象创建、整数化编码、共享对象 | `createStringObject`、`createEmbeddedStringObject`、`tryObjectEncoding` |
| `t_hash.c` / `t_zset.c` / `t_set.c` / `t_list.c` | 各类型的编码转换 | `hashTypeTryConversion`、`zsetAdd`、`setTypeMaybeConvert`、`listTypeTryConversionRaw` |
| `db.c` | 键空间命令实现 | `keysCommand`、`scanGenericCommand` |

---

## 二、键空间的四层结构

**第一层：服务器持有数据库数组**（`server.h:1057`、`server.h:1738`）：

```c
redisDb *db;          /* 数据库数组 */
int dbnum;            /* 数据库数量（配置 databases，默认 16） */
```

**第二层：每个数据库是一组独立字典**（`server.h:968-982`，节选）：

```c
typedef struct redisDb {
    dict *dict;                 /* The keyspace for this DB — 键空间 */
    dict *expires;              /* Timeout of keys with a timeout set */
    dict *blocking_keys;        /* Keys with clients waiting for data (BLPOP) */
    dict *watched_keys;         /* WATCHED keys for MULTI/EXEC CAS */
    int id;                     /* Database ID */
    ...
} redisDb;
```

**第三层：dict 是"两张哈希表 + 迁移游标"**（`dict.h:84-94`，节选）：

```c
struct dict {
    dictType *type;
    dictEntry **ht_table[2];
    unsigned long ht_used[2];
    long rehashidx;             /* rehashing not in progress if rehashidx == -1 */
    int16_t pauserehash;
    signed char ht_size_exp[2]; /* 表容量 = 1<<exp */
    ...
};
```

**第四层：桶内条目**（`dict.c:63-71`）：

```c
struct dictEntry {
    void *key;
    union { void *val; uint64_t u64; int64_t s64; double d; } v;
    struct dictEntry *next;     /* 同桶冲突链 */
};
```

**值对象**（`server.h:900-908`）：

```c
struct redisObject {
    unsigned type:4;
    unsigned encoding:4;
    unsigned lru:LRU_BITS;
    int refcount;
    void *ptr;
};
```

**对象图走查**：`SET user:1 张三` 在内存中形成：

```
server.db[0]
 └─ dict.dict（键空间）
     └─ ht_table[0][idx] → dictEntry
         ├─ key → robj(type=String, encoding=embstr, ptr→sdshdr8 "user:1")
         └─ v.val → robj(type=String, encoding=embstr, ptr→sdshdr8 "张三")
```

---

## 三、查找与哈希（dict.c）

**哈希对象是键的内容**（`server.c:310-312`）：

```c
uint64_t dictSdsHash(const void *key) {
    return dictGenHashFunction((unsigned char*)key, sdslen((char*)key));
}
```

```c
/* dict.c:109-111 */
uint64_t dictGenHashFunction(const void *key, size_t len) {
    return siphash(key,len,dict_hash_function_seed);
}
```

种子来自进程启动时的 16 字节随机数（`server.c:7113-7115`：`getRandomBytes` + `dictSetHashFunctionSeed`），用于抵抗 HashDoS。

**查找**（`dict.c:668-686`，节选）：

```c
dictEntry *dictFind(dict *d, const void *key) {
    ...
    if (dictIsRehashing(d)) _dictRehashStep(d);
    h = dictHashKey(d, key);
    for (table = 0; table <= 1; table++) {
        idx = h & DICTHT_SIZE_MASK(d->ht_size_exp[table]);
        he = d->ht_table[table][idx];
        while(he) {
            void *he_key = dictGetKey(he);
            if (key == he_key || dictCompareKeys(d, key, he_key))
                return he;
            he = dictGetNext(he);
        }
        if (!dictIsRehashing(d)) return NULL;
    }
    return NULL;
}
```

要点：

1. 桶下标 = `h & (size-1)`（容量是 2 的幂，源码 `dict.h:82`）；
2. 桶内先做**指针相等**快速路径，再按内容比较（键空间用 `dictSdsKeyCompare`，`server.c:464-475` 的 `dbDictType` 声明）；
3. **每次查找顺带推进 rehash 一步**（`_dictRehashStep`）；
4. rehash 期间先查旧表、未命中再查新表。

**两层哈希的区分**（原理）：节点内桶位用 siphash+随机种子（只需进程内一致）；集群的键→槽位用 `crc16(键或 hash tag) & 16383`（`cluster.c:1375-1395`，必须跨节点稳定）。"hash tag 同槽"与"随机种子"是两层独立机制。

**插入**（`dict.c:467-472`、`494-505`，节选）：

```c
dictEntry *dictInsertAtPosition(dict *d, void *key, void *position) {
    ...
    int htidx = dictIsRehashing(d) ? 1 : 0;   /* rehash 期间写入新表 */
    assert(bucket >= &d->ht_table[htidx][0] && ...);
    ...
        entry->key = key;
        entry->next = *bucket;                /* 新元素头插 */
    *bucket = entry;
    d->ht_used[htidx]++;
    return entry;
}
```

---

## 四、扩容与渐进式 rehash（dict.c）

**触发与容量**（`dict.c:1405-1426`，节选）：

```c
static int _dictExpandIfNeeded(dict *d) {
    if (dictIsRehashing(d)) return DICT_OK;
    if (DICTHT_SIZE(d->ht_size_exp[0]) == 0) return dictExpand(d, DICT_HT_INITIAL_SIZE);
    if ((dict_can_resize == DICT_RESIZE_ENABLE &&
         d->ht_used[0] >= DICTHT_SIZE(d->ht_size_exp[0])) ||
        (dict_can_resize != DICT_RESIZE_FORBID &&
         d->ht_used[0] / DICTHT_SIZE(d->ht_size_exp[0]) > dict_force_resize_ratio))
    {
        ...
        return dictExpand(d, d->ht_used[0] + 1);
    }
    return DICT_OK;
}
```

要点：触发条件为"元素数 ≥ 桶数"（负载因子 ≥1），或 resize 被禁止（如子进程写时复制阶段）时负载因子 > `dict_force_resize_ratio`（=5，`dict.c:59`）强制扩容；**新容量 = 不小于「元素数+1」的最小 2 的幂**（`_dictNextExp`）；收缩条件为元素数/桶数 < 10% 且桶数大于初始值（`htNeedsResize`，`server.c:593-600`），由 serverCron 周期触发。

**每步迁移 1 桶**（`dict.c:414-415`、`295-348` 节选）：

```c
static void _dictRehashStep(dict *d) {
    if (d->pauserehash == 0) dictRehash(d,1);
}
```

```c
int dictRehash(dict *d, int n) {
    int empty_visits = n*10;
    ...
    while(n-- && d->ht_used[0] != 0) {
        ...
        while(d->ht_table[0][d->rehashidx] == NULL) {
            d->rehashidx++;
            if (--empty_visits == 0) return 1;
        }
        de = d->ht_table[0][d->rehashidx];
        /* Move all the keys in this bucket from the old to the new hash HT */
        while(de) {
            uint64_t h;
            nextde = dictGetNext(de);
            void *key = dictGetKey(de);
            if (d->ht_size_exp[1] > d->ht_size_exp[0]) {
                h = dictHashKey(d, key) & DICTHT_SIZE_MASK(d->ht_size_exp[1]);
            } else {
                h = d->rehashidx & DICTHT_SIZE_MASK(d->ht_size_exp[1]);
            }
            ...
```

**空闲补进度**（`dict.c:393-402`，节选）：`dictRehashMilliseconds(d, 1)` 在 serverCron 中以 1ms 时间片持续迁移（`server.c:621`）。

**走查**：设 `rehashidx = 5`，旧表 5 号桶挂着链 `[k1 → k2]`。本次操作触发 `dictRehash(d,1)`：取 `k1`、`k2`，分别用新表掩码计算新桶下标（扩容场景重新哈希；收缩场景直接用旧桶下标掩码），头插入新表对应桶链；`ht_used[0]--`、`ht_used[1]++`、`rehashidx++`；若 5 号桶为空则跳过并按 `empty_visits` 限制空桶访问数量，避免长时间空转。

**原理与代价**：把一次 O(N) 迁移摊成"每次操作 1 桶"；代价是 rehash 期间内存接近双倍、查找最坏两次定位（第三节）。这是单线程模型下"不让任何一次操作过长"的直接体现（承 01）。

---

## 五、对象系统与编码（object.c / server.h）

**短字符串一次分配**（`object.c:92-104`，节选）：

```c
robj *createEmbeddedStringObject(const char *ptr, size_t len) {
    robj *o = zmalloc(sizeof(robj)+sizeof(struct sdshdr8)+len+1);
    struct sdshdr8 *sh = (void*)(o+1);
    o->type = OBJ_STRING;
    o->encoding = OBJ_ENCODING_EMBSTR;
    o->ptr = sh+1;
    ...
    sh->len = len;
    sh->alloc = len;
    sh->flags = SDS_TYPE_8;
```

**编码选择**（`object.c:123-128`）：

```c
robj *createStringObject(const char *ptr, size_t len) {
    if (len <= OBJ_ENCODING_EMBSTR_SIZE_LIMIT)   /* 44 */
        return createEmbeddedStringObject(ptr,len);
    else
        return createRawStringObject(ptr,len);
}
```

**整数化与共享对象**（`object.c:649-665`，节选；共享分支续至 682）：

```c
    if (!sdsEncodedObject(o)) return o;
    if (o->refcount > 1) return o;               /* 共享对象不重编码 */
    len = sdslen(s);
    if (len <= 20 && string2l(s,len,&value)) {
        /* Try to use a shared object... */
```

共享整数 `0..9999` 在启动时创建（`server.c:1967-1972`）：

```c
    for (j = 0; j < OBJ_SHARED_INTEGERS; j++) {
        shared.integers[j] = makeObjectShared(createObject(OBJ_STRING,(void*)(long)j));
        ...
        shared.integers[j]->encoding = OBJ_ENCODING_INT;
    }
```

**引用计数是普通整型**（`object.c:380-390`）：`o->refcount++`——非原子操作，安全性由单线程执行模型提供（01 第四节）。

**走查**：`SET counter 100` → 值对象 `encoding=int`。整数化的两条路径（`object.c:660-682`）：命中共享整数（`0..9999`）且**未启用 maxmemory**（源码注释：启用 maxmemory 时每个对象需要私有 LRU 字段，故避免共享）→ 直接返回共享对象；否则就地编码（RAW 改 `encoding/ptr`；EMBSTR 重建为整数值对象）；`SET name 张三` → `encoding=embstr`（robj 与 sdshdr8 一次分配）；值长 45 字节 → `encoding=raw`。

---

## 六、编码切换：转换发生在"触发阈值的那次写"（各类型源码）

**Hash**（`t_hash.c:40-52`，节选）：

```c
void hashTypeTryConversion(robj *o, robj **argv, int start, int end) {
    ...
    if (o->encoding != OBJ_ENCODING_LISTPACK) return;
    size_t new_fields = (end - start + 1) / 2;
    if (new_fields > server.hash_max_listpack_entries) {
        hashTypeConvert(o, OBJ_ENCODING_HT);
        dictExpand(o->ptr, new_fields);
        return;
    }
    for (i = start; i <= end; i++) {                    /* 单值超限也触发转换 */
        if (!sdsEncodedObject(argv[i])) continue;
        size_t len = sdslen(argv[i]->ptr);
        if (len > server.hash_max_listpack_value) {
            hashTypeConvert(o, OBJ_ENCODING_HT);
            return;
        }
        sum += len;
    }
    if (!lpSafeToAdd(o->ptr, sum))
```

**ZSet**（`t_zset.c:1415-1421`，节选）：

```c
            if (zzlLength(zobj->ptr)+1 > server.zset_max_listpack_entries ||
                sdslen(ele) > server.zset_max_listpack_value ||
                !lpSafeToAdd(zobj->ptr, sdslen(ele)))
            {
                zsetConvertAndExpand(zobj, OBJ_ENCODING_SKIPLIST, zsetLength(zobj) + 1);
            }
```

**Set**（`t_set.c:61-68` 与 `78-82`，节选）：

```c
void setTypeMaybeConvert(robj *set, size_t size_hint) {
    if ((set->encoding == OBJ_ENCODING_LISTPACK && size_hint > server.set_max_listpack_entries)
        || (set->encoding == OBJ_ENCODING_INTSET && size_hint > server.set_max_intset_entries))
    {
        setTypeConvertAndExpand(set, OBJ_ENCODING_HT, size_hint, 1);
    }
}
...
static void maybeConvertIntset(robj *subject) {
    if (intsetLen(subject->ptr) > intsetMaxEntries())
        setTypeConvert(subject,OBJ_ENCODING_HT);
}
```

**List**（`t_list.c:132-145`，节选）：

```c
static void listTypeTryConversionRaw(robj *o, list_conv_type lct, ...) {
    if (o->encoding == OBJ_ENCODING_QUICKLIST) {
        if (lct == LIST_CONV_GROWING) return;
        listTypeTryConvertQuicklist(o, lct == LIST_CONV_SHRINKING, fn, data);
    } else if (o->encoding == OBJ_ENCODING_LISTPACK) {
        if (lct == LIST_CONV_SHRINKING) return;
        listTypeTryConvertListpack(o, argv, start, end, fn, data);
    } else {
        serverPanic("Unknown list encoding");
    }
}
```

**走查**（以 Hash 为例）：写入第 513 个字段（或单值 >64 字节）时，`hashTypeTryConversion` 命中条件 → `hashTypeConvert(o, HT)` 一次性把 listpack 全部字段搬进新 dict（`dictExpand` 预分配目标容量）→ 此后点操作走哈希。7.2 实测阈值与切换点：Hash 512 字段/64 字节值；Set 512 整数（intset）/128 非整数（listpack）；ZSet 128 成员/64 字节值；List 单节点 8KB（`list-max-listpack-size=-2`）。

**原理**：紧凑编码的收益是内存（无指针、少分配），代价是元素级操作 O(N)；阈值就是"内存收益"与"操作成本"的均衡点；转换是一次性 O(N)，发生在触发写入的那次调用内（慢命令候选之一）。

---

## 七、慢命令的源码形态（命令实现）

**HGETALL**（`t_hash.c:831-845`，节选）——单次调用内完成全量遍历与回复构建：

```c
void genericHgetallCommand(client *c, int flags) {
    ...
    if ((o = lookupKeyReadOrReply(c,c->argv[1],emptyResp))
        == NULL || checkType(c,o,OBJ_HASH)) return;
    length = hashTypeLength(o);
    if (flags & OBJ_HASH_KEY && flags & OBJ_HASH_VALUE) {
        addReplyMapLen(c, length);
    ...
```

**KEYS**（`db.c:785-800`，节选）——全键空间迭代：

```c
void keysCommand(client *c) {
    dictIterator *di;
    dictEntry *de;
    ...
    di = dictGetSafeIterator(c->db->dict);
    allkeys = (pattern[0] == '*' && plen == 1);
    while((de = dictNext(di)) != NULL) {
        sds key = dictGetKey(de);
        if (allkeys || stringmatchlen(pattern,plen,key,sdslen(key),0)) {
```

**SCAN/HSCAN**（`db.c:955-975`、`1074-1075`，节选）——游标式有界轮次：

```c
void scanGenericCommand(client *c, robj *o, unsigned long cursor) {
    ...
        do {
            cursor = dictScan(ht, cursor, scanCallback, &data);
        } while (cursor && maxiterations-- && data.sampled < count);
```

`dictScan`（函数定义 `dict.c:1284`；设计说明注释见 `dict.c:1200`）是**无状态游标遍历**：每轮从游标位置扫描有限桶，返回新游标；rehash 期间保证不遗漏/不重复的遍历语义。

**实验佐证**（30 万规模，基线 PING 91ms）：HGETALL 期间 342ms vs HSCAN 分 300 轮 82ms；KEYS 200ms vs SCAN 90ms；100 字段内存账：String×100 = 5,584B vs Hash×1 = 1,036B（5.4×）。

**原理**：O(N) 命令的"慢"不在算法本身，而在**执行位置**——命令实现在事件循环线程内联完成全部工作（承 01 第四、六节）；游标命令把 O(N) 摊成多轮 O(有界)。内存侧：紧凑编码省下的是每个键/条目的固定开销（robj、dictEntry、键 SDS 等）。

---

## 八、原理总结

1. **四层结构**：server → db[]（每个库一组字典）→ dict（两张表 + 游标）→ dictEntry → robj；键空间、过期、阻塞、监视各自独立（第二节）；
2. **内容哈希 + 链地址**：`siphash(键字节, 进程随机种子)`，桶下标位掩码；集群层另有 CRC16 槽位（第三节）；
3. **渐进式 rehash**：每次操作 1 桶、cron 1ms 补进度；代价是双表与两次查找（第四节）；
4. **对象系统**：type 与 encoding 解耦是全部"自适应"的前提；引用计数非原子、共享整数复用（第五节）；
5. **编码切换**：阈值即内存与操作的均衡点；转换是触发写入内的一次性 O(N)（第六节）；
6. **慢命令**：O(N) 在事件循环线程内联完成；遍历用游标命令（第七节）。

---

## 附录 A：面试速答（附带）

> 键空间分层：server 里是 redisDb 数组（默认 16 库），每库一组字典——键空间、过期、阻塞、监视各一个；核心的 dict 是"两张哈希表 + rehashidx"，用渐进式 rehash 把搬家摊成每次操作一桶，查找在 rehash 期先旧表后新表、新增只写新表，哈希是带进程随机种子的 siphash，防 HashDoS；集群的槽位是另一层，用 CRC16 取 16384 模。表里每条是 dictEntry：键、值、冲突链；值是 robj，type 与 encoding 解耦——String 是 int/embstr/raw，Hash 小规模 listpack、超 512 字段或 64 字节值切哈希表，ZSet 超 128 成员切跳表+字典。编码切换发生在触发阈值的那次写，一次 O(N) 转换；内存上同一对象字段放一个 Hash，实测 100 字段 5,584 字节 vs 1,036 字节。慢命令比如 HGETALL 是命令实现里一次全量遍历加回复构建，30 万字段时其他客户端 PING 从 91ms 涨到 342ms，换 HSCAN 分轮是 82ms。

## 附录 B：实验与复现

```bash
cd redis-lab && docker compose up -d
bash scripts/03-encoding.sh redis-lab-7                 # 编码切换 + 内存账
bash scripts/04-slow-ops.sh redis-lab-7 300000 300000   # 慢操作对照（结束自动清空）
```
证据：`evidence/03-encoding-7.log`、`04-slow-ops-7.log`（说明：PING 含 `docker exec` 开销，看增量）。

## 附录 C：源码坐标系（本文引用）

| 内容 | 坐标 |
|---|---|
| 数据库数组/数量 | `server.h:1057`、`server.h:1738` |
| redisDb（键空间/过期/阻塞/监视字典） | `server.h:968-982` |
| dict 结构（两表/游标/容量指数） | `dict.h:84-94` |
| 桶下标掩码 | `dict.h:82` |
| dictEntry | `dict.c:63-71` |
| robj | `server.h:900-908` |
| 键空间哈希函数声明 | `server.c:310-312`（`dictSdsHash`）、`dict.c:109-111`（`dictGenHashFunction`） |
| 哈希种子 | `server.c:7113-7115` |
| dbDictType（哈希/比较函数） | `server.c:464-475` |
| dictFind（查找/双表/顺带 rehash） | `dict.c:668-686` |
| dictInsertAtPosition（写新表/头插） | `dict.c:467-472`、`dict.c:494-505` |
| 扩容触发与容量 | `dict.c:1405-1426`、`dict.c:59`（force ratio=5） |
| 迁移 1 桶 / cron 补进度 | `dict.c:414-415`、`dict.c:295-348`、`dict.c:393-402`、`server.c:621` |
| 收缩条件 | `server.c:593-600` |
| 集群槽位 CRC16 | `cluster.c:1375-1395` |
| embstr 一次分配 / 阈值 | `object.c:92-104`、`object.c:123-128` |
| 整数化与共享 | `object.c:644-665`、`server.c:1967-1972`、`server.h:121` |
| 引用计数非原子 | `object.c:380-390` |
| 编码转换（Hash/ZSet/Set/List） | `t_hash.c:40-52`、`t_zset.c:1415-1421`、`t_set.c:61-68`、`t_set.c:78-82`、`t_list.c:132-145` |
| 慢命令实现（HGETALL/KEYS/SCAN） | `t_hash.c:831-845`、`db.c:785-800`、`db.c:955-975`、`db.c:1074-1075` |
