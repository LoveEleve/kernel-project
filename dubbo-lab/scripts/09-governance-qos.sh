#!/usr/bin/env bash
# 实验 09：RpcContext 隐式传参 + 泛化调用 + 消费方缓存 + QoS 命令 + 线程池耗尽诊断
# 对应：Dubbo 系列第 08 篇（生产治理与诊断）
set -u
LAB_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$LAB_DIR"
export JAVA_HOME="${JAVA_HOME:-/env/javaEnv/jdk17}"
PROV_LOG=/tmp/dubbo-lab-gov-provider.log

cleanup() {
  local PID
  PID=$(jps -l | awk '/ProviderApp/{print $1}')
  [ -n "$PID" ] && kill -9 $PID 2>/dev/null
  PID=$(jps -l | awk '/ConsumerApp/{print $1}')
  [ -n "$PID" ] && kill -9 $PID 2>/dev/null
}
start_provider() {
  setsid nohup mvn -q -pl lab-provider exec:exec "$@" > "$PROV_LOG" 2>&1 < /dev/null &
  disown
  for i in $(seq 1 60); do ss -ltn 2>/dev/null | grep -q ':20880' && break; sleep 1; done
  echo "provider 就绪（${i}s，20880）"
}
cleanup
sleep 1

echo "═══ 生产治理与诊断 ═══"
start_provider

echo
echo "--- ① RpcContext 隐式传参：请求附件 + 响应附件 + 调用后清理 ---"
timeout 90 mvn -q -pl lab-consumer exec:exec -Dlab.trace.id=tr-123 -Dlab.debug=true > /tmp/dubbo-lab-gov-trace.log 2>&1
grep -E "sayHello =|响应附件|sayHello 后" /tmp/dubbo-lab-gov-trace.log
echo "provider 侧："
grep "lab-trace-id" "$PROV_LOG" | tail -1

echo
echo "--- ② 泛化调用：不依赖接口类，按接口名+方法+参数类型调用 ---"
timeout 90 mvn -q -pl lab-consumer exec:exec -Dlab.generic=true > /tmp/dubbo-lab-gov-generic.log 2>&1
grep "generic \$invoke" /tmp/dubbo-lab-gov-generic.log

echo
echo "--- ③ 消费方缓存：cache=lru，同一入参调 5 次（LRU2 两级准入）---"
timeout 90 mvn -q -pl lab-consumer exec:exec -Dlab.cache=lru -Dlab.fixed.name=true -Dlab.name=cache-lab -Dlab.loop=5 > /tmp/dubbo-lab-gov-cache.log 2>&1
grep "call#" /tmp/dubbo-lab-gov-cache.log
echo "provider 侧 sayHello(cache-lab) 次数 = $(grep -c 'name=cache-lab' "$PROV_LOG")"
echo "--- 对照：不开缓存，同一入参调 5 次 ---"
timeout 90 mvn -q -pl lab-consumer exec:exec -Dlab.fixed.name=true -Dlab.name=cache-lab -Dlab.loop=5 > /tmp/dubbo-lab-gov-nocache.log 2>&1
grep "call#" /tmp/dubbo-lab-gov-nocache.log
echo "provider 侧 sayHello(cache-lab) 次数（累计）= $(grep -c 'name=cache-lab' "$PROV_LOG")"

cleanup
sleep 1

echo
echo "--- ④ QoS 单机运维命令（Nacos 模式 + QoS 打开 + executes 启用计数）---"
start_provider -Dlab.registry=nacos://127.0.0.1:8848 -Dlab.qos=true -Dlab.executes=200
sleep 1
timeout 90 mvn -q -pl lab-consumer exec:exec -Dlab.registry=nacos://127.0.0.1:8848 -Dlab.loop=2 -Dlab.interval.ms=200 > /tmp/dubbo-lab-gov-qos-consumer.log 2>&1
grep "call#" /tmp/dubbo-lab-gov-qos-consumer.log
echo "--- ls / count / help ---"
python3 tools/qos-cmd.py 22222 "ls" "count com.myxhs.dubbo.lab.api.DemoService" "help" 2>&1 | head -45
echo "--- offline 摘流量 ---"
python3 tools/qos-cmd.py 22222 "offline" 2>&1 | head -4
sleep 4
timeout 90 mvn -q -pl lab-consumer exec:exec -Dlab.registry=nacos://127.0.0.1:8848 -Dlab.check=false -Dlab.loop=1 > /tmp/dubbo-lab-gov-offline.log 2>&1
grep "lab-consumer\] sayHello" /tmp/dubbo-lab-gov-offline.log
echo "--- online 恢复 ---"
python3 tools/qos-cmd.py 22222 "online" 2>&1 | head -4
sleep 4
timeout 90 mvn -q -pl lab-consumer exec:exec -Dlab.registry=nacos://127.0.0.1:8848 -Dlab.check=false -Dlab.loop=1 > /tmp/dubbo-lab-gov-online.log 2>&1
grep "lab-consumer\] sayHello" /tmp/dubbo-lab-gov-online.log

cleanup
sleep 1

echo
echo "--- ⑤ 线程池耗尽：threads=2, queues=0，5 个并发 slow(2000) ---"
start_provider -Dlab.threads=2 -Dlab.queues=0
timeout 90 mvn -q -pl lab-consumer exec:exec -Dlab.concurrent=5 -Dlab.slow.ms=2000 > /tmp/dubbo-lab-gov-pool.log 2>&1
grep -E "concurrent#|总耗时" /tmp/dubbo-lab-gov-pool.log
echo "provider 侧 EXHAUSTED 次数 = $(grep -c 'EXHAUSTED' "$PROV_LOG")"
grep "EXHAUSTED" "$PROV_LOG" | head -1 | cut -c1-200

cleanup
sleep 1
if ss -ltn 2>/dev/null | grep -qE ':20880|:22222'; then
  echo "警告：端口未释放"
else
  echo "已清理（20880/22222 释放）"
fi
