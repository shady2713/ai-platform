# basic-framework-module-ai-api

AI 模块的对外契约薄模块：只承载 CommonApi 与 DTO，不含实现，也不含任何厂商类型
（Spring AI、Qdrant SDK 等一律不允许出现在本模块）。

## 对外能力

| 契约 | 说明 |
| --- | --- |
| `AiRunCommonApi` | 按运行业务键查询运行状态；受理、事件与取消随 O 系列任务在同一接口扩展 |
| `AiRunStatusEnum` | 运行状态词汇，与开放 API 契约 `RunStatus` 一一对应（`docs/ai-platform/contracts/openapi-core.json`） |

## 使用约束

- core starter 与其它业务模块只消费本模块发布的契约，不访问 `module-ai` 的 Service、Mapper、DO。
- 新增契约类必须同步登记到 `ModuleBoundaryArchitectureTest` 的显式允许清单，
  包名含 `api` 本身不构成豁免。
- 契约方法必须定义取消、超时、资源关闭与稳定错误；本模块的 v1 为只读查询，
  扩展保持向后兼容，不新建平行契约。
- 当前仓库内暂无消费点（AI 运行服务随 O 系列落地），按 ADR 0028 的零消费者能力缝约束处理：
  契约测试钉住行为，不宣称已被生产验证。

## 构建与测试

```sh
cd 后端代码/basic-framework-boot
./mvnw -pl basic-framework-module-ai-api test
```
