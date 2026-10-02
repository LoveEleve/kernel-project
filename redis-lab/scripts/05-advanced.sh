#!/usr/bin/env bash
# 实验 05：高级结构核心点——Stream（PEL 生命周期）/ Bitmap（位寻址）/
#           HyperLogLog（稀疏→稠密 + 基数估计）/ Geo（score = 52 位 geohash）
# 对应：04 高级数据结构（核心点版）；容器：redis-lab-7（7.2.11）
set -u
CT=${1:-${CT:-redis-lab-7}}
R() { docker exec "$CT" redis-cli "$@"; }

R flushall >/dev/null

echo "═══ 一、Stream：消息存储 + 消费组 PEL 生命周期 ═══"
R xadd s 1-0 f a >/dev/null
R xadd s 2-0 f b >/dev/null
R xadd s 3-0 f c >/dev/null
echo "XADD 3 条（1-0/2-0/3-0）→ XLEN=$(R xlen s | tr -d '\r')"
echo "XINFO STREAM 关键行："
R xinfo stream s | tr -d '\r' | paste - - | grep -E "length|radix-tree|last-generated|max-deleted|entries-added" | sed 's/^/    /'

R xgroup create s g 0 >/dev/null
echo; echo "XGROUP CREATE s g 0 → groups=$(R xinfo groups s | tr -d '\r' | tr '\n' ' ' | cut -c1-90)"
R xreadgroup group g c1 count 2 streams s '>' >/dev/null
echo "XREADGROUP（c1 读 2 条）→ XPENDING 摘要: $(R xpending s g | tr -d '\r' | tr '\n' ' ')"
R xack s g 2-0 >/dev/null
echo "XACK 2-0 后            → XPENDING 摘要: $(R xpending s g | tr -d '\r' | tr '\n' ' ')"
echo "XINFO CONSUMERS（c1）："
R xinfo consumers s g | tr -d '\r' | paste - - | sed 's/^/    /'

echo; echo "═══ 二、Bitmap：位寻址与自动扩长 ═══"
R setbit b 100 1 >/dev/null
echo "SETBIT b 100 1 → STRLEN=$(R strlen b | tr -d '\r') 字节（bit 100 → 第 12 字节；byte=100>>3）"
echo "GETBIT b 100=$(R getbit b 100 | tr -d '\r')  GETBIT b 99=$(R getbit b 99 | tr -d '\r')  BITCOUNT b=$(R bitcount b | tr -d '\r')"
R setbit sparse 1000000 1 >/dev/null
echo "SETBIT sparse 1000000 1 → STRLEN=$(R strlen sparse | tr -d '\r') 字节（≈125KB，只为 1 个高位 bit）"

echo; echo "═══ 三、HyperLogLog：基数估计 + 稀疏→稠密 ═══"
docker exec "$CT" sh -c 'redis-cli pfadd hll $(seq 1 1000) >/dev/null; redis-cli sadd exact $(seq 1 1000) >/dev/null'
echo "PFADD 1000 个不同元素 → PFCOUNT=$(R pfcount hll | tr -d '\r')（精确 SCARD=$(R scard exact | tr -d '\r')）"
echo "MEMORY USAGE hll（稀疏期）≈ $(R memory usage hll | tr -d '\r') 字节；PFDEBUG ENCODING=$(R pfdebug encoding hll 2>/dev/null | tr -d '\r' || echo '(不可用)')"
docker exec "$CT" sh -c 'redis-cli pfadd hll $(seq 1001 20000) >/dev/null'
echo "PFADD 累计 2 万 → PFCOUNT=$(R pfcount hll | tr -d '\r')"
echo "MEMORY USAGE hll（稠密期）≈ $(R memory usage hll | tr -d '\r') 字节；PFDEBUG ENCODING=$(R pfdebug encoding hll 2>/dev/null | tr -d '\r' || echo '(不可用)')"

echo; echo "═══ 四、Geo：score 就是 52 位 geohash ═══"
R geoadd geo 116.397128 39.916527 beijing >/dev/null
R geoadd geo 121.473701 31.230416 shanghai >/dev/null
echo "GEOADD 北京/上海 → ZSCORE geo beijing=$(R zscore geo beijing | tr -d '\r')（52 位整数）"
echo "ZSCORE geo shanghai=$(R zscore geo shanghai | tr -d '\r')"
echo "GEOPOS geo beijing → $(R geopos geo beijing | tr -d '\r' | tr '\n' ' ')"
echo "GEODIST geo beijing shanghai km → $(R geodist geo beijing shanghai km | tr -d '\r') km"
echo "GEOSEARCH（北京 100km 内，ASC）→ $(R geosearch geo fromlonlat 116.397128 39.916527 byradius 100 km asc | tr -d '\r' | tr '\n' ' ')"

R flushall >/dev/null
echo; echo "（已清空实验数据）"
