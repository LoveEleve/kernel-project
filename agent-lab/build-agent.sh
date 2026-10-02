#!/bin/bash
# 用法: ./build-agent.sh <Agent类名> [输出jar]
# 例:   ./build-agent.sh ParamPrintAgent paramprint.jar
# 说明: 从 target/classes 打包指定 Agent（含内部类），并自动写入 MANIFEST；
#       如果本机 Maven 仓库里有 ASM，会一起打进 jar（自带依赖，无需目标进程有 ASM）。
set -e
DIR=$(cd "$(dirname "$0")" && pwd)
AGENT_CLASS=${1:?用法: ./build-agent.sh <Agent类名> [输出jar]}
OUT=${2:-$DIR/agent.jar}
CLASSES="$DIR/target/classes"
PKG="com/myxhs/demo/agent"

[ -d "$CLASSES/$PKG" ] || { echo "!! 先编译：cd xhs-demo && mvn -q -pl agent-lab compile（或在 IDEA 里 Build）"; exit 1; }

STAGE="$DIR/.stage"
rm -rf "$STAGE"; mkdir -p "$STAGE"
cp -r "$CLASSES/com" "$STAGE/"

ASM_JAR="$HOME/.m2/repository/org/ow2/asm/asm/9.7/asm-9.7.jar"
if [ -f "$ASM_JAR" ]; then
    (cd "$STAGE" && unzip -oq "$ASM_JAR" 'org/objectweb/asm/*')
    echo "(已把 ASM 打进 jar)"
fi

printf 'Manifest-Version: 1.0\nAgent-Class: com.myxhs.demo.agent.%s\nPremain-Class: com.myxhs.demo.agent.%s\nCan-Retransform-Classes: true\nCan-Redefine-Classes: true\n\n' \
    "$AGENT_CLASS" "$AGENT_CLASS" > "$DIR/.agent.mf"

rm -f "$OUT"
jar cfm "$OUT" "$DIR/.agent.mf" -C "$STAGE" .
rm -rf "$STAGE" "$DIR/.agent.mf"

echo "== 构建完成: $OUT =="
jar tf "$OUT" | grep -v "^org/" | head -10
