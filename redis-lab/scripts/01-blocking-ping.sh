#!/usr/bin/env bash
# 实验 01-A：单线程命令执行 —— 一条慢命令阻塞所有客户端
# 对应：域4-01 架构与线程模型（权威大纲）
# 用法: bash scripts/01-blocking-ping.sh [容器名] [循环次数]
#   默认容器 redis-lab-7（对齐 xhs 生产）；LOOPS 默认 200000000（约 3-6s，视机器）
set -u
CT=${1:-${CT:-redis-lab-7}}
LOOPS=${2:-${LOOPS:-200000000}}

ping_ms() { # 用宿主机时间量一次到容器的 PING 往返（含 docker exec 开销，基线同法可比）
  python3 - "$CT" <<'PY'
import subprocess, sys, time
t = time.time()
subprocess.run(["docker", "exec", sys.argv[1], "redis-cli", "ping"], capture_output=True)
print(int((time.time() - t) * 1000))
PY
}

echo "== 环境: 容器=$CT（记得 docker compose up -d） =="
echo "== [1] 基线 PING 往返（无阻塞） =="
base=$(ping_ms); echo "   基线: ${base}ms"

echo "== [2] 后台发起阻塞命令：EVAL 长脚本（${LOOPS} 次循环，纯 Lua 计算不占 IO） =="
t0=$(date +%s)
docker exec "$CT" redis-cli eval "local i=0 while i<${LOOPS} do i=i+1 end return i" 0 > /tmp/redis-lab-01-eval.out 2>&1 &
BG=$!
sleep 0.3

echo "== [3] 阻塞期间发起 PING（另一个"客户端"） =="
blocked=$(ping_ms); echo "   阻塞期间: ${blocked}ms"
wait "$BG"
t1=$(date +%s)
echo "== [4] 长脚本总时长: $((t1 - t0))s =="
echo
echo "结论：PING 从 ${base}ms → ${blocked}ms（≈ 脚本时长）=> 命令串行执行，一条慢命令阻塞所有客户端。"
echo "EVAL 返回值: $(cat /tmp/redis-lab-01-eval.out | tr -d '\r')"
