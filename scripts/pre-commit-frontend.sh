#!/bin/sh
# Runs the frontend lint pipeline (prettier + eslint + stylelint with autofix)
# on staged files under the frontend workspace, then re-stages the fixes.
# Exists because lefthook resolves its config at the git root while the
# frontend toolchain must run with the frontend dir as CWD.
set -e

FRONTEND_DIR="前端代码/basic-framework-admin"

staged=$(git -c core.quotepath=false diff --cached --name-only --diff-filter=ACMR -- "$FRONTEND_DIR")
[ -z "$staged" ] && exit 0

rel=$(printf '%s\n' "$staged" | sed "s|^$FRONTEND_DIR/||")
cd "$FRONTEND_DIR"

fmt=$(printf '%s\n' "$rel" | grep -E '\.(vue|jsx?|tsx?|scss|less|styl|html|css|md|json)$' || true)
if [ -n "$fmt" ]; then
  printf '%s\n' "$fmt" | xargs -n 20 npx prettier --cache --ignore-unknown --write
fi

eslf=$(printf '%s\n' "$rel" | grep -E '\.(vue|jsx?|tsx?)$' || true)
if [ -n "$eslf" ]; then
  printf '%s\n' "$eslf" | xargs -n 20 npx eslint --cache --no-warn-ignored --fix
fi

stlf=$(printf '%s\n' "$rel" | grep -E '\.(vue|scss|less|styl|html|css)$' || true)
if [ -n "$stlf" ]; then
  printf '%s\n' "$stlf" | xargs -n 20 npx stylelint --fix --allow-empty-input
fi

cd - >/dev/null
printf '%s\n' "$staged" | xargs -n 50 git add --
