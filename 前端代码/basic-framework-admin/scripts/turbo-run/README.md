# @vben/turbo-run

在前端 monorepo 根目录运行 `pnpm dev` 或 `pnpm preview`，交互选择包含对应脚本的工作区包。这两个入口已在根 package.json 注册，无需另行安装工具。

也可运行 `pnpm exec turbo-run <script>`。工具读取各包的 package.json，选中一个包后执行 `pnpm --filter=<包名> run <script>`；没有定义该脚本的包不会进入候选列表。
