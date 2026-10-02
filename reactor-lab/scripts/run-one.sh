#!/usr/bin/env bash
# 跑单个实验，例如：
#   ./scripts/run-one.sh l11.Ex01_BackpressureStrategies
#   ./scripts/run-one.sh l12.Ex03_Debug
set -euo pipefail
cd "$(dirname "$0")/../.."   # 回到 xhs-demo（父聚合工程）
MAIN="com.myxhs.demo.reactor.${1:?用法: ./scripts/run-one.sh <l11.Ex01_BackpressureStrategies>}"
mvn -q -pl reactor-lab compile exec:exec \
  -Dexec.executable=java \
  -Dexec.args="-cp %classpath $MAIN"
