# 会话状态与消息组件（C02）

独立 Chat 的会话逻辑与界面：会话列表 CRUD + 发送/取消/重试 + 状态机 + 代次隔离。

## 模块

| 文件 | 职责 |
| --- | --- |
| `state.ts` | 纯状态机：等待/执行/确认/失败/完成 五态、seq 去重、代次隔离、错误只提示一次、重试复用幂等键 |
| `use-conversation.ts` | 会话逻辑 composable：列表 CRUD、发送/取消/重试；`ConversationApi`/`ConversationRunApi` 由宿主注入 |
| `ConversationPanel.vue` | 界面：会话列表（新建/重命名/删除）、消息列表、阶段徽标、发送/取消/重试 |

## 用法

```ts
import { ConversationPanel, createOpenApiClient } from '@vben/ai-chat-ui';

const runApi = createOpenApiClient({
  baseUrl: '/app-api',
  accessToken: () => ticket,
});
```

```vue
<ConversationPanel
  :api="conversationApi"
  :run-api="runApi"
  service-id="svc_9"
/>
```

## 行为约定（与验收对应）

- **连续点击不重复 run**：受理在途与执行中都拒绝第二次发送（同一运行只受理一次）。
- **取消后晚到的完成不覆盖终态**（AT-016）：取消即进入失败态；后续事件按 seq/阶段规则处理，界面不会回到执行中。
- **切用户/切会话丢弃旧响应**（AT-053）：`switchUser()` / `selectConversation()` 换代并清空；旧代次的受理结果、事件与错误一律丢弃，不会出现在新用户界面上。
- **错误只提示一次**：同一错误键只追加一个错误块；换错误键才再次提示。
- **失权提示**：服务端返回的稳定原因码（如 `AI_RUN_NOT_FOUND`、`AI_REPORT_SCOPE_CHANGED`）由错误块原样展示，界面不猜测、不伪造成功。
