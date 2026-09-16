# @vben/vsh

用于当前工程的 Shell 工具集合，主要服务于依赖检查、发布检查和工程辅助命令。

## 安装

```bash
pnpm add -D @vben/vsh
```

## 使用

```bash
pnpm vsh [command]
```

## 常用命令

- `vsh check-dep`：工作区依赖声明检查。
- `vsh check-circular`：循环依赖检查。
- `vsh publint`：包发布契约检查。
- `vsh lint`：ESLint、Stylelint 零告警与 Prettier 检查。
- `vsh lint --format`：修复可自动处理的格式问题。
