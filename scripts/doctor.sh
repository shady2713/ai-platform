#!/usr/bin/env bash
# 开发环境体检：对照 README「环境要求」逐项检查本机是否可开发/构建。
# 用法：scripts/doctor.sh（仓库根执行，Git Bash / Linux / macOS 均可）
# 退出码：0 全部通过；1 存在未通过项（输出已指明修复方向）。
# WARN 项不影响退出码：只提示本地 run 才需要的能力缺失。
set -u

fail=0

check() { # $1=名称 $2=状态(0=OK 1=FAIL 2=WARN) $3=描述或修复建议
  case "$2" in
    0) printf '  [OK]   %s: %s\n' "$1" "$3" ;;
    2) printf '  [WARN] %s: %s\n' "$1" "$3" ;;
    *) printf '  [FAIL] %s: %s\n' "$1" "$3"; fail=1 ;;
  esac
}

echo "== 后端工具链 =="
if command -v java >/dev/null 2>&1; then
  major=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
  # 项目字节码目标 17（maven release）；JDK 17 及以上均可构建
  if [ "${major:-0}" -ge 17 ] 2>/dev/null; then
    check "JDK" 0 "$(java -version 2>&1 | head -1)（字节码目标 17）"
  else
    check "JDK" 1 "需要 JDK >= 17，当前主版本为 ${major:-未知}"
  fi
else
  check "JDK" 1 "未找到 java，请安装 JDK 17+（Maven 由 ./mvnw 包裹，无需本机安装）"
fi

echo "== 前端工具链 =="
if command -v node >/dev/null 2>&1; then
  nodev=$(node -v | sed 's/^v//')
  node_major=$(echo "$nodev" | cut -d. -f1)
  node_minor=$(echo "$nodev" | cut -d. -f2)
  if [ "$node_major" -gt 20 ] || { [ "$node_major" -eq 20 ] && [ "$node_minor" -ge 19 ]; }; then
    check "Node.js" 0 "v$nodev"
  else
    check "Node.js" 1 "需要 >= 20.19，当前 v$nodev"
  fi
else
  check "Node.js" 1 "未找到 node，请安装 Node.js >= 20.19"
fi
if command -v pnpm >/dev/null 2>&1; then
  check "pnpm" 0 "$(pnpm -v)（版本由 corepack 对齐 packageManager 字段）"
else
  check "pnpm" 1 "未找到 pnpm，执行 corepack enable 后重试"
fi

echo "== 仓库自检 =="
[ -f 后端代码/basic-framework-boot/mvnw ] && check "后端 wrapper" 0 "mvnw 在位" || \
  check "后端 wrapper" 1 "未找到 后端代码/basic-framework-boot/mvnw，请在仓库根执行本脚本"
[ -f 前端代码/basic-framework-admin/pnpm-lock.yaml ] && check "前端锁文件" 0 "pnpm-lock.yaml 已提交" || \
  check "前端锁文件" 1 "缺少 pnpm-lock.yaml（lockfile-integrity 门禁会拦截）"

echo "== 本地服务（仅本地 run 需要，verify/CI 不依赖）=="
if command -v mysql >/dev/null 2>&1; then
  check "MySQL 客户端" 0 "$(mysql --version)"
else
  check "MySQL 客户端" 2 "未找到 mysql CLI；本地开发需 MySQL 8（可用 Docker 起），首次导入见 README bootstrap"
fi

if [ "$fail" -eq 0 ]; then
  echo "构建环境就绪，可按 README 的 bootstrap/run/verify 流程开发。"
else
  echo "存在未通过项，按上方修复建议处理后重跑本脚本。"
fi
exit "$fail"
