#!/usr/bin/env bash
# 模拟 K8s 资源限制对 JVM/Tomcat 的影响：同一 jar、三组 JVM 参数，对比 /jvm-info 输出。
# （本机没有 K8s，但资源限制最终就是这两组 JVM 参数：MaxRAMPercentage / ActiveProcessorCount）
#
# 用法：scripts/k8s-jvm-demo.sh
set -euo pipefail

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
LOG="/tmp/tomcat-lab-k8s-jvm.log"
JAR="$ROOT/target/tomcat-lab-1.0-SNAPSHOT.jar"

echo "== 打包（跳过测试）=="
(cd "$ROOT/.." && mvn -q -pl tomcat-lab -DskipTests package)

run_case() {
  local title="$1"; shift
  echo
  echo "== ${title} =="
  java "$@" -jar "$JAR" --server.port="$PORT" > "$LOG" 2>&1 &
  local pid=$!
  for _ in $(seq 1 60); do
    curl -sf "http://localhost:$PORT/jvm-info" > /dev/null 2>&1 && break
    sleep 0.5
  done
  curl -s "http://localhost:$PORT/jvm-info"
  echo
  kill -TERM "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  sleep 0.5
}

run_case "默认（无限制；maxMemoryMb 默认为物理内存的 1/4）"
run_case "MaxRAMPercentage=10（模拟内存限额：堆按物理内存 10% 算）" -XX:MaxRAMPercentage=10
run_case "ActiveProcessorCount=2（模拟 CPU limit=2 核）" -XX:ActiveProcessorCount=2

echo
echo "== 结论 =="
cat <<'EOF'
  · 内存：K8s 里用 -XX:MaxRAMPercentage 对齐 limits.memory，别写死 -Xmx；
  · CPU：limits.cpu 下用 -XX:ActiveProcessorCount 对齐并行度（GC/ForkJoinPool）；
  · Boot 的 docBase 临时目录在 java.io.tmpdir（只读根文件系统要挂 emptyDir 或配 server.tomcat.basedir）。
EOF
