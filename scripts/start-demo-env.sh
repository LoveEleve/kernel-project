#!/usr/bin/env bash
# ============================================================================
# 重启后一键恢复【demo 环境】（网关 + 两个 provider）
#
# 前置：Nacos 容器在跑（它有 --restart unless-stopped，正常会自动起来）
#      若没起：见 docs/gateway/infra-nacos.md
#
# 用法：bash scripts/start-demo-env.sh
# ============================================================================
set -uo pipefail
cd "$(dirname "$0")/.."                     # xhs-demo

JAVA=/env/javaEnv/jdk17/bin/java
PV_JAR=demo-provider/target/demo-provider-1.0-SNAPSHOT.jar
GW_JAR=demo-gateway/target/demo-gateway-1.0-SNAPSHOT.jar

# ---------- 前置检查 ----------
echo "== 前置检查 =="
curl -s --noproxy '*' --max-time 3 http://localhost:8848/nacos/v1/console/health/readiness >/dev/null 2>&1 \
  && echo "  ✅ Nacos（docker）在跑" || { echo "  ❌ Nacos 没起 → 先跑：docker start nacos（或见 infra-nacos.md）"; exit 1; }
[ -f "$PV_JAR" ] || { echo "  ❌ 缺 $PV_JAR → mvn -pl demo-provider package -DskipTests"; exit 1; }
[ -f "$GW_JAR" ] || { echo "  ❌ 缺 $GW_JAR → mvn -pl demo-gateway package -DskipTests"; exit 1; }

# ---------- 起两个 provider（不同 metadata，供 L16 灰度/Zone 用）----------
echo
echo "== 启动 provider =="
setsid nohup "$JAVA" -jar "$PV_JAR" \
  > /tmp/opencode/pv-8081.log 2>&1 < /dev/null &
echo "  provider#1 → 8081（zone=shanghai, version=v1，用 application.yml 默认）"

setsid nohup "$JAVA" -jar "$PV_JAR" \
  --server.port=8082 \
  --spring.cloud.nacos.discovery.metadata.zone=beijing \
  --spring.cloud.nacos.discovery.metadata.version=v2 \
  > /tmp/opencode/pv-8082.log 2>&1 < /dev/null &
echo "  provider#2 → 8082（zone=beijing, version=v2）"

# ---------- 起网关 ----------
echo
echo "== 启动网关 =="
setsid nohup "$JAVA" -jar "$GW_JAR" > /tmp/opencode/gw.log 2>&1 < /dev/null &
echo "  gateway → 8080（yml 默认 demo.lb.mode=gray）"

# ---------- 等就绪 ----------
echo
echo "== 等待就绪（最多 60s）=="
for i in $(seq 1 30); do
  sleep 2
  ok=0
  curl -s --noproxy '*' --max-time 2 -o /dev/null http://localhost:8081/hello && ok=$((ok+1))
  curl -s --noproxy '*' --max-time 2 -o /dev/null http://localhost:8082/hello && ok=$((ok+1))
  code=$(curl -s --noproxy '*' --max-time 2 -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/health 2>/dev/null)
  [ "$code" = "200" ] && ok=$((ok+1))
  if [ "$ok" = "3" ]; then echo "  ✅ 三个服务全部就绪"; break; fi
done

echo
echo "== 验证 =="
echo -n "  Nacos 实例数: "
curl -s --noproxy '*' --max-time 4 "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=demo-provider" \
  | python3 -c "import sys,json;print(len(json.load(sys.stdin).get('hosts',[])))" 2>/dev/null || echo "?"
echo -n "  lb:// 请求:   "
curl -s --noproxy '*' --max-time 4 http://localhost:8080/lb/hello \
  | python3 -c "import sys,json;print('port='+str(json.load(sys.stdin)['port']))" 2>/dev/null || echo "失败"
echo
echo "（日志：/tmp/opencode/pv-8081.log / pv-8082.log / gw.log）"
