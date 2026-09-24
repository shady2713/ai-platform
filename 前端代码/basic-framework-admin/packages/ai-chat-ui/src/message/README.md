# 消息块与受控 Markdown（C03）

会话消息的**判别渲染层**：把服务端给的结果块按契约渲染出来，不能渲染的明确降级。

## 模块

| 文件 | 职责 |
| --- | --- |
| `blocks.ts` | 首期消息块目录与校验：冻结 v1（text/chart/error）委托 `@vben/ai-contracts`；首期新增 table/report/citation/file/action/clarification 在此逐字段校验；`toRenderableBlock(s)` 把不可渲染项转成"明确降级" |
| `markdown.ts` | 受控 Markdown → 允许节点。只认标题/段落/引用/列表/围栏代码/强调/行内代码/链接；原始 HTML 从不解析；链接在**解析期**过协议与 Origin 校验 |
| `MarkdownBlockNodes.vue` | 块级节点渲染（标题/引用/列表/代码/段落） |
| `MarkdownInlineNodes.vue` | 行内递归渲染；`<a>` 只出现在已校验的链接节点上，固定 `rel="noopener noreferrer nofollow"` |
| `ResultTable.vue` | 表格块：类型化单元格，取值原样展示（金额不做浮点再格式化），空值显示 `—` |
| `ActionCard.vue` | 待确认动作：展示工具与参数摘要、到期时间；按钮只 emit `actionId` |
| `ClarificationCard.vue` | 追问：候选项或补充说明；回答形成**新输入**，不伪装成功结果 |
| `MessageBlockView.vue` | 单块判别渲染（含共享 ChartRenderer / AiReportView 与引用、附件卡片） |
| `MessageList.vue` | 消息列表：逐块降级渲染入口，并把交互副作用往上 emit |

## 用法

```vue
<MessageList
  :messages="messages"
  :ports="{ attachment: attachmentApi, citation: citationApi }"
  @answer="(value) => conversation.send(value)"
  @confirm="(actionId) => confirmAction(actionId)"
  @open-report="(reportId) => openReport(reportId)"
  @reject="(actionId) => rejectAction(actionId)"
/>
```

`messages[].blocks` 保持 `unknown[]`：宿主可以原样透传服务端或存档数据，**校验在本层完成**。

## 契约来源与差异

- 冻结 v1：`docs/contracts/ai/result-block.schema.json`（`text`/`chart`/`error`，判别键 `kind`）——直接交给 `@vben/ai-contracts` 解析，本层不重复实现校验器。
- 平台 API 契约：`docs/ai-platform/contracts/openapi-core.json` 的 `ResultBlock` 定义了 `clarification` （question/options）与 `report`（reportId/version）。草案用 `type` 作为判别键，与冻结 v1 的 `kind` 不一致；本层统一按 `kind` 接受，`type` 形态会被**明确降级**并在提示里写出来源类型，差异记在 C03 交接记录中。
- 后端应用端 VO：引用字段对齐 `AiKnowledgeSearchRespVO.Citation`，附件字段对齐 `AiFileUploadRespVO`；新增类型并入正式 Schema 由拥有方任务（X01 等）按变更流程完成。

## 行为约定（与验收对应）

- **script/危险 URL 不执行**：Markdown 解析器不产生 HTML 节点，`javascript:`/`data:`/`file:`、协议相对地址与相对地址都不是链接（只保留标签文本）；渲染层没有 `v-html`/`innerHTML` 调用点。
- **未知类型明确降级**：未知判别键或非法取值转成 `不支持的结果类型 <来源>：<原因>`，同一条消息的其它块照常渲染。
- **引用不得编造**（AT-026）：引用标识只来自服务端；没有文档编号时不渲染"打开原文"入口。
- **失权后不可读**（AT-048）：引用片段与原文、附件预览与下载都只以**本次**响应为准；失败时清空内容并给固定提示。
- **不泄露凭据**：界面只输出固定提示，不回显宿主异常文本（异常里可能带请求地址与票据参数）。
- **报表/图表用共享组件**：`AiReportView`（R07）与 `ChartRenderer`，不在消息层再实现一套解释口径。

## 视觉

本层不带样式：颜色、间距与深浅色由主题层（C04）与宿主决定，组件只提供语义化的类名与 `data-testid`。
