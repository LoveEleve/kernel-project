#!/bin/bash
# sentinel-lab 启动脚本（xhs-demo）
# 用法: ./start-sentinel-lab.sh [JVM参数...]
set -e
cd /data/workspace/source-code/xhs-demo
CP_FILE=/tmp/cp-sentinel.txt
if [ ! -f "$CP_FILE" ] || [ sentinel-lab/pom.xml -nt "$CP_FILE" ] || [ pom.xml -nt "$CP_FILE" ]; then
    mvn dependency:build-classpath -pl sentinel-lab -Dmdep.outputFile="$CP_FILE" -q
fi
setsid nohup java -cp "sentinel-lab/target/classes:$(cat "$CP_FILE")" "$@" \
    com.scalab.sentinel.SentinelLabApplication > /tmp/sentinel-lab.log 2>&1 < /dev/null &
echo "started, pid=$!"
