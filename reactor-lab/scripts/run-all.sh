#!/usr/bin/env bash
# 一键跑全部实验（16 个）
# 说明：
#   1) 必须先 compile：exec 目标【不会自动编译】，否则跑的是 target/classes 里的旧类
#   2) 用 exec:exec（fork 独立 JVM）而不是 exec:java —— 后者跑在 Maven 自己的 JVM 里，
#      Maven 的 SLF4J provider 会抢绑定，导致 reactor 的 log() 输出看不到
set -euo pipefail
cd "$(dirname "$0")/../.."   # 回到 xhs-demo（父聚合工程）
mvn -q -pl reactor-lab compile exec:exec \
  -Dexec.executable=java \
  -Dexec.args="-cp %classpath com.myxhs.demo.reactor.LabMain"
