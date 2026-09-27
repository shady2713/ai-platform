#!/usr/bin/env bash
#
# Q09 恢复演练（AT-030 索引快照恢复 / AT-066 索引与 DB 回退演练）
#
# 一次可复现的「备份 → 破坏 → 恢复 → 重启后业务可用性」演练：
#   1. 播种真实业务数据（应用/凭据、主体与授权、知识库文档版本与切片、报表版本、向量索引）；
#   2. 用容器内真实 mysqldump 导出整库到宿主机，记录体积与 SHA-256；
#   3. 备份完成后写入一条业务数据（RPO 探针），再删除/改写授权、文档版本、切片、报表版本并清空向量索引；
#   4. 用真实 mysql 客户端按「备份文件 → MySQL → 向量索引快照」顺序恢复；
#   5. 以新启动的应用上下文（模拟进程重启）经 A01/A03/K02/R04 真实服务链路断言
#      「权限、引用、报表、检索真的可用」，并输出 RTO/RPO 实测数字。
#
# 依赖的 Docker 环境（本机容器，非生产）：
#   - Docker daemon 可用（Testcontainers 通过 unix socket 连接；不占用宿主机固定端口，
#     容器端口由 Testcontainers 随机映射）；
#   - 本机已存在以下按 digest 固定的镜像（缺失时脚本给出 pull 命令并退出，不会静默换镜像）：
#       mysql:8.4.11@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb
#       redis:7.4.11@sha256:71da9275c5f3fcb97d0fa0c8c5b36cc995327265420f17a04bfd544f458059f7
#       qdrant/qdrant:v1.19.1@sha256:12364fe851b9f17356fc88189fc06d1b521262e04659ec7345975b00c9246a10
#   - 需要 JDK 17（默认 $HOME/.local/opt/jdk17/...，可用 JAVA_HOME 覆盖）。
#
# 用法：
#   bash scripts/drill-backup-restore.sh
#
# 退出码：0 = 演练用例全部通过且指标已提取；非 0 = 失败（不输出编造的指标）。

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR="$REPO_ROOT/后端代码/basic-framework-boot"
IT_CLASS="Q09RestoreDrillIT"
REPORT_XML="$BACKEND_DIR/basic-framework-server/target/failsafe-reports/TEST-com.basicframework.server.integration.${IT_CLASS}.xml"
LOG_FILE="${Q09_DRILL_LOG:-/tmp/q09-drill-$(date +%Y%m%d-%H%M%S).log}"

MYSQL_IMAGE="mysql:8.4.11@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb"
REDIS_IMAGE="redis:7.4.11@sha256:71da9275c5f3fcb97d0fa0c8c5b36cc995327265420f17a04bfd544f458059f7"
QDRANT_IMAGE="qdrant/qdrant:v1.19.1@sha256:12364fe851b9f17356fc88189fc06d1b521262e04659ec7345975b00c9246a10"

fail() {
    echo "[drill] 失败：$*" >&2
    exit 2
}

echo "[drill] 仓库根目录：$REPO_ROOT"
echo "[drill] 演练日志：$LOG_FILE"

# ---- 1. Docker 环境预检 ----
command -v docker >/dev/null 2>&1 || fail "未找到 docker 命令；本演练需要 Docker（Testcontainers）"
docker info >/dev/null 2>&1 || fail "Docker daemon 不可用（Testcontainers 需要 unix socket 连接）"
for image in "$MYSQL_IMAGE" "$REDIS_IMAGE" "$QDRANT_IMAGE"; do
    if ! docker image inspect "$image" >/dev/null 2>&1; then
        fail "缺少按 digest 固定的镜像 $image；请先 docker pull ${image%@*}"
    fi
done
echo "[drill] Docker 预检通过：mysql / redis / qdrant 镜像均按 digest 就位"

# ---- 2. JDK 预检 ----
if [ -z "${JAVA_HOME:-}" ]; then
    DEFAULT_JAVA_HOME="$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64"
    [ -x "$DEFAULT_JAVA_HOME/bin/java" ] || fail "未设置 JAVA_HOME，且默认 JDK17 不存在：$DEFAULT_JAVA_HOME"
    export JAVA_HOME="$DEFAULT_JAVA_HOME"
fi
echo "[drill] JAVA_HOME=$JAVA_HOME"

# ---- 3. 运行演练用例 ----
umask 022
rm -f "$REPORT_XML"
echo "[drill] 开始执行 $IT_CLASS（真实 mysqldump 备份 → 破坏 → 恢复 → 业务断言）..."
set +e
(cd "$BACKEND_DIR" && ./mvnw -o -Pintegration -pl basic-framework-server verify \
    -Dit.test="$IT_CLASS" -DfailIfNoTests=false -Djacoco.skip=true -Dspotless.check.skip=true) 2>&1 | tee "$LOG_FILE"
STATUS=${PIPESTATUS[0]}
set -e

echo
echo "[drill] Maven 退出码：$STATUS"
if [ "$STATUS" -ne 0 ] || [ ! -f "$REPORT_XML" ]; then
    echo "[drill] 演练未通过：见 $LOG_FILE 与 $REPORT_XML" >&2
    exit "$STATUS"
fi

# ---- 4. 从 failsafe 报告提取实测指标 ----
grep -q "Q09-DRILL-PHASE-2-OK" "$REPORT_XML" || fail "未找到演练完成标记 Q09-DRILL-PHASE-2-OK，不能声称演练通过"
echo
echo "===== Q09 恢复演练实测指标（来自 $REPORT_XML） ====="
grep -o 'Q09-DRILL-METRIC [a-z_]*=[^<]*' "$REPORT_XML" | sort -u
echo "===== 指标结束 ====="
echo
echo "[drill] 演练通过：备份/破坏/恢复/重启后业务断言全部成功（详见 $LOG_FILE）"
