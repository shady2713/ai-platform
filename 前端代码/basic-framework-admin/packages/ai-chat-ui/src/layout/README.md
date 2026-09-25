# Chat 布局外壳（C07）

iframe 内部的三形态骨架：`inline`（页面里的一块）/ `drawer`（侧栏）/ `dialog`（弹窗）。

## 用法

```vue
<ChatLayout
  :mode="mode"
  :narrow-breakpoint="layout.narrowBreakpoint"
  :title="'AI 助手'"
  @close="closeSelf()"
>
  <ConversationPanel … />
</ChatLayout>
```

宿主侧的外壳（容器、overlay、焦点陷阱、Esc 关闭与焦点归还）由 `@vben/ai-embed-sdk` 的 `createChatMount` 负责；本组件只解决 **iframe 内部**的语义与窄屏布局。

## 行为约定

- **语义随形态变化**：`dialog`/`drawer` 是模态区域（`role="dialog"` + `aria-modal`，Esc 触发 `close`）； `inline` 是普通区域（`role="region"`），**不抢 Esc**，避免宿主的快捷键被吞掉。
- **窄屏可用**：宽度小于 `narrowBreakpoint`（来自主题布局令牌）时标记 `data-narrow="true"`，标题栏与关闭入口保持可点，不隐藏任何操作。
- **滚动位置保留**：滚动容器始终在 DOM 中（换形态只改语义与 class），消息列表的滚动不会被重置。
- **销毁释放**：`ResizeObserver` 在卸载时断开，宿主反复挂载不会累积监听器。
