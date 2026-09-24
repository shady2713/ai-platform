# 引用读取（C03）

引用的展示与**受控打开**：块里只有服务端签发的标识，没有 URL。

## 模块

| 文件 | 职责 |
| --- | --- |
| `citation.ts` | `CitationApi` 端口（`readSnippet` / `readOriginal`）、标签与片段取值、受控打开流程与固定失败提示 |
| `CitationCard.vue` | 引用卡片：标题（版本 · 位置）+ 引用标识 + 片段；按端口可用性与文档编号决定是否渲染"打开原文" |

## 用法

```ts
import type { CitationApi } from '@vben/ai-chat-ui';

// 宿主实现：用当前票据请求应用端引用接口（票据只在请求头，不进 URL）
const citationApi: CitationApi = {
  readOriginal: async (documentId) => {
    const response = await fetch(
      `/app-api/ai/knowledge/document/content?documentId=${documentId}`,
      {
        headers: { Authorization: `Bearer ${ticket}` },
      },
    );
    if (!response.ok) {
      throw new Error('read original failed');
    }
    return { content: await response.text(), mimeType: 'text/plain' };
  },
  readSnippet: async (citationId) => {
    const response = await fetch(
      `/app-api/ai/knowledge/citation?citationId=${encodeURIComponent(citationId)}`,
      {
        headers: { Authorization: `Bearer ${ticket}` },
      },
    );
    if (!response.ok) {
      throw new Error('read snippet failed');
    }
    const body = (await response.json()) as { data?: string };
    return body.data ?? '';
  },
};
```

## 行为约定

- **引用不可编造**（AT-026）：`citationId` 只由服务端给出（K06 的检索候选）；本层不拼标识、不造摘要。片段缺失时界面写"请按引用位置打开原文核对"，而不是生成一段看起来像原文的文字。
- **打开即再鉴权**（AT-048）：片段与原文都走端口重新取；失败时不保留上一次的内容，也不把"曾经可读"当依据。
- **不回显异常文本**：失败只输出固定提示（原文/片段各一条），宿主异常里的请求地址与票据参数不会进界面。
- **二进制原文**：端口返回的是**文本预览**（`mimeType` 由宿主给出）；非文本内容的下载由宿主负责，本层不生成任何对象地址或直链。
