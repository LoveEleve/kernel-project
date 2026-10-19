#!/bin/bash
# loadbalancer-lab 启动脚本（xhs-demo 版）
# 用法: ./start-lb-lab.sh [JVM参数...]  如 ./start-lb-lab.sh -Dscalab.lb.strategy=random
set -e
cd /data/workspace/source-code/xhs-demo
CP_FILE=/tmp/cp-lb.txt
# pom 变更触发重新生成 classpath
if [ ! -f "$CP_FILE" ] || [ loadbalancer-lab/pom.xml -nt "$CP_FILE" ] || [ pom.xml -nt "$CP_FILE" ]; then
    mvn dependency:build-classpath -pl loadbalancer-lab -Dmdep.outputFile="$CP_FILE" -q
fi
setsid nohup java -cp "/data/workspace/source-code/xhs-demo/loadbalancer-lab/target/classes:$(cat "$CP_FILE")" "$@" \
    com.scalab.loadbalancer.LoadBalancerLabApplication > /tmp/lb-lab.log 2>&1 < /dev/null &
echo "started, pid=$!"
