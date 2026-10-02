#!/usr/bin/env bash
# 实验 03：对象编码的自适应切换 + 内存账（String×N vs Hash×1）
# 对应：域4-02 核心数据结构（权威大纲 02）；参考：核心课 02/11、源码课 02-06
# 说明：所有写入在容器内逐条执行并校验元素数量（避免批量管道静默失败干扰结论）
set -u
CT=${1:-${CT:-redis-lab-7}}
R() { docker exec "$CT" redis-cli "$@"; }

echo "═══ 一、阈值配置（7.2 默认，逐项查询）═══"
for k in hash-max-listpack-entries hash-max-listpack-value set-max-intset-entries \
         set-max-listpack-entries zset-max-listpack-entries zset-max-listpack-value \
         list-max-listpack-size; do
  echo "$k = $(R config get "$k" | tail -1 | tr -d '\r')"
done

echo; echo "═══ 二、编码切换（OBJECT ENCODING + 数量校验）═══"
R flushall >/dev/null

R set s1 12345 >/dev/null
echo "String  SET s1 12345            → $(R object encoding s1 | tr -d '\r')"
R set s2 hello >/dev/null
echo "String  SET s2 hello（5B）       → $(R object encoding s2 | tr -d '\r')"
R set s3 "$(printf 'a%.0s' $(seq 1 44))" >/dev/null
echo "String  值 44 字节               → $(R object encoding s3 | tr -d '\r')"
R set s4 "$(printf 'a%.0s' $(seq 1 45))" >/dev/null
echo "String  值 45 字节               → $(R object encoding s4 | tr -d '\r')"

docker exec "$CT" sh -c '
redis-cli del h1 h2 >/dev/null
i=0; while [ $i -lt 100 ]; do redis-cli hset h1 f$i v$i >/dev/null; i=$((i+1)); done
echo "Hash    100 字段   → HLEN=$(redis-cli hlen h1) enc=$(redis-cli object encoding h1)"
i=100; while [ $i -lt 600 ]; do redis-cli hset h1 f$i v$i >/dev/null; i=$((i+1)); done
echo "Hash    600 字段   → HLEN=$(redis-cli hlen h1) enc=$(redis-cli object encoding h1)"
redis-cli hset h2 f "$(printf "x%.0s" $(seq 1 65))" >/dev/null
echo "Hash    单值 65B   → enc=$(redis-cli object encoding h2)"

redis-cli del st1 st2 >/dev/null
i=0; while [ $i -lt 512 ]; do redis-cli sadd st1 $i >/dev/null; i=$((i+1)); done
echo "Set     512 整数   → SCARD=$(redis-cli scard st1) enc=$(redis-cli object encoding st1)"
i=512; while [ $i -lt 514 ]; do redis-cli sadd st1 $i >/dev/null; i=$((i+1)); done
echo "Set     514 整数   → SCARD=$(redis-cli scard st1) enc=$(redis-cli object encoding st1)"
redis-cli sadd st2 a b c >/dev/null
echo "Set     3 个非整数 → enc=$(redis-cli object encoding st2)"

redis-cli del l1 >/dev/null
i=0; while [ $i -lt 200 ]; do redis-cli rpush l1 v$i >/dev/null; i=$((i+1)); done
echo "List    200 短元素 → LLEN=$(redis-cli llen l1) enc=$(redis-cli object encoding l1)"
i=0; while [ $i -lt 200 ]; do redis-cli rpush l1 "$(printf "y%.0s" $(seq 1 100))" >/dev/null; i=$((i+1)); done
echo "List    +200×100B  → LLEN=$(redis-cli llen l1) enc=$(redis-cli object encoding l1)"

redis-cli del z1 >/dev/null
i=0; while [ $i -lt 128 ]; do redis-cli zadd z1 $i m$i >/dev/null; i=$((i+1)); done
echo "ZSet    128 成员   → ZCARD=$(redis-cli zcard z1) enc=$(redis-cli object encoding z1)"
i=128; while [ $i -lt 130 ]; do redis-cli zadd z1 $i m$i >/dev/null; i=$((i+1)); done
echo "ZSet    130 成员   → ZCARD=$(redis-cli zcard z1) enc=$(redis-cli object encoding z1)"
'

echo; echo "═══ 三、内存账：同一份数据（100 字段）两种存法 ═══"
R flushall >/dev/null
docker exec "$CT" sh -c '
i=1; while [ $i -le 100 ]; do redis-cli set obj:f$i v$i >/dev/null; i=$((i+1)); done
sum=0; i=1; while [ $i -le 100 ]; do v=$(redis-cli memory usage obj:f$i); sum=$((sum+v)); i=$((i+1)); done
echo "独立 String ×100：合计 ${sum} B"
redis-cli flushall >/dev/null
i=1; while [ $i -le 100 ]; do redis-cli hset obj f$i v$i >/dev/null; i=$((i+1)); done
h=$(redis-cli memory usage obj)
echo "单个 Hash ×1    ：合计 ${h} B"
awk -v s="$sum" -v h="$h" "BEGIN{printf \"比值：String 方案是 Hash 方案的 %.1f 倍\\n\", s/h}"
'
