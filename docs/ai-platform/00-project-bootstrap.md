# 00 项目初始化与跨电脑迁移

本说明记录目录与基线决策。运行日志、机器信息和校验输出放仓库根`.local-state/`，不写入.harness。

本轮完成情况与未验证项见[初始化验收摘要](verification/bootstrap-summary.md)。

## 1. 工作目录

```text
项目根目录/
  .git/                           本项目新建的独立仓库
  .github/                        原框架CI配置
  .harness/                       原框架已有门禁，完整保留
  .local-state/                   本机校验输出和工具缓存，Git忽略
  后端代码/
  前端代码/
  数据库文件/
  docs/
    ai-platform/                  本产品的唯一活动方案目录
    framework-baseline.json       原框架文件与哈希清单
    ...                           原有ADR、契约、安全和开发文档
  ops/
  scripts/
  AGENTS.md
  README.md
  开发入口.md

中台-归档/                        项目外的同级目录，不属于源码仓库
  2026-09-16-初始化/              历史ZIP与已退役的初始化工具
```

这是对原框架既有Harness的迁移，未创建第二套Harness、修改门禁或降低原规则。原框架仓库的.git没有复制过来。

## 2. Git与框架更新

- 原框架commit：`23a7edb375939a84bdfd01e8d1a68e7b016aab59`。
- 本项目使用独立`main`分支；GitHub使用个人账户的私有仓库，不添加协作者。实际远程地址以`git remote -v`为准。
- 原源码通过`framework-baseline-23a7edb37593`标签保留，标签所指提交是本仓库独立创建的快照提交，不含原仓库历史。逐文件Git blob及文件模式与manifest一致；manifest中的SHA-256记录原电脑工作树字节，可能因CRLF/LF与Git规范化后的字节不同。
- 原源码标签与manifest可用于比较“原基线→新框架”和“原基线→本产品”的差异；升级时遵循[06](06-upstream-upgrade.md)。正常克隆后执行`git fetch --tags`，不得省略基线标签备份。
- 新仓库中找不到原commit是正常的；不能把本项目HEAD强行改回原框架commit。未来需要导入上游Git历史时另行设计，不假装已有共同Git祖先。
- `.gitignore`、根README、Lefthook入口及秘密检查的精确字面量登记为本次接入做了调整，新增根`.gitattributes`；前后端业务代码保持原样。原基线标签保留调整前版本，具体理由见[ADR 0048](../adr/0048-independent-ai-platform-repository.md)。

## 3. 当前文件处理

补齐缺少的2,169个框架文件；已有407个受版本管理文件与原框架一致，保留原件。复制前后均校验SHA-256，未覆盖内容不同的用户文件。

已有前端.env、个人工具配置和缓存原地保留，未读取其秘密内容；它们被Git忽略，并从迁移包排除。新电脑需要按部署文档重新配置环境。

源码仓库保留已有中文前后端目录，避免改变Maven、pnpm、Harness、CI和任务卡路径。文档统一在`docs/ai-platform`，框架公共文档仍在`docs`原位置。

根目录不存交付ZIP。历史文档ZIP、旧初始化迁移ZIP和原框架ZIP移到项目外的归档目录。它们不能代替当前Git版本；默认不提交压缩包、依赖、日志或个人配置。原框架自带的`ip2region.xdb`是运行所需资源，保留在所属模块。

## 4. 新电脑验证顺序

先按根README安装匹配的JDK、Node、pnpm和Docker。工具版本以项目权威配置为准，不能把本机检测结果当作项目的新要求。

前端冻结依赖安装完成后，在仓库根安装提交钩子。使用项目锁定的Lefthook，配置通过相对路径定位工具，不依赖原电脑的安装路径：

```sh
node 前端代码/basic-framework-admin/node_modules/lefthook/bin/index.js install
```

Windows在仓库根运行：

```powershell
& .\.harness\verify.ps1 --list
& .\.harness\verify.ps1 contracts
& .\.harness\verify.ps1 all
```

Linux在仓库根运行：

```sh
chmod +x 后端代码/basic-framework-boot/mvnw
bash scripts/doctor.sh
sh .harness/verify.sh contracts
sh .harness/verify.sh all
```

`all`会安装/构建并使用Docker，只有在开发环境准备完毕后执行。数据库初始化及账号配置按[部署说明](../deployment.md)执行；不要先导入最新SQL快照再让Flyway重复迁移。

本轮的“目录初始化完成”不等于F01全部完成：F01仍需正式的backend/frontend/lockfile/dependencies/integration结果。不得用本说明替代门禁证据。

## 5. 文档校验

校验器依赖见[verification](verification/README.md)。默认只验证文档，可在任何机器执行：

```sh
python docs/ai-platform/scripts/verify-documents.py
```

只有需要复核原始框架时，额外传`--source-root`并指向**只读原框架**。该参数是可选项；不传时明确报告未复核原框架，不尝试访问E盘。

## 6. Git迁移与打包

联网迁移：登录有权限的GitHub账户，克隆私有仓库并获取标签。随后按根README准备环境。

```sh
git clone -c core.longpaths=true https://github.com/shady2713/ai-platform.git
cd ai-platform
git fetch --tags
git show --stat framework-baseline-23a7edb37593
```

离线迁移：先提交需要带走的改动，在项目外创建Git备份；文件名应使用新日期或版本，避免覆盖旧归档。

```sh
git bundle create ../ai-platform-2026-09-16.bundle --all
git bundle verify ../ai-platform-2026-09-16.bundle
# 新电脑
git clone -c core.longpaths=true -b main /path/to/ai-platform-2026-09-16.bundle ai-platform
cd ai-platform
git fetch origin 'refs/tags/*:refs/tags/*'
git remote set-url origin https://github.com/shady2713/ai-platform.git
```

`bundle`保存已提交的分支与标签，不包含未提交文件、本机.env、外部数据库和运行数据。需要纯源码ZIP时使用`git archive --format=zip --output=../ai-platform-source.zip HEAD`；源码ZIP没有Git历史，不能代替bundle。

Windows克隆时保留上述`-c core.longpaths=true`：Java包名形成较长路径，深层目录可能导致默认Git签出失败。它只设置新仓库，不改变全局Git配置；开发目录优先使用较短路径，例如`C:/work/ai-platform`。

初始化专用`package-project.py`及测试已随历史材料移出源码仓库，避免后续误用。文档ZIP仍由`python docs/ai-platform/scripts/package-documents.py --output <项目外目标ZIP>`生成。

这些归档用于源码迁移；产品发布包仍需按Q组完成构建、安全、兼容与恢复验证。
