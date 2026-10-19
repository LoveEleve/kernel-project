#!/usr/bin/env bash
# 实验 08：Failover 重试放大 + 业务异常不重试 + 优雅下线 + 标签路由
# 对应：Dubbo 系列第 07 篇（服务不可用怎么办）
set -u
LAB_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$LAB_DIR"
export JAVA_HOME="${JAVA_HOME:-/env/javaEnv/jdk17}"
PROV_LOG=/tmp/dubbo-lab-failover-provider.log

cleanup() {
  local PID
  PID=$(jps -l | awk '/ProviderApp/{print $1}')
  [ -n "$PID" ] && kill -9 $PID 2>/dev/null
  PID=$(jps -l | awk '/ConsumerApp/{print $1}')
  [ -n "$PID" ] && kill -9 $PID 2>/dev/null
}
cleanup
sleep 1

echo "═══ 重试放大 + 业务异常 + 优雅下线 + 标签路由 ═══"
setsid nohup mvn -q -pl lab-provider exec:exec > "$PROV_LOG" 2>&1 < /dev/null &
disown
for i in $(seq 1 60); do ss -ltn 2>/dev/null | grep -q ':20880' && break; sleep 1; done
echo "provider 就绪（${i}s，20880）"

echo
echo "--- ① 超时重试放大：timeout=1000, retries=2, provider slow(2000) ---"
timeout 90 mvn -q -pl lab-consumer exec:exec -Dlab.timeout.ref=1000 -Dlab.retries=2 -Dlab.slow.ms=2000 > /tmp/dubbo-lab-retry2.log 2>&1
grep -E "lab-consumer\] (配置|sayHello|slow)" /tmp/dubbo-lab-retry2.log
echo "provider 侧 slow 开始次数 = $(grep -c 'slow 开始' "$PROV_LOG")"

echo
echo "--- ② 对照：retries=0（同一超时参数）---"
timeout 90 mvn -q -pl lab-consumer exec:exec -Dlab.timeout.ref=1000 -Dlab.retries=0 -Dlab.slow.ms=2000 > /tmp/dubbo-lab-retry0.log 2>&1
grep "lab-consumer\] slow" /tmp/dubbo-lab-retry0.log
echo "provider 侧 slow 开始次数（累计）= $(grep -c 'slow 开始' "$PROV_LOG")"

echo
echo "--- ③ 业务异常：retries=2, sayHello(biz-error) 由 provider 抛出 ---"
timeout 90 mvn -q -pl lab-consumer exec:exec -Dlab.retries=2 -Dlab.name=biz-error > /tmp/dubbo-lab-biz.log 2>&1
grep "lab-consumer\] sayHello" /tmp/dubbo-lab-biz.log
echo "provider 侧 biz-error 收到次数 = $(grep -c 'name=biz-error' "$PROV_LOG")"

cleanup
sleep 1

echo
echo "--- ④ 优雅下线（默认 wait=10000）：调用中 SIGTERM provider ---"
setsid nohup mvn -q -pl lab-provider exec:exec > "$PROV_LOG" 2>&1 < /dev/null &
disown
for i in $(seq 1 60); do ss -ltn 2>/dev/null | grep -q ':20880' && break; sleep 1; done
echo "provider 就绪（${i}s）"

setsid nohup mvn -q -pl lab-consumer exec:exec -Dlab.slow.ms=3000 -Dlab.timeout.ref=5000 > /tmp/dubbo-lab-graceful-consumer.log 2>&1 < /dev/null &
disown
for i in $(seq 1 60); do grep -q "slow 开始" "$PROV_LOG" && break; sleep 0.25; done
echo "provider 已收到 slow（${i}×0.25s），发出 SIGTERM"
PID=$(jps -l | awk '/ProviderApp/{print $1}')
T0=$(date +%s%3N)
kill "$PID"
echo "SIGTERM -> provider(pid=$PID)"
for i in $(seq 1 40); do grep -q "lab-consumer\] slow" /tmp/dubbo-lab-graceful-consumer.log && break; sleep 0.25; done
grep "lab-consumer\] slow" /tmp/dubbo-lab-graceful-consumer.log
for i in $(seq 1 60); do jps -l | grep -q ProviderApp || break; sleep 0.25; done
T1=$(date +%s%3N)
echo "provider SIGTERM 到进程退出 = $((T1-T0))ms"
echo "provider 侧时间线："
grep -E "slow 开始|slow 结束" "$PROV_LOG" | tail -2

echo
echo "--- ⑤ 对照：wait=0（不等待排空，立即关连接）---"
setsid nohup mvn -q -pl lab-provider exec:exec -Dlab.shutdown.wait=0 > "$PROV_LOG" 2>&1 < /dev/null &
disown
for i in $(seq 1 60); do ss -ltn 2>/dev/null | grep -q ':20880' && break; sleep 1; done
echo "provider 就绪（${i}s）"

setsid nohup mvn -q -pl lab-consumer exec:exec -Dlab.slow.ms=3000 -Dlab.timeout.ref=5000 > /tmp/dubbo-lab-nowait-consumer.log 2>&1 < /dev/null &
disown
for i in $(seq 1 60); do grep -q "slow 开始" "$PROV_LOG" && break; sleep 0.25; done
echo "provider 已收到 slow（${i}×0.25s），发出 SIGTERM"
PID=$(jps -l | awk '/ProviderApp/{print $1}')
T0=$(date +%s%3N)
kill "$PID"
echo "SIGTERM -> provider(pid=$PID)"
for i in $(seq 1 40); do grep -q "lab-consumer\] slow" /tmp/dubbo-lab-nowait-consumer.log && break; sleep 0.25; done
grep "lab-consumer\] slow" /tmp/dubbo-lab-nowait-consumer.log
for i in $(seq 1 60); do jps -l | grep -q ProviderApp || break; sleep 0.25; done
T1=$(date +%s%3N)
echo "provider SIGTERM 到进程退出 = $((T1-T0))ms"
echo "provider 侧时间线："
grep -E "slow 开始|slow 结束" "$PROV_LOG" | tail -2

cleanup
sleep 1

echo
echo "--- ⑥ 标签路由：A/B 双 provider（Nacos），consumer 带 dubbo.tag=A ---"
setsid nohup mvn -q -pl lab-provider exec:exec -Dlab.registry=nacos://127.0.0.1:8848 -Dlab.port=20880 -Dlab.provider.tag=A -Dlab.provider.url.tag=A > /tmp/dubbo-lab-tag-providerA.log 2>&1 < /dev/null &
disown
setsid nohup mvn -q -pl lab-provider exec:exec -Dlab.registry=nacos://127.0.0.1:8848 -Dlab.port=20881 -Dlab.provider.tag=B -Dlab.provider.url.tag=B > /tmp/dubbo-lab-tag-providerB.log 2>&1 < /dev/null &
disown
for i in $(seq 1 60); do ss -ltn 2>/dev/null | grep -q ':20880' && ss -ltn 2>/dev/null | grep -q ':20881' && break; sleep 1; done
sleep 3
echo "双 provider 就绪（${i}s + 注册等待 3s：A=20880 / B=20881）"

timeout 90 mvn -q -pl lab-consumer exec:exec -Dlab.registry=nacos://127.0.0.1:8848 -Dlab.tag=A -Dlab.loop=4 -Dlab.interval.ms=200 > /tmp/dubbo-lab-tagA.log 2>&1
echo "consumer tag=A："
grep "lab-consumer\] call#" /tmp/dubbo-lab-tagA.log

timeout 90 mvn -q -pl lab-consumer exec:exec -Dlab.registry=nacos://127.0.0.1:8848 -Dlab.loop=2 > /tmp/dubbo-lab-untag.log 2>&1
echo "consumer 无标签（两个 provider 都带 tag）："
grep "lab-consumer\] call#" /tmp/dubbo-lab-untag.log

cleanup
sleep 1
if ss -ltn 2>/dev/null | grep -qE ':20880|:20881'; then
  echo "警告：端口未释放"
else
  echo "已清理（20880/20881 释放）"
fi
