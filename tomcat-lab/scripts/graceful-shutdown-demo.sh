#!/usr/bin/env bash
# 优雅停机复现脚本：打包 → 启动 → 发慢请求 → SIGTERM → 输出停机耗时与关键日志
#
# 用法：
#   scripts/graceful-shutdown-demo.sh [graceful|immediate] [slowSeconds]
# 例：
#   scripts/graceful-shutdown-demo.sh graceful 10   # 预期：停机耗时 ≈ 10s，请求正常返回
#   scripts/graceful-shutdown-demo.sh immediate 10  # 预期：停机耗时 < 1s，请求被硬断
set -euo pipefail

MODE="${1:-graceful}"
SLOW_SECONDS="${2:-10}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

JAVA_HOME="${JAVA_HOME:-/env/javaEnv/jdk17}"
export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"

# 本机环境可能设置了 http_proxy（curl 会把 localhost 请求也发给代理导致挂起），
# 这里强制 localhost/127.0.0.1 不走代理，并给 curl 加超时防止挂死。
export no_proxy="localhost,127.0.0.1"
export NO_PROXY="localhost,127.0.0.1"
curl() {
  command curl --max-time 5 --noproxy '*' "$@"
}

PORT=18080
LOG="/tmp/tomcat-lab-${MODE}.log"
OUT="/tmp/tomcat-lab-curl-${MODE}.out"
JAR="$ROOT/target/tomcat-lab-1.0-SNAPSHOT.jar"

echo "== 1) 打包（跳过测试）=="
(cd "$ROOT/.." && mvn -q -pl tomcat-lab -DskipTests package)

echo "== 2) 启动（server.shutdown=$MODE, port=$PORT）=="
java -jar "$JAR" --server.shutdown="$MODE" --server.port="$PORT" > "$LOG" 2>&1 &
APP_PID=$!
trap 'kill -9 "$APP_PID" 2>/dev/null || true' EXIT

echo "== 3) 等待就绪 =="
for _ in $(seq 1 60); do
  curl -sf "http://localhost:$PORT/inflight" > /dev/null 2>&1 && break
  sleep 0.5
done
if ! curl -sf "http://localhost:$PORT/inflight" > /dev/null 2>&1; then
  echo "!! 服务未就绪（端口被占用或启动失败），日志尾部："
  tail -20 "$LOG"
  exit 1
fi

echo "== 4) 后台发起 ${SLOW_SECONDS}s 慢请求 =="
# 慢请求自己要跑 SLOW_SECONDS 秒，超时要放宽（不能用默认 5s 的 curl 包装）
command curl -s --max-time $((SLOW_SECONDS + 20)) --noproxy '*' \
  "http://localhost:$PORT/slow?seconds=${SLOW_SECONDS}&tag=${MODE}" > "$OUT" 2>&1 &
CURL_PID=$!
for _ in $(seq 1 50); do
  curl -s "http://localhost:$PORT/inflight" 2>/dev/null | grep -q "inFlight=1" && break
  sleep 0.1
done
echo "   /inflight: $(curl -s "http://localhost:$PORT/inflight" 2>/dev/null || echo '(已不可达)')"

echo "== 5) 发送 SIGTERM，计时 =="
START_MS=$(date +%s%3N)
kill -TERM "$APP_PID"
wait "$APP_PID" 2>/dev/null || true
END_MS=$(date +%s%3N)
echo "   停机耗时: $((END_MS - START_MS)) ms"

echo "== 6) 在途请求结果 =="
wait "$CURL_PID" 2>/dev/null || true
if [ -s "$OUT" ]; then cat "$OUT"; else echo "(无响应体：连接被硬断)"; fi
echo

echo "== 7) 关键日志 =="
grep -E "Commencing graceful shutdown|Graceful shutdown complete|Graceful shutdown aborted|Shutdown initiated" "$LOG" || echo "(无优雅停机日志——immediate 模式属正常)"
echo "（完整日志：$LOG）"

trap - EXIT
