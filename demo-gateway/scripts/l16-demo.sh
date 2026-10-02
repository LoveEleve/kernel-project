#!/usr/bin/env bash
# ============================================================================
# L16 演示脚本：自定义负载均衡的三种模式（none / gray / zone）
#
# 作用：依次用三种模式重启网关，各发 6 次请求，打印"每次落到哪个实例"。
# 前置：① Nacos 容器在跑   ② 两个 provider 实例在跑（8081 / 8082）
#      ③ demo-gateway 已打好包
#
# 跑法：bash demo-gateway/scripts/l16-demo.sh
# ============================================================================
set -uo pipefail
cd "$(dirname "$0")/../.."            # 回到 xhs-demo

JAR=demo-gateway/target/demo-gateway-1.0-SNAPSHOT.jar
JAVA=/env/javaEnv/jdk17/bin/java
LOG=/tmp/opencode/l16-demo-gateway.log

# ---------- 前置检查 ----------
echo "== 前置检查 =="
curl -s --noproxy '*' --max-time 3 http://localhost:8848/nacos/v1/console/health/readiness >/dev/null 2>&1 \
  && echo "  ✅ Nacos（docker）在跑" || { echo "  ❌ Nacos 不在跑（见 docs/gateway/infra-nacos.md）"; exit 1; }
curl -s --noproxy '*' --max-time 3 http://localhost:8848/nacos/v1/ns/instance/list?serviceName=demo-provider \
  | python3 -c "
import sys,json
d=json.load(sys.stdin); hosts=d.get('hosts',[])
print(f'  ✅ demo-provider 实例数 = {len(hosts)}')
for h in hosts:
    m=h.get('metadata',{})
    print(f\"     {h['ip']}:{h['port']}  zone={m.get('zone')} version={m.get('version')}\")
if len(hosts) < 2: print('  ⚠️ 只有一个实例，先起第二个（见 README/文档）')
" 2>/dev/null || { echo "  ❌ 查不到实例"; exit 1; }

[ -f "$JAR" ] || { echo "  ❌ 找不到 $JAR，先 mvn -pl demo-gateway package -DskipTests"; exit 1; }

# ---------- 工具函数 ----------
stop_gateway() {
  local pid
  pid=$(ss -tlnp 2>/dev/null | grep ':8080' | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2)
  { [ -n "$pid" ] && kill -9 "$pid"; } 2>/dev/null
  sleep 2
}

start_gateway() {                     # $1 = mode
  stop_gateway
  setsid nohup "$JAVA" -jar "$JAR" "--demo.lb.mode=$1" > "$LOG" 2>&1 < /dev/null &
  disown 2>/dev/null || true          # 静音 "Killed" 噪音
  # 等【应用就绪】（不只是端口起来）：轮询 actuator/health 直到 200，最多 60s
  for _ in $(seq 1 30); do
    sleep 2
    local code
    code=$(curl -s --noproxy '*' --max-time 3 -o /dev/null -w '%{http_code}' \
           http://localhost:8080/actuator/health 2>/dev/null)
    [ "$code" = "200" ] && return 0
  done
  echo "  ❌ 网关启动超时，看 $LOG"; return 1
}

hit6() {                              # 发 6 次请求，打印落到的端口
  for i in 1 2 3 4 5 6; do
    local port
    port=$(curl -s --noproxy '*' --max-time 4 http://localhost:8080/lb/hello \
           | python3 -c "import sys,json;print(json.load(sys.stdin)['port'])" 2>/dev/null)
    printf "    第%d次 → port=%s\n" "$i" "${port:-请求失败}"
  done
}

# ---------- 三种模式 ----------
for mode in none gray zone; do
  echo
  echo "=============================================================="
  echo " 模式：demo.lb.mode = $mode"
  echo "=============================================================="
  start_gateway "$mode" || continue
  hit6
done

stop_gateway
echo
echo "（演示结束。网关已停 → 需要时用 yml 默认模式重启：java -jar $JAR）"
