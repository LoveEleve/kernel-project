#!/usr/bin/env bash
# CLOSE_WAIT 复现：客户端 1s 超时断开（发 FIN），服务端 10s 慢请求还在跑 →
# 服务端连接进入 CLOSE_WAIT，直到 Tomcat 写响应失败才关闭。
#
# 用法：scripts/close-wait-demo.sh [slowSeconds] [clientTimeoutSeconds]
set -euo pipefail

SLOW_SECONDS="${1:-10}"
CLIENT_TIMEOUT="${2:-1}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

JAVA_HOME="${JAVA_HOME:-/env/javaEnv/jdk17}"
export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"

export no_proxy="localhost,127.0.0.1"
export NO_PROXY="localhost,127.0.0.1"
curl() {
  command curl --max-time 5 --noproxy '*' "$@"
}

PORT=18080
LOG="/tmp/tomcat-lab-closewait.log"
JAR="$ROOT/target/tomcat-lab-1.0-SNAPSHOT.jar"

echo "== 1) 打包（跳过测试）=="
(cd "$ROOT/.." && mvn -q -pl tomcat-lab -DskipTests package)

echo "== 2) 启动（port=$PORT）=="
java -jar "$JAR" --server.port="$PORT" > "$LOG" 2>&1 &
APP_PID=$!
trap 'kill -9 "$APP_PID" 2>/dev/null || true' EXIT

for _ in $(seq 1 60); do
  curl -sf "http://localhost:$PORT/inflight" > /dev/null 2>&1 && break
  sleep 0.5
done
curl -s "http://localhost:$PORT/slow?seconds=0" > /dev/null   # 预热

show() {
  echo "---- ss CLOSE-WAIT（服务端 :$PORT）----"
  ss -tan state close-wait sport = :"$PORT" 2>/dev/null || true
  echo "数量: $(ss -tan state close-wait sport = :"$PORT" 2>/dev/null | tail -n +2 | wc -l)"
}

echo "== 3) 客户端 ${CLIENT_TIMEOUT}s 超时断开（对端 FIN）=="
# 慢请求后台跑；curl 到时自己断开（close → 内核发 FIN）
command curl -s --max-time "$CLIENT_TIMEOUT" --noproxy '*' \
  "http://localhost:$PORT/slow?seconds=${SLOW_SECONDS}&tag=closewait" > /dev/null 2>&1 || true &

sleep $((CLIENT_TIMEOUT + 1))
echo "== 4) 此刻服务端状态（慢请求还在跑，连接应是 CLOSE_WAIT）=="
show

echo "== 5) 等 ${SLOW_SECONDS}s 慢请求结束（Tomcat 写响应失败 → 关连接）=="
sleep "$SLOW_SECONDS"
show

echo "== 6) 解释 =="
cat <<'EOF'
  CLOSE_WAIT = 对端（客户端）已发 FIN，本端（Tomcat）还没 close()。
  本例根因：请求处理期间客户端超时断开；Tomcat 直到把响应写回（失败）才关闭连接。
  排查口径：ss -tan state close-wait；大量堆积先查"慢处理/客户端超时不对齐"。
  注意：CLOSE_WAIT 连接仍占用 maxConnections 名额（LimitLatch 计数在 destroySocket 才 -1）。
EOF

echo "（完整日志：$LOG）"
trap - EXIT
