# 附件读取（C03）

会话附件的展示与**受控下载/预览**：块里只有服务端签发的文件编号，没有 URL。

## 模块

| 文件 | 职责 |
| --- | --- |
| `attachment.ts` | `AttachmentApi` 端口（`read` / `download`）、大小与文件名处理、预览条件、受控读取流程与固定失败提示 |
| `AttachmentCard.vue` | 附件卡片：名称 + 大小 + 媒体类型；文本类给"预览"，全部给"下载" |

## 用法

```ts
import type { AttachmentApi } from '@vben/ai-chat-ui';

// 宿主实现：用当前票据请求应用端文件接口（票据只在请求头）
const attachmentApi: AttachmentApi = {
  download: async (fileId, fileName) => {
    const response = await fetch(`/app-api/ai/file/${fileId}`, {
      headers: { Authorization: `Bearer ${ticket}` },
    });
    if (!response.ok) {
      throw new Error('download failed');
    }
    const blob = await response.blob();
    const anchor = document.createElement('a');
    anchor.href = URL.createObjectURL(blob);
    anchor.download = fileName;
    anchor.click();
    URL.revokeObjectURL(anchor.href);
  },
  read: async (fileId) => {
    const response = await fetch(`/app-api/ai/file/${fileId}`, {
      headers: { Authorization: `Bearer ${ticket}` },
    });
    if (!response.ok) {
      throw new Error('read failed');
    }
    return { content: await response.text(), mimeType: 'text/plain' };
  },
};
```

## 行为约定

- **不透传上游临时 URL**：块里没有地址字段，宿主只能按 `fileId` 用当前票据重新取（设计契约 §5 的"受控下载"）。
- **失权后不可读**（AT-048）：预览与下载都以本次响应为准；失败时清空已显示内容并给固定提示。
- **文件名只用于显示与建议下载名**：先剥离目录成分与控制字符（`../`、`C:\` 等），避免被下游当路径使用。
- **大小不伪装**：非法大小显示"未知大小"而不是 0；空值不当作"没有附件"。
- **不回显异常文本**：与引用模块同一口径，失败只输出固定提示。
