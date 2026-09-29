# Chat 侧跨系统范围选择（`context/`，Y01）

本目录承载 Chat 侧的**业务上下文**类型化模型。当前内容是多系统授权发现与范围选择：把"这次分析用哪些系统"从用户意图变成可核验事实的前端一半。

## 为什么在客户端也要有模型

范围选择是权限链路的入口，但客户端不是权限来源。以下三条在 `analysisScope.ts` 里写死，并由 `__tests__/analysisScope.test.ts` 逐条覆盖：

1. **不推断身份**：两侧 `externalUserId` 相同不代表同一个人。客户端只接受平台给出的系统标识，不提供"按同名自动选目标系统"的入口（跨系统主体联邦映射由服务端独立审批，见 `docs/adr/0051-cross-system-subject-federation.md`）。
2. **不合成系统清单**：`planScopeSelection` 只接受授权目录里出现过的系统；未知标识当场拒绝（fail closed），不把编程错误变成一次越权请求；跨系统必须包含当前系统且至少两个系统。
3. **选择可核验**：选择请求带上平台给出的 `catalogFingerprint`，结果带回 `selectionFingerprint`；核验失败（授权或映射事实变化）时丢弃选择并要求重新发现，**不会**静默改用"当前仍可访问的系统"。

## 状态与生命周期

`createAnalysisScopeMachine(api)` 用宿主注入的 `AnalysisScopeApi` 端口驱动，阶段为 `IDLE / LOADING / READY / DENIED / FAILED`：

| 状态 | 触发 | 语义 |
| --- | --- | --- |
| `READY` | 目录存在且至少一个系统 | 可以显式选择；选择/核验结果都在快照里 |
| `DENIED` | 服务端返回 `denied=true` | 没有**任何**可访问系统；与"主体未登记"不可区分（不枚举） |
| `FAILED` | 目录加载失败 | 明确失败，不猜权限、不显示过期目录 |
| `LOADING` | 加载或选择进行中 | 界面只显示进行中，不显示旧结论 |
| `IDLE` | 初始 | 尚未读取 |

`destroy()` 之后代次 +1：所有晚到的加载/选择/核验响应被丢弃（快照不写、不提示），因此销毁后的组件不会因为一个慢响应而复活并显示旧权限。

## 使用

```ts
import {
  createAnalysisScopeMachine,
  modelCatalogSystemCodes,
} from '@vben/ai-chat-ui';

const scope = createAnalysisScopeMachine({
  loadCatalog: () => hostApi.get('/app-api/ai/.../discovery/get'),
  selectScope: (input) =>
    hostApi.post('/app-api/ai/.../discovery/select', input),
  verifyScope: () =>
    hostApi.post('/app-api/ai/.../discovery/verify', selection),
});

await scope.load();
await scope.select('CROSS_SYSTEM', ['crm', 'erp']); // 目标必须来自目录
await scope.verify(); // 运行前再核验一次：事实变化即失败
```

`modelCatalogSystemCodes(modelCatalog)` 只解析平台发布的模型目录文本（解析失败返回空数组，不猜、不显示未解析出的系统），用于界面提示"模型能看到哪些系统"。
