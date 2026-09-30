# 实时语音会话（X05）

`RealtimePanel` + 受控状态机：实时语音会话的**界面侧**唯一入口（FR-37）。

## 模块

| 文件 | 职责 |
| --- | --- |
| `state.ts` | 状态机（加载 / 空 / 会话中 / 失败 / 失权 / 销毁）+ 本地回合栅栏 + 转写派生 + 压力/错误码映射；只依赖注入的 `RealtimeApi` 端口，不含浏览器 API |
| `RealtimePanel.vue` | 组件：五态渲染、转写（中间结果与定稿）、工具调用状态、背压提示、丢弃计数与操作按钮（开始/打断/关麦/重连/结束） |

## 用法

```vue
<script setup lang="ts">
import { createRealtimeMachine, RealtimePanel } from '@vben/ai-chat-ui';
import { realtimeApi } from './realtime-api'; // 宿主实现：带票据/登录会话的请求

const machine = createRealtimeMachine(realtimeApi);
const acceptInput = {
  endpointId: 9,
  protocol: 'WEBSOCKET',
  audioFormat: 'audio/pcm@16000:1:20',
  requestKey: 'rt_request_0001',
};
</script>

<template>
  <RealtimePanel :machine="machine" :accept-input="acceptInput" />
</template>
```

## 行为约定（与验收对应）

- **五态可测**（AT-069 的界面侧）：`LOADING` 正在建立、`EMPTY` 未开始或已结束（保留转写与结束原因）、 `FAILED` 失败（稳定文案 + 可重试）、`DENIED` 失权、`DESTROYED` 已销毁（切换用户/离开页面）。
- **打断即栅栏**：`interrupt()` 先本地推进回合；打断前发出的推流响应晚到时按旧回合丢弃并计入 `localDroppedFrames`——旧音频不会因为"响应晚到"继续进入界面。生命周期响应（查询/断线/重连/关闭） **不**套用栅栏：终态事实必须能进入界面。
- **身份不跨会话复用**：组件卸载或宿主切换用户时必须 `destroy()`（代次 +1），此后任何晚到响应都不写状态。
- **背压不静默**：界面只提示输入缓冲达到高水位（75%）；"超限结束会话"由服务端按稳定原因执行，客户端不做丢帧补偿（`AI_REALTIME_BACKPRESSURE_CONFLICT` 的文案明确写"未静默丢帧"）。
- **不猜可用**：受理输入（端点/协议/音频格式）由宿主显式给出；受理被拒绝时按稳定码进入失败/失权态，不自动改用其它协议或端点重试（ADR 0052：实时能力必须逐端点验证）。
- **无脚本渲染**：全部内容文本插值（无 `v-html`/`innerHTML`），组件测试用恶意文本夹具证明它只会成为文本。

## 边界

- 本包**不含**真实供应商适配器与媒体面实现：真实实时链路在本环境未验证（ADR 0052 未验证项），宿主实现 `RealtimeApi` 端口时只做请求转发，不在这里发明协议。
- 设备/浏览器矩阵（WebRTC 的 ICE/编解码/自动播放策略）未验证：`tests/**` 与真实网关不在本卡允许路径内。
