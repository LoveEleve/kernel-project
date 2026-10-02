# 03 底层数据结构：SDS、intset、listpack、quicklist、skiplist（源码解析）

> 定位：**源码解析与原理**文章——以每种底层结构的主链源码为骨架（抓主链，不逐行）；类型层的"编码切换框架"已在 02 展开，本篇深入到结构内部；面试速答见附录 A。
> 源码基线：Redis 7.2.11；实验证据：`redis-lab/evidence/`（编码切换、内存账、慢操作）。

---

## 一、源码地图

| 文件 | 职责 | 本文引用点 |
|---|---|---|
| `sds.h` / `sds.c` | 动态字节串 | `sdshdr8/16/32/64`、`_sdsnewlen`、`_sdsMakeRoomFor`、`sdscatlen` |
| `intset.h` / `intset.c` | 有序整数集合 | `intset`、`intsetSearch`、`intsetUpgradeAndAdd` |
| `listpack.c` | 单块内存紧凑列表 | `LP_HDR_SIZE`、`lpAppend`、`lpInsert`、`lpSkip` |
| `quicklist.h` / `quicklist.c` | 双向链表 × listpack | `quicklistNode`、`quicklistNodeLimit`、`quicklistGetIteratorAtIdx` |
| `t_zset.c` + `server.h` | 跳表（ZSet 专用） | `zskiplist`、`zslRandomLevel`、`zslInsert`、`zslGetRank` |
| `t_list.c` / `t_set.c` | 类型与结构的粘合 | `listTypeTryConvertListpack`、`setTypeCreate` |
| `ziplist.h` / `ziplist.c` | 历史结构（对照） | `prevlen` 与级联更新 |
| `rdb.c` | 旧数据加载 | ziplist → listpack 转换 |

---

## 二、两层视图：类型 → 编码 → 结构（速查）

**例子先行**：`SADD s 1 5 40000` 这条命令背后，前两个整数用 2 字节/个的 `intset` 存；第三个整数一进来，整个集合会被"升级"成 4 字节/个的数组。同一个类型（Set），底层结构随数据形态变化——这是 02 的框架，本篇讲每种结构**内部**怎么做到。

**表 1：类型 → 当前编码 → 结构（7.2 默认阈值；阈值细节见 02 第六节）**

| 类型 | 小规模 / 整数编码 | 大规模编码 | 结构层（本篇展开） |
|---|---|---|---|
| String | `int` / `embstr`（≤44B） | `raw` | SDS（§三） |
| List | `listpack` | `quicklist`（单节点 8KB） | listpack（§五）+ quicklist（§六） |
| Hash | `listpack` | `hashtable`（dict） | listpack（§五）+ dict（02 §三） |
| Set | `intset`（全整数）/ `listpack` | `hashtable`（dict） | intset（§四） |
| ZSet | `listpack` | `skiplist + dict` | skiplist（§七） |

历史编码：`linkedlist`、`ziplist` 在 7.2 已不用于活跃写入（`server.h:884-885` 常量注释 "No longer used"）；ziplist 仅用于读取/转换旧数据（§五）。

**表 2：复杂度档位（源于 7.2 各命令 `commands/*.json` 的 `complexity` 字段）**

| 档位 | 代表命令 |
|---|---|
| O(1) | `SET`/`GET`/`INCR`；`HSET`/`HGET`/`HINCRBY`；`SADD`/`SISMEMBER`/`SCARD`；`ZSCORE`；`LLEN`；单元素 `LPUSH`/`LPOP` |
| O(logN) | `ZADD`/`ZINCRBY`/`ZRANK`；`ZRANGEBYSCORE`（`LIMIT` 取常数时） |
| O(N)（慢命令候选） | `HGETALL`/`HVALS`；`SMEMBERS`/`SINTER`/`SUNION`/`SDIFF`；`LRANGE`/`LINDEX`/`LREM`；多元素 `LPUSH`/`LPOP`；大集合 `DEL` |
| 游标替代 | `SCAN`/`HSCAN`/`SSCAN`/`ZSCAN`（每轮有界） |

判断规律：命令名带"全量"语义（`GETALL`/`MEMBERS`）或需要遍历到指定位置（`LINDEX`/`LRANGE`）→ 单次 O(N)；`S` 前缀游标命令把 O(N) 摊成多轮。**一条命令的真实成本 = 命令语义 × 当前底层实现**（承 02）。

---

## 三、SDS：二进制安全的动态字节串

**它干了什么**：`APPEND k " world"` 往已有值末尾追加 6 字节——SDS 负责"要多少给多少、追加不缩容、长度 O(1) 可读"。

**布局**（`sds.h:43-74`）：

```c
typedef char *sds;

/* Note: sdshdr5 is never used, we just access the flags byte directly.
 * However is here to document the layout of type 5 SDS strings. */
struct __attribute__ ((__packed__)) sdshdr5 {
    unsigned char flags; /* 3 lsb of type, and 5 msb of string length */
    char buf[];
};
struct __attribute__ ((__packed__)) sdshdr8 {
    uint8_t len; /* used */
    uint8_t alloc; /* excluding the header and null terminator */
    unsigned char flags; /* 3 lsb of type, 5 unused bits */
    char buf[];
};
```

`sdshdr16/32/64` 与之同构，仅 `len`/`alloc` 宽度为 16/32/64 位（`sds.h:57-74`）。`flags` 的低 3 位记录头部类型（`SDS_TYPE_MASK 7`，`sds.h:81`），读取时用 `s[-1]` 取 flags 再取 `len`/`alloc`——所以 SDS 指针指向 `buf`，头部在它左边。

**创建**（`sds.c:104-166`，节选）：

```c
sds _sdsnewlen(const void *init, size_t initlen, int trymalloc) {
    ...
    char type = sdsReqType(initlen);
    /* Empty strings are usually created in order to append. Use type 8
     * since type 5 is not good at this. */
    if (type == SDS_TYPE_5 && initlen == 0) type = SDS_TYPE_8;
    int hdrlen = sdsHdrSize(type);
    ...
    sh = trymalloc?
        s_trymalloc_usable(hdrlen+initlen+1, &usable) :
        s_malloc_usable(hdrlen+initlen+1, &usable);
    ...
    s = (char*)sh+hdrlen;
    fp = ((unsigned char*)s)-1;
    usable = usable-hdrlen-1;
    if (usable > sdsTypeMaxSize(type))
        usable = sdsTypeMaxSize(type);
    switch(type) {
        case SDS_TYPE_5: {
            *fp = type | (initlen << SDS_TYPE_BITS);
            break;
        }
        case SDS_TYPE_8: {
            SDS_HDR_VAR(8,s);
            sh->len = initlen;
            sh->alloc = usable;
            *fp = type;
            break;
        }
        ...
    }
    if (initlen && init)
        memcpy(s, init, initlen);
    s[initlen] = '\0';
    return s;
}
```

要点：一次分配"头部 + 数据 + 结尾 `\0`"；`alloc` 用分配器实际可用字节校准（`s_malloc_usable`）；空串强制用 type 8（type 5 没有 `alloc` 字段、不适合追加）。

**扩容策略**（`sds.c:240-293`，节选）：

```c
sds _sdsMakeRoomFor(sds s, size_t addlen, int greedy) {
    ...
    if (avail >= addlen) return s;

    len = sdslen(s);
    sh = (char*)s-sdsHdrSize(oldtype);
    reqlen = newlen = (len+addlen);
    assert(newlen > len);   /* Catch size_t overflow */
    if (greedy == 1) {
        if (newlen < SDS_MAX_PREALLOC)
            newlen *= 2;
        else
            newlen += SDS_MAX_PREALLOC;
    }

    type = sdsReqType(newlen);
    ...
    if (type == SDS_TYPE_5) type = SDS_TYPE_8;
    ...
```

扩容规则：目标长度 `< 1MB`（`SDS_MAX_PREALLOC`，`sds.h:36`）时**翻倍**，否则**加 1MB**；头部类型可能随之升档（8→16→32→64）；同型用 `realloc`，升型则重新分配并搬移数据。`sdsMakeRoomFor` = greedy 版（`sds.c:295-297`），另有 `sdsMakeRoomForNonGreedy`。

**追加**（`sds.c:486-497`）：

```c
sds sdscatlen(sds s, const void *t, size_t len) {
    size_t curlen = sdslen(s);

    s = sdsMakeRoomFor(s,len);
    if (s == NULL) return NULL;
    memcpy(s+curlen, t, len);
    sdssetlen(s, curlen+len);
    s[curlen+len] = '\0';
    return s;
}
```

**惰性收缩**（`sds.c:310-312`）：缩短不自动回收，由调用方显式决定：

```c
sds sdsRemoveFreeSpace(sds s, int would_regrow) {
    return sdsResize(s, sdslen(s), would_regrow);
}
```

**字节走查**（`APPEND`）：

1. `SET k hello`：短值走 embstr——robj 与 sdshdr8 在同一块内存（02 §五），此时 `alloc = len = 5`，没有空闲空间；
2. `APPEND k " world"`：`appendCommand` 先 `dbUnshareStringValue`（`t_string.c:697-727`、`db.c:434-443`）——对象是 embstr（非 RAW）→ 先转成独立 SDS 的 raw 对象：

```c
robj *dbUnshareStringValue(redisDb *db, robj *key, robj *o) {
    serverAssert(o->type == OBJ_STRING);
    if (o->refcount != 1 || o->encoding != OBJ_ENCODING_RAW) {
        robj *decoded = getDecodedObject(o);
        o = createRawStringObject(decoded->ptr, sdslen(decoded->ptr));
        decrRefCount(decoded);
        dbReplaceValue(db,key,o);
    }
    return o;
}
```

3. 然后 `o->ptr = sdscatlen(o->ptr,...)`：`addlen=6`、`avail=0` → `newlen = 11` → greedy 翻倍为 22 → type 仍为 8 → `realloc` → 写入并置新长度。追加后的字节布局：

```
[sdshdr8 len=11 alloc≈22 flags=1]["hello world\0"][≈11 字节空闲]
```

**原理**：对应 C 字符串三个缺陷——`len` 让取长度 O(1)（C 串 `strlen` O(N)）；以 `len` 而非 `\0` 判定结尾 → 二进制安全；预分配 + 惰性释放 → 追加摊还 O(1)、少分配。`sdshdr5` 的注释说明其设计目的只是"记录 type5 的布局"（实际不再用于新字符串）。

---

## 四、intset：有序整数数组与单向升级

**它干了什么**：`SADD s 1 5 40000`——前两个值用 int16 存（2 字节/个），第三个值放不进 int16，整个集合升级为 int32（4 字节/个），数据搬移后继续用有序数组。

**布局**（`intset.h:35-39`）：

```c
typedef struct intset {
    uint32_t encoding;
    uint32_t length;
    int8_t contents[];
} intset;
```

`contents` 按 `encoding`（int16/int32/int64）解释为**升序整数数组**；`encoding` 常量是各类型 `sizeof`（`intset.c:42-44`）。

**谁用它**（`t_set.c:46-57`）——SADD 建集合时的选择：

```c
robj *setTypeCreate(sds value, size_t size_hint) {
    if (isSdsRepresentableAsLongLong(value,NULL) == C_OK && size_hint <= server.set_max_intset_entries)
        return createIntsetObject();
    if (size_hint <= server.set_max_listpack_entries)
        return createSetListpackObject();
    ...
```

写入路径（`t_set.c:128-133`，节选）：

```c
        if (set->encoding == OBJ_ENCODING_INTSET) {
            uint8_t success = 0;
            set->ptr = intsetAdd(set->ptr, llval, &success);
            if (success) maybeConvertIntset(set);
            return success;
        }
```

`maybeConvertIntset`（`t_set.c:78-82`）：元素数超过 `set_max_intset_entries`（默认 512）转 hashtable；`SADD` 的"0/1 返回值"语义就来自 `success`。

**编码判定与查找**（`intset.c:46-54`、`117-157`）：

```c
static uint8_t _intsetValueEncoding(int64_t v) {
    if (v < INT32_MIN || v > INT32_MAX)
        return INTSET_ENC_INT64;
    else if (v < INT16_MIN || v > INT16_MAX)
        return INTSET_ENC_INT32;
    else
        return INTSET_ENC_INT16;
}
```

```c
static uint8_t intsetSearch(intset *is, int64_t value, uint32_t *pos) {
    int min = 0, max = intrev32ifbe(is->length)-1, mid = -1;
    int64_t cur = -1;

    /* The value can never be found when the set is empty */
    if (intrev32ifbe(is->length) == 0) {
        if (pos) *pos = 0;
        return 0;
    } else {
        /* Check for the case where we know we cannot find the value,
         * but do know the insert position. */
        if (value > _intsetGet(is,max)) {
            if (pos) *pos = intrev32ifbe(is->length);
            return 0;
        } else if (value < _intsetGet(is,0)) {
            if (pos) *pos = 0;
            return 0;
        }
    }

    while(max >= min) {
        mid = ((unsigned int)min + (unsigned int)max) >> 1;
        cur = _intsetGet(is,mid);
        if (value > cur) {
            min = mid+1;
        } else if (value < cur) {
            max = mid-1;
        } else {
            break;
        }
    }

    if (value == cur) {
        if (pos) *pos = mid;
        return 1;
    } else {
        if (pos) *pos = min;
        return 0;
    }
}
```

有序保证二分的正确性；两端先判边界，未命中时 `pos` 直接给出插入位置。

**升级与插入**（`intset.c:159-185`、`206-232`）：

```c
static intset *intsetUpgradeAndAdd(intset *is, int64_t value) {
    uint8_t curenc = intrev32ifbe(is->encoding);
    uint8_t newenc = _intsetValueEncoding(value);
    int length = intrev32ifbe(is->length);
    int prepend = value < 0 ? 1 : 0;

    /* First set new encoding and resize */
    is->encoding = intrev32ifbe(newenc);
    is = intsetResize(is,intrev32ifbe(is->length)+1);

    /* Upgrade back-to-front so we don't overwrite values.
     * Note that the "prepend" variable is used to make sure we have an empty
     * space at either the beginning or the end of the intset. */
    while(length--)
        _intsetSet(is,length+prepend,_intsetGetEncoded(is,length,curenc));

    /* Set the value at the beginning or the end. */
    if (prepend)
        _intsetSet(is,0,value);
    else
        _intsetSet(is,intrev32ifbe(is->length),value);
    is->length = intrev32ifbe(intrev32ifbe(is->length)+1);
    return is;
}
```

```c
intset *intsetAdd(intset *is, int64_t value, uint8_t *success) {
    uint8_t valenc = _intsetValueEncoding(value);
    uint32_t pos;
    if (success) *success = 1;

    if (valenc > intrev32ifbe(is->encoding)) {
        /* This always succeeds, so we don't need to curry *success. */
        return intsetUpgradeAndAdd(is,value);
    } else {
        if (intsetSearch(is,value,&pos)) {
            if (success) *success = 0;
            return is;
        }

        is = intsetResize(is,intrev32ifbe(is->length)+1);
        if (pos < intrev32ifbe(is->length)) intsetMoveTail(is,pos,pos+1);
    }

    _intsetSet(is,pos,value);
    is->length = intrev32ifbe(intrev32ifbe(is->length)+1);
    return is;
}
```

**升级走查**：`SADD s 1 5 100` 后 → `SADD s 40000`：

1. `valenc(40000) = INT32 > 当前 INT16` → `intsetUpgradeAndAdd`；
2. `prepend = 0`（正数）；改 `encoding = INT32`，`intsetResize(len+1 = 4)` → 数据区 16 字节；
3. **从尾到头**逐格原位重写（倒序保证：写宽格子会覆盖后面的旧格子，而它们已被读过）：`100→槽2`、`5→槽1`、`1→槽0`；
4. 新值 `40000` 写到末尾槽 3，`length = 4`。

字节布局（每格 4 字节）：

```
[encoding=INT32][length=4][1][5][100][40000]
```

若新值是负数（`prepend=1`）：搬移时整体后移一格，新值放槽 0。**升级单向不可逆**：不会因为后续删掉大值而降回 int16；插入本身是搬移 O(N) 一次性成本，之后继续 O(logN) 二分。

**原理**：全整数场景下，连续数组没有指针/哈希开销，内存最省；代价是插入/删除要移动数据、编码升档要整体重写。与 `listpack` 的分工：intset 只服务"全整数"的 Set，非整数小集合走 listpack（§五），超 512 转 dict。

---

## 五、listpack：单块内存 + 自身长度（backlen）

**它干了什么**：`HSET h f 123`——字段和值被"打包"进一块连续字节里；值 `123` 被识别为整数，整个 entry 只占 2 字节。它是小 Hash / 小 ZSet / 小 Set / 小 List 的统一紧凑格式。

**布局与头部宏**（`listpack.c:48-51`、`97`）：

```c
#define LP_HDR_SIZE 6       /* 32 bit total len + 16 bit number of elements. */
#define LP_HDR_NUMELE_UNKNOWN UINT16_MAX
#define LP_MAX_BACKLEN_SIZE 5
...
#define LP_EOF 0xFF
```

整体是：`[6 字节头：u32 总字节数 + u16 元素数][entry][entry]...[0xFF]`。元素数超过 65535 时头部记 `UINT16_MAX` 表示"未知"，`lpLength` 扫描后回填（`listpack.c:517-535`，节选）：

```c
unsigned long lpLength(unsigned char *lp) {
    uint32_t numele = lpGetNumElements(lp);
    if (numele != LP_HDR_NUMELE_UNKNOWN) return numele;

    /* Too many elements inside the listpack. We need to scan in order
     * to get the total number. */
    uint32_t count = 0;
    unsigned char *p = lpFirst(lp);
    while(p) {
        count++;
        p = lpNext(lp,p);
    }

    /* If the count is again within range of the header numele field,
     * set it. */
    if (count < LP_HDR_NUMELE_UNKNOWN) lpSetNumElements(lp,count);
    return count;
}
```

**创建与追加**（`listpack.c:242-250`、`963-967`）：

```c
unsigned char *lpNew(size_t capacity) {
    unsigned char *lp = lp_malloc(capacity > LP_HDR_SIZE+1 ? capacity : LP_HDR_SIZE+1);
    if (lp == NULL) return NULL;
    lpSetTotalBytes(lp,LP_HDR_SIZE+1);
    lpSetNumElements(lp,0);
    lp[LP_HDR_SIZE] = LP_EOF;
    return lp;
}
```

```c
unsigned char *lpAppend(unsigned char *lp, unsigned char *ele, uint32_t size) {
    uint64_t listpack_bytes = lpGetTotalBytes(lp);
    unsigned char *eofptr = lp + listpack_bytes - 1;
    return lpInsert(lp,ele,NULL,size,eofptr,LP_BEFORE,NULL);
}
```

**entry 编码**——每个 entry = `[编码+数据][backlen]`。尺寸与编码选择（`listpack.c:428-440`、`267-345`，节选）：

```c
static inline uint32_t lpCurrentEncodedSizeUnsafe(unsigned char *p) {
    if (LP_ENCODING_IS_7BIT_UINT(p[0])) return 1;
    if (LP_ENCODING_IS_6BIT_STR(p[0])) return 1+LP_ENCODING_6BIT_STR_LEN(p);
    if (LP_ENCODING_IS_13BIT_INT(p[0])) return 2;
    if (LP_ENCODING_IS_16BIT_INT(p[0])) return 3;
    if (LP_ENCODING_IS_24BIT_INT(p[0])) return 4;
    if (LP_ENCODING_IS_32BIT_INT(p[0])) return 5;
    if (LP_ENCODING_IS_64BIT_INT(p[0])) return 9;
    if (LP_ENCODING_IS_12BIT_STR(p[0])) return 2+LP_ENCODING_12BIT_STR_LEN(p);
    if (LP_ENCODING_IS_32BIT_STR(p[0])) return 5+LP_ENCODING_32BIT_STR_LEN(p);
    if (p[0] == LP_EOF) return 1;
    return 0;
}
```

```c
/* Stores the integer encoded representation of 'v' in the 'intenc' buffer. */
static inline void lpEncodeIntegerGetType(int64_t v, unsigned char *intenc, uint64_t *enclen) {
    if (v >= 0 && v <= 127) {
        /* Single byte 0-127 integer. */
        intenc[0] = v;
        *enclen = 1;
    } else if (v >= -4096 && v <= 4095) {
        /* 13 bit integer. */
        ...
```

```c
static inline int lpEncodeGetType(unsigned char *ele, uint32_t size, unsigned char *intenc, uint64_t *enclen) {
    int64_t v;
    if (lpStringToInt64((const char*)ele, size, &v)) {
        lpEncodeIntegerGetType(v, intenc, enclen);
        return LP_ENCODING_INT;
    } else {
        if (size < 64) *enclen = 1+size;
        else if (size < 4096) *enclen = 2+size;
        else *enclen = 5+(uint64_t)size;
        return LP_ENCODING_STRING;
    }
}
```

即：**能表示为整数就按整数存**（1/2/3/4/5/9 字节六档），否则按字符串存（长度前缀 1/2/5 字节）。

**backlen**——entry 末尾的"自身总长"字段，变长 1–5 字节；为**从尾向前解码**设计——最后一个字节存最低 7 位并用最高位（0x80）作续读标志，向前依次是更高位组（`lpDecodeBacklen` 读取、`lpEncodeBacklen` 写入）：

```c
static inline unsigned long lpEncodeBacklen(unsigned char *buf, uint64_t l) {
    if (l <= 127) {
        if (buf) buf[0] = l;
        return 1;
    } else if (l < 16383) {
        ...
```

正向遍历 `lpSkip` = 读编码长度 + 跳过 backlen（`listpack.c:464-470`）：

```c
unsigned char *lpSkip(unsigned char *p) {
    unsigned long entrylen = lpCurrentEncodedSizeUnsafe(p);
    entrylen += lpEncodeBacklen(NULL,entrylen);
    p += entrylen;
    return p;
}
```

反向遍历 `lpPrev` 靠 backlen 往回找（`listpack.c:485-495`）：

```c
unsigned char *lpPrev(unsigned char *lp, unsigned char *p) {
    assert(p);
    if (p-lp == LP_HDR_SIZE) return NULL;
    p--; /* Seek the first backlen byte of the last element. */
    uint64_t prevlen = lpDecodeBacklen(p);
    prevlen += lpEncodeBacklen(NULL,prevlen);
    p -= prevlen-1; /* Seek the first byte of the previous entry. */
    ...
```

**插入/替换核心**（`listpack.c:780-920`，节选）：先算新总长，必要时 `realloc`，然后 `memmove` 给新 entry 腾位置：

```c
    /* Setup the listpack relocating the elements to make the exact room
     * we need to store the new one. */
    if (where == LP_BEFORE) {
        memmove(dst+enclen+backlen_size,dst,old_listpack_bytes-poff);
    } else { /* LP_REPLACE. */
        memmove(dst+enclen+backlen_size,
                dst+replaced_len,
                old_listpack_bytes-poff-replaced_len);
    }
```

```c
    /* Store the entry. */
    if (!delete) {
        if (enctype == LP_ENCODING_INT) {
            memcpy(dst,eleint,enclen);
        } else if (elestr) {
            lpEncodeString(dst,elestr,size);
        } else {
            redis_unreachable();
        }
        dst += enclen;
        memcpy(dst,backlen,backlen_size);
        dst += backlen_size;
    }

    /* Update header. */
    ...
    lpSetTotalBytes(lp,new_listpack_bytes);
```

**字节走查**：`HSET h f 123`

1. 建 hash：`lpNew` → `[07 00 00 00 | 00 00][FF]`（总长 7 = 6 字节头 + 1 字节 EOF）；
2. `HSET` → `hashTypeSet`（`t_hash.c:211-234`）：空表用 `lpFirst` 找不到字段 → 走"新增"分支，**字段与值各追加一条 entry**：

```c
        if (!update) {
            /* Push new field/value pair onto the tail of the listpack */
            zl = lpAppend(zl, (unsigned char*)field, sdslen(field));
            zl = lpAppend(zl, (unsigned char*)value, sdslen(value));
        }
```

   字段 `f` 不是整数 → 6-bit 字符串编码（`0x81|len` + 数据字节）：`[81 66 02]`；值 `123` → 7-bit 整数：`[7B 01]`；两条各自带 backlen，头部更新为总长 12、元素数 2：

```
[0C 00 00 00 | 02 00][81 66 02][7B 01][FF]
 └ u32 总长=12 └ u16 数=2  "f"    123     EOF
```

再 `HSET h g 5000`：字段 `g`（`[81 67 02]`）+ 值 5000（超出 13-bit 范围 → 16-bit 整数 `[F1 88 13 03]`）→ 总长 19、元素数 4：

```
[13 00 00 00 | 04 00][81 66 02][7B 01][81 67 02][F1 88 13 03][FF]
```

（更新已有字段走 `lpReplace`，`t_hash.c:225`。）

**与 ziplist 的对比（为什么被取代）**——ziplist 的 entry 结构是（`ziplist.c:40-69`，节选）：

```
 * <prevlen> <encoding> <entry-data>
...
 * The length of the previous entry, <prevlen>, is encoded in the following way:
 * If this length is smaller than 254 bytes, it will only consume a single
 * byte ... When the length
 * is greater than or equal to 254, it will consume 5 bytes.
```

问题在于 `<prevlen>` 记录的是**前一条**的长度：在头部插入/删除导致后面某条 entry 长度跨过 254 字节阈值时，它后面**所有** entry 的 prevlen 都要重写（1 字节↔5 字节），即级联更新（`__ziplistCascadeUpdate`，`ziplist.c:751`）。listpack 把"前一条长度"换成"**自身长度**"（backlen），插入只影响被插 entry 本身——从根源消除级联。

7.2 中 ziplist 仅用于**加载旧数据并转成 listpack**（`rdb.c:1660-1662` 注释：`The ziplist element pointed by 'p' will be converted and stored into listpack`）。

**原理**：紧凑编码省内存（无指针、判整数更省），代价是**没有索引**——随机访问 O(N)（顺序跳过 entry）、中间插入/删除 O(N)（memmove）。所以它只用于"小"规模（阈值见 02 第六节），长大即转换。

---

## 六、quicklist：listpack 的双向链表

**它干了什么**：`LINDEX mylist 50000` 在大列表上要"跳"到第 5 万个元素——quicklist 把列表切成多个 ≤8KB 的 listpack 节点，用双向链表串起来，定位时先选近端、按节点计数跳，再进节点定位。

**结构**（`quicklist.h:36-63`、`106-115`，节选）：

```c
/* quicklistNode is a 32 byte struct describing a listpack for a quicklist.
 * We use bit fields keep the quicklistNode at 32 bytes.
 * count: 16 bits, max 65536 (max lp bytes is 65k, so max count actually < 32k).
 * encoding: 2 bits, RAW=1, LZF=2.
 * container: 2 bits, PLAIN=1 (a single item as char array), PACKED=2 (listpack with multiple items).
 * ... */
typedef struct quicklistNode {
    struct quicklistNode *prev;
    struct quicklistNode *next;
    unsigned char *entry;
    size_t sz;             /* entry size in bytes */
    unsigned int count : 16;     /* count of items in listpack */
    unsigned int encoding : 2;   /* RAW==1 or LZF==2 */
    unsigned int container : 2;  /* PLAIN==1 or PACKED==2 */
    ...
} quicklistNode;
```

```c
typedef struct quicklist {
    quicklistNode *head;
    quicklistNode *tail;
    unsigned long count;        /* total count of all entries in all listpacks */
    unsigned long len;          /* number of quicklistNodes */
    signed int fill : QL_FILL_BITS;       /* fill factor for individual nodes */
    unsigned int compress : QL_COMP_BITS; /* depth of end nodes not to compress;0=off */
    ...
} quicklist;
```

**节点容量上限**——由 `fill` 换算（`quicklist.c:46-49`、`467-479`）：

```c
/* Optimization levels for size-based filling. ... */
static const size_t optimization_level[] = {4096, 8192, 16384, 32768, 65536};
```

```c
void quicklistNodeLimit(int fill, size_t *size, unsigned int *count) {
    *size = SIZE_MAX;
    *count = UINT_MAX;

    if (fill >= 0) {
        /* Ensure that one node have at least one entry */
        *count = (fill == 0) ? 1 : fill;
    } else {
        size_t offset = (-fill) - 1;
        size_t max_level = sizeof(optimization_level) / sizeof(*optimization_level);
        if (offset >= max_level) offset = max_level - 1;
        *size = optimization_level[offset];
    }
}
```

默认 `list-max-listpack-size = -2`（`config.c:3157`）→ `optimization_level[1] = 8192`（8KB）；另外有兜底常数 `SIZE_SAFETY_LIMIT 8192`（`quicklist.c:73-78`：按个数限制的节点最长也不超过 8KB 的安全值）。判断能否插入（`quicklist.c:503-519`，节选）：

```c
REDIS_STATIC int _quicklistNodeAllowInsert(const quicklistNode *node,
                                           const int fill, const size_t sz) {
    if (unlikely(!node))
        return 0;

    if (unlikely(QL_NODE_IS_PLAIN(node) || isLargeElement(sz)))
        return 0;
    ...
    size_t new_sz = node->sz + sz + SIZE_ESTIMATE_OVERHEAD;
    if (unlikely(quicklistNodeExceedsLimit(fill, new_sz, node->count + 1)))
        return 0;
    return 1;
}
```

**两端推入**（`quicklist.c:570-588`，节选）：

```c
int quicklistPushHead(quicklist *quicklist, void *value, size_t sz) {
    quicklistNode *orig_head = quicklist->head;

    if (unlikely(isLargeElement(sz))) {
        __quicklistInsertPlainNode(quicklist, quicklist->head, value, sz, 0);
        return 1;
    }

    if (likely(
            _quicklistNodeAllowInsert(quicklist->head, quicklist->fill, sz))) {
        quicklist->head->entry = lpPrepend(quicklist->head->entry, value, sz);
        quicklistNodeUpdateSz(quicklist->head);
    } else {
        quicklistNode *node = quicklistCreateNode();
        node->entry = lpPrepend(lpNew(0), value, sz);

        quicklistNodeUpdateSz(node);
        _quicklistInsertNodeBefore(quicklist, quicklist->head, node);
    }
    quicklist->count++;
    quicklist->head->count++;
    return (orig_head != quicklist->head);
}
```

`isLargeElement` 用 `packed_threshold`（默认 1GB，`quicklist.c:51-55`）判断"超大元素"——这类元素单独成 PLAIN 节点（头部注释 `PLAIN=1 (a single item as char array)`）。

**节点分裂**（`quicklist.c:958-990`，节选）——在"已满"节点的中间插入时：复制整块，两边各自裁掉多余部分：

```c
REDIS_STATIC quicklistNode *_quicklistSplitNode(quicklistNode *node, int offset,
                                                int after) {
    size_t zl_sz = node->sz;

    quicklistNode *new_node = quicklistCreateNode();
    new_node->entry = zmalloc(zl_sz);

    /* Copy original listpack so we can split it */
    memcpy(new_node->entry, node->entry, zl_sz);
    ...
    node->entry = lpDeleteRange(node->entry, orig_start, orig_extent);
    node->count = lpLength(node->entry);
    quicklistNodeUpdateSz(node);

    new_node->entry = lpDeleteRange(new_node->entry, new_start, new_extent);
    new_node->count = lpLength(new_node->entry);
    quicklistNodeUpdateSz(new_node);
    ...
    return new_node;
}
```

**按下标定位（LINDEX 的实现）**（`quicklist.c:1265-1319`，节选）：

```c
quicklistIter *quicklistGetIteratorAtIdx(quicklist *quicklist,
                                         const int direction,
                                         const long long idx)
{
    ...
    /* Seek in the other direction if that way is shorter. */
    int seek_forward = forward;
    unsigned long long seek_index = index;
    if (index > (quicklist->count - 1) / 2) {
        seek_forward = !forward;
        seek_index = quicklist->count - 1 - index;
    }

    n = seek_forward ? quicklist->head : quicklist->tail;
    while (likely(n)) {
        if ((accum + n->count) > seek_index) {
            break;
        } else {
            ...
            accum += n->count;
            n = seek_forward ? n->next : n->prev;
        }
    }
    ...
```

定位规则：**下标超过半数就从尾端找**；按节点 `count` 累加定位目标节点，再在节点内的 listpack 里定位（`iter->offset = index - accum`）。命令入口 `lindexCommand`（`t_list.c:594-620`）→ `listTypeInitIterator`（`t_list.c:235-257`）→ quicklist 分支调用上面的函数，listpack 分支用 `lpSeek`。

**List 编码转换**——从小 listpack 切到 quicklist 的触发（`t_list.c:42-76`，节选）：

```c
static void listTypeTryConvertListpack(robj *o, robj **argv, int start, int end,
                                       beforeConvertCB fn, void *data)
{
    serverAssert(o->encoding == OBJ_ENCODING_LISTPACK);
    ...
    if (quicklistNodeExceedsLimit(server.list_max_listpack_size,
            lpBytes(o->ptr) + add_bytes, lpLength(o->ptr) + add_length))
    {
        /* Invoke callback before conversion. */
        if (fn) fn(data);

        quicklist *ql = quicklistCreate();
        quicklistSetOptions(ql, server.list_max_listpack_size, server.list_compress_depth);

        /* Append listpack to quicklist if it's not empty, otherwise release it. */
        if (lpLength(o->ptr))
            quicklistAppendListpack(ql, o->ptr);
        ...
```

**走查**：`RPUSH` 时 `quicklistPushTail`：尾节点还能装（`_quicklistNodeAllowInsert`）→ `lpAppend` 到该节点的 listpack；装不下 → 新建节点（`lpNew` + `lpAppend`）挂到链表尾。中间插入且节点满 → `_quicklistSplitNode` 一分为二再插。

**原理**：链表节点数远小于元素数（一个 8KB 节点可容纳成百上千个小元素），所以"逐节点 O(节点数)"实际近似 O(1) 级别；同时每节点是一块连续内存，省指针开销。折中点是"节点大小"：太大则节点内插入/删除慢（memmove 大），太小则指针开销回升。压缩（LZF）是进一步的省内存手段（`compress` 深度控制两端不压缩的节点数）。

---

## 七、skiplist：带跨度的有序链表（ZSet）

**它干了什么**：`ZADD hot 100 note1` 在大 ZSet 上要按分数排序插入；`ZRANK` 要回答"note1 排第几"——跳表用多层索引把插入/查询降到 O(logN)，用 `span` 把"第几名"在查找途中顺带算出来。

**结构与参数**（`server.h:1337-1356`、`523-524`）：

```c
/* ZSETs use a specialized version of Skiplists */
typedef struct zskiplistNode {
    sds ele;
    double score;
    struct zskiplistNode *backward;
    struct zskiplistLevel {
        struct zskiplistNode *forward;
        unsigned long span;
    } level[];
} zskiplistNode;

typedef struct zskiplist {
    struct zskiplistNode *header, *tail;
    unsigned long length;
    int level;
} zskiplist;

typedef struct zset {
    dict *dict;
    zskiplist *zsl;
} zset;
```

```c
#define ZSKIPLIST_MAXLEVEL 32 /* Should be enough for 2^64 elements */
#define ZSKIPLIST_P 0.25      /* Skiplist P = 1/4 */
```

`zset` 同时挂 `dict`（成员→分数，`ZSCORE` O(1)）与 `zsl`（排序索引）。创建时 header 直接按最大层数分配（`t_zset.c:82-97`，节选）：

```c
zskiplist *zslCreate(void) {
    ...
    zsl->level = 1;
    zsl->length = 0;
    zsl->header = zslCreateNode(ZSKIPLIST_MAXLEVEL,0,NULL);
    for (j = 0; j < ZSKIPLIST_MAXLEVEL; j++) {
        zsl->header->level[j].forward = NULL;
        zsl->header->level[j].span = 0;
    }
    ...
```

**随机层数**（`t_zset.c:124-131`）：

```c
int zslRandomLevel(void) {
    static const int threshold = ZSKIPLIST_P*RAND_MAX;
    int level = 1;
    while (random() < threshold)
        level += 1;
    return (level<ZSKIPLIST_MAXLEVEL) ? level : ZSKIPLIST_MAXLEVEL;
}
```

每层以 1/4 概率继续升层，期望层数约 1.33；无重平衡。

**插入（核心）**（`t_zset.c:135-190`）：

```c
zskiplistNode *zslInsert(zskiplist *zsl, double score, sds ele) {
    zskiplistNode *update[ZSKIPLIST_MAXLEVEL], *x;
    unsigned long rank[ZSKIPLIST_MAXLEVEL];
    int i, level;

    serverAssert(!isnan(score));
    x = zsl->header;
    for (i = zsl->level-1; i >= 0; i--) {
        /* store rank that is crossed to reach the insert position */
        rank[i] = i == (zsl->level-1) ? 0 : rank[i+1];
        while (x->level[i].forward &&
                (x->level[i].forward->score < score ||
                    (x->level[i].forward->score == score &&
                    sdscmp(x->level[i].forward->ele,ele) < 0)))
        {
            rank[i] += x->level[i].span;
            x = x->level[i].forward;
        }
        update[i] = x;
    }
    /* we assume the element is not already inside, since we allow duplicated
     * scores, reinserting the same element should never happen since the
     * caller of zslInsert() should test in the hash table if the element is
     * already inside or not. */
    level = zslRandomLevel();
    if (level > zsl->level) {
        for (i = zsl->level; i < level; i++) {
            rank[i] = 0;
            update[i] = zsl->header;
            update[i]->level[i].span = zsl->length;
        }
        zsl->level = level;
    }
    x = zslCreateNode(level,score,ele);
    for (i = 0; i < level; i++) {
        x->level[i].forward = update[i]->level[i].forward;
        update[i]->level[i].forward = x;

        /* update span covered by update[i] as x is inserted here */
        x->level[i].span = update[i]->level[i].span - (rank[0] - rank[i]);
        update[i]->level[i].span = (rank[0] - rank[i]) + 1;
    }

    /* increment span for untouched levels */
    for (i = level; i < zsl->level; i++) {
        update[i]->level[i].span++;
    }

    x->backward = (update[0] == zsl->header) ? NULL : update[0];
    if (x->level[0].forward)
        x->level[0].forward->backward = x;
    else
        zsl->tail = x;
    zsl->length++;
    return x;
}
```

三个关键点：

1. **比序**：先比 `score`，分数相同再 `sdscmp(ele)`——分数可重复，成员唯一由调用方先查 dict 保证（注释原文）；
2. **两个数组**：`update[i]` = 第 i 层插入点的前驱；`rank[i]` = 从 header 到 `update[i]` 在第 0 层数了多少个节点（即"排名前缀和"）；
3. **span 修正**：新节点插在 `rank[0]+1` 位；每层用 `span = 旧 span - (rank[0]-rank[i])` 把跨过新节点的跨度拆成两段；没触及的层 `span++`。

**插入走查**（3 个节点 A(10)、B(20)、C(30)，对应分数 10/20/30；已知两条链）：

- level 1（顶层）：`header →(span 1) A →(span 2) C`
- level 0：`header →(1) A →(1) B →(1) C`

插入 D(25)：

| i | rank[i] 初始 | 前进过程 | rank[i] 终值 | update[i] |
|---|---|---|---|---|
| 1 | 0 | 经 A（span 1），遇 C(30) 停 | 1 | A |
| 0 | 1 | 经 B（span 1），遇 C(30) 停 | 2 | B |

`rank[0] = 2` → D 落在第 3 位。若 `zslRandomLevel()` 返回 1（只用 0 层）：`D.span = update[0].span - (2-2) = 1`；`update[0].span = (2-2)+1 = 1`（A→D 跨度 1，D→C 跨度 1）；第 1 层未触及 → `update[1].span++`（A→C 跨度从 2 变 3，覆盖 B、D、C）。若随机层数超过当前 `zsl->level`，先把多出的层在 header 上登记（`update[i]=header`、`span=length`），再按同一公式拆 span。

**排名查询**（`t_zset.c:478-499`）：

```c
unsigned long zslGetRank(zskiplist *zsl, double score, sds ele) {
    zskiplistNode *x;
    unsigned long rank = 0;
    int i;

    x = zsl->header;
    for (i = zsl->level-1; i >= 0; i--) {
        while (x->level[i].forward &&
            (x->level[i].forward->score < score ||
                (x->level[i].forward->score == score &&
                sdscmp(x->level[i].forward->ele,ele) <= 0))) {
            rank += x->level[i].span;
            x = x->level[i].forward;
        }

        /* x might be equal to zsl->header, so test if obj is non-NULL */
        if (x->ele && x->score == score && sdscmp(x->ele,ele) == 0) {
            return rank;
        }
    }
    return 0;
}
```

**排名走查**：在上面两条链里查 C(30)：第 1 层 `header.forward = A(10) ≤ 30` → `rank += 1`，x=A；`A.forward = C(30)`，分数相等且 `sdscmp(C,C) <= 0` → `rank += 2`（rank=3），x=C；下一跳为空，检查 `x` 等于目标 → 返回 **3**（`zslGetRank` 为 1-based）；命令层 `zsetRank` 再返回 `rank-1`，所以 ZRANK 对客户端的回复是 **2**（0-based，`t_zset.c:1558-1587`）。对照 `zslGetElementByRank`（`t_zset.c:502-519`）用 `traversed + span <= rank` 反向回答"第 N 名是谁"（ZRANGE 按下标取）。

**原理**：跳表用"随机层数"替代平衡树的旋转重平衡，实现简单；`span` 让排名信息在 O(logN) 查找途中同步算出（平衡树要维护子树 size 才能做到）；范围查询从 `zslFirstInRange`/`zslLastInRange`（`t_zset.c:333` 起）出发沿 0 层顺序取，成本 O(logN+M)。点查（`ZSCORE`）走配套 dict，O(1)。

---

## 八、走一遍：三条命令落到结构

1. **`SADD s 1 5 40000`**：建集合按"全整数 + hint ≤ 512"选 `intset`（§四）；前两个值二分插入 int16 数组；第三个值触发 `intsetUpgradeAndAdd` 整体升 int32。若之后继续加到超过 512 个成员 → `maybeConvertIntset` 转 dict（02 §六）。
2. **`HSET cart:1 apple 5`**：键 `cart:1` 先在键空间 dict 定位（02 §三）；值对象是 listpack → `lpAppend` 尾部追加 entry（§五）；若这是第 513 个字段或单值超 64 字节 → 整块转 dict（02 §六）。
3. **`ZADD hot 100 note1`**：小 ZSet 走 listpack（`t_zset.c:1415-1421`，02 §六）；成员数超 128 或单值超 64B → `zsetConvertAndExpand` 转 `skiplist + dict`；此后插入走 `zslInsert`（§七），`ZSCORE` 走 dict O(1)，`ZRANK` 走 `zslGetRank`。

三条命令对应同一个模式：**先定位键（dict）→ 再看值对象的编码（02）→ 最后落到结构自身的操作（本篇）**。

---

## 九、项目中的对应实现（类型选择理由）

| 场景 | 类型 | 当前实现 | 为什么选它 |
|---|---|---|---|
| 库存分桶、券库存（`InventoryService.java:819`、`claim_coupon.lua`） | String | int 编码 | 只需数值 + 原子加减（O(1)） |
| IM 未读计数（`ChatService.java:200`） | Hash | listpack → dict | 一个用户一组"会话→未读数"，按字段自增；单键避免 N 个键的固定开销（02 内存账） |
| 点赞关系（`LikeService.java:26-27,81`） | Set | intset（整数） | `SADD` 返回 0/1 即天然幂等；整数集合自动 intset（§四） |
| 热搜榜 / 推荐池（`HotSearchService.java:282`、`RecommendComputeJob.java:413`） | ZSet | listpack → skiplist | 需要按分数排序、TopN 范围查询（§七） |
| 限流滑动窗口（`RateLimitAspect.java:61-64`） | ZSet | listpack → skiplist | score=时间戳，`ZREMRANGEBYSCORE` 清窗口 + `ZCARD` 计数 |
| Feed 收件箱（`FeedPushConsumer`） | ZSet | listpack → skiplist | 按发布时间排序 + 按排名裁剪 |
| 幂等标记 / 版本号 / 锁 | String | embstr/int | `SET NX PX` 或 CAS 语义 |

已知改进点（与 01/02 同）：大结构删除未使用 `UNLINK`（检索 0 处）；延时类需求未用 ZSet 轮询（当前项目延时依赖消息队列的延时消息与调度任务）。

---

## 十、原理总结

1. **五种结构对应两组权衡**：连续内存（SDS/intset/listpack、quicklist 的节点）换内存效率，代价是插入/删除要搬移；指针结构（quicklist 链表、skiplist）换修改效率，代价是指针开销。阈值就是均衡点（§三~§七）；
2. **命令成本 = 语义 × 当前结构**：同一个 `LINDEX`，listpack 上是小数组扫描，quicklist 上是"选近端 + 跨节点"（§六）；同一个 `ZRANK`，小 ZSet 扫 listpack，大 ZSet 走 span（§七）；
3. **两个"把成本放对位置"的设计**：listpack 的 backlen 用"自身长度"消除 ziplist 的 prevlen 级联更新（§五）；skiplist 的 span 让排名在查找途中顺带算出（§七）；
4. **升级与转换都是单向、一次性 O(N)**：intset 编码只升不降（§四）；listpack→quicklist/dict/skiplist 的转换发生在触发阈值的那次写（02 §六）；单线程模型下这些都是"那一次命令"的阻塞来源（承 01）；
5. **紧凑格式的适用边界**：没有索引 → 随机访问 O(N)，所以只服务小规模/两端操作为主的数据；"长大"后自动切到有索引结构（§二表 1）。

---

## 附录 A：面试速答（附带）

> Redis 底层结构按"连续内存 vs 指针结构"取舍：String 是 SDS——带 len/alloc 头的动态字节串，O(1) 取长度、二进制安全、追加按 <1MB 翻倍、≥1MB 加 1MB 预分配，缩短惰性回收；小 Set 全整数走 intset——有序数组二分查找，插大数触发的"升级"是整体重写且不可逆，超 512 转哈希表；小 Hash/小 ZSet/小 Set 走 listpack——6 字节头 + entry（编码+数据+自身长度的 backlen）+ 0xFF 结尾，能当整数就当整数（1~9 字节六档），它的 backlen 正是为了消除 ziplist 旧结构的"前一条长度"级联更新；大 List 走 quicklist——每个节点一个 ≤8KB 的 listpack 的双向链表，默认 list-max-listpack-size=-2，LINDEX 从近端按节点计数定位；大 ZSet 走跳表——32 层上限、1/4 概率升层，插入用 update/rank 数组一次定位，span 让 ZRANK 在 O(logN) 查找中顺带算出，ZSCORE 另有 dict 兜底 O(1)。判断成本：命令语义乘当前结构，小规模下的 O(N) 常数很小，长大后的范围查询才是跳表的主场。

## 附录 B：实验与复现

```bash
cd redis-lab && docker compose up -d
bash scripts/03-encoding.sh redis-lab-7                 # 编码切换点 + 内存账（02/03 共用）
bash scripts/04-slow-ops.sh redis-lab-7 300000 300000   # 慢操作对照（结束自动清空）
```

关键数据（引自 02 课证据 `evidence/03-encoding-7.log`、`04-slow-ops-7.log`）：

- 切换点实测：String 44/45 字节；Hash 100→listpack、600→hashtable、单值 65B→hashtable；Set 512→intset、514→hashtable、3 非整数→listpack；List 200→listpack、+200×100B→quicklist；ZSet 128→listpack、130→skiplist；
- 内存账：100 字段 String×100 = 5,584B vs Hash×1 = 1,036B（5.4×）；
- 慢操作：30 万字段 HGETALL 期间 PING 342ms（基线 91ms），HSCAN 分轮 82ms。

## 附录 C：源码坐标系（本文引用）

| 内容 | 坐标 |
|---|---|
| SDS 头部家族（sdshdr5/8/16/32/64） | `sds.h:46-74` |
| 类型常量与 `SDS_TYPE_MASK` | `sds.h:76-84` |
| `SDS_MAX_PREALLOC`（1MB） | `sds.h:36` |
| 创建 `_sdsnewlen` | `sds.c:104-166` |
| 扩容 `_sdsMakeRoomFor` | `sds.c:240-293`；greedy 包装 `sds.c:295-297` |
| 追加 `sdscatlen` | `sds.c:486-497` |
| 惰性收缩 `sdsRemoveFreeSpace` | `sds.c:310-312`（→`sdsResize`） |
| APPEND 命令与解共享 | `t_string.c:697-727`；`db.c:434-443` |
| intset 结构 | `intset.h:35-39` |
| 编码判定 `_intsetValueEncoding` | `intset.c:46-54` |
| 二分查找 `intsetSearch` | `intset.c:117-157` |
| 升级 `intsetUpgradeAndAdd` | `intset.c:159-185` |
| 插入 `intsetAdd` | `intset.c:206-232` |
| Set 创建选 intset / 写入分支 | `t_set.c:46-57`、`t_set.c:128-133` |
| listpack 头部宏 / EOF | `listpack.c:48-51`、`listpack.c:97` |
| `lpNew` | `listpack.c:242-250` |
| entry 尺寸表 | `listpack.c:428-440` |
| 整数编码档位 `lpEncodeIntegerGetType` | `listpack.c:267-273` 起 |
| 编码选择 `lpEncodeGetType` | `listpack.c:329-340` |
| backlen 编码 `lpEncodeBacklen` | `listpack.c:347-382` |
| 正向/反向遍历 `lpSkip`/`lpPrev` | `listpack.c:464-470`、`listpack.c:485-495` |
| `lpLength`（未知计数回填） | `listpack.c:517-535` |
| 追加 `lpAppend` | `listpack.c:963-967` |
| Hash 写入 `hashTypeSet`（listpack 追加/替换） | `t_hash.c:211-234`（`lpReplace` `225`、`lpAppend` `231-232`） |
| 插入/替换 `lpInsert` | `listpack.c:780-920`（memmove `859-867`、header 更新 `895-905`） |
| ziplist 布局与 prevlen | `ziplist.c:40-69`；`ZIP_BIG_PREVLEN` `ziplist.c:196-198` |
| ziplist 级联更新 | `ziplist.c:751`（`__ziplistCascadeUpdate`） |
| 旧数据转换 | `rdb.c:1660-1662` |
| quicklistNode（32B 位域） | `quicklist.h:36-63` |
| quicklist 结构 | `quicklist.h:106-115` |
| 优化档位与安全上限 | `quicklist.c:46-49`、`quicklist.c:73-79` |
| PLAIN 阈值 `packed_threshold` | `quicklist.c:51-55` |
| 节点上限 `quicklistNodeLimit` | `quicklist.c:467-479` |
| 超限判定 `quicklistNodeExceedsLimit` | `quicklist.c:486-501` |
| 允许插入 `_quicklistNodeAllowInsert` | `quicklist.c:503-519` |
| 头插 `quicklistPushHead` | `quicklist.c:570-588` |
| 节点分裂 `_quicklistSplitNode` | `quicklist.c:958-990` |
| 按索引定位 `quicklistGetIteratorAtIdx` | `quicklist.c:1265-1319` |
| LINDEX 命令 / 迭代器分派 | `t_list.c:594-620`、`t_list.c:235-257` |
| List listpack→quicklist | `t_list.c:42-76` |
| 默认 `list-max-listpack-size=-2` | `config.c:3157` |
| 跳表结构与参数 | `server.h:1337-1356`、`server.h:523-524` |
| 创建 / 随机层数 | `t_zset.c:82-97`、`t_zset.c:124-131` |
| 插入 `zslInsert` | `t_zset.c:135-190` |
| 删除 `zslDeleteNode` | `t_zset.c:194-215` |
| 排名 `zslGetRank` | `t_zset.c:478-499` |
| 按排名取元素 `zslGetElementByRank` | `t_zset.c:502-519` |
| 范围查找 | `t_zset.c:333` 起（`zslFirstInRange`） |
| zset = dict + zsl | `server.h:1353-1356` |
