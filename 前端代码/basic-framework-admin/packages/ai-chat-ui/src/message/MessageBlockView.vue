<script setup lang="ts">
/**
 * 单块判别渲染（C03）：一个 `kind` 一条渲染路径，没有"猜类型"的兜底分支。
 *
 * <p>分工：**契约校验在 blocks.ts**（未知类型在解析期就被拒），这里只面对已判别的块；
 * 而"未知类型明确降级"发生在 MessageList（它负责把解析失败转成提示），
 * 所以本组件不需要也不允许对未知数据做尽力渲染。
 *
 * <p>图表与报表用**共享组件**（ChartRenderer / AiReportView），不在消息层再写一套渲染：
 * 同一份 ChartSpec/ReportSpec 在报表页与 Chat 里的解释必须一致，否则"聊天里对、报表里错"无法排查。
 *
 * <p>副作用（确认动作、追问回答、打开报表）只往上 emit **标识与文本**，不在这里发请求：
 * 请求要带票据与幂等键，属于宿主的编排职责。
 */
import type { ChartSpec } from '@vben/ai-contracts';

import type { AttachmentApi } from '../attachment/attachment';
import type { CitationApi } from '../citation/citation';
import type { MessageBlock } from './blocks';
import type { MarkdownNode } from './markdown';

import { computed } from 'vue';

import AttachmentCard from '../attachment/AttachmentCard.vue';
import CitationCard from '../citation/CitationCard.vue';
import ChartRenderer from '../components/ChartRenderer.vue';
import AiReportView from '../report/AiReportView.vue';
import ActionCard from './ActionCard.vue';
import ClarificationCard from './ClarificationCard.vue';
import { parseMarkdown } from './markdown';
import MarkdownBlockNodes from './MarkdownBlockNodes.vue';
import ResultTable from './ResultTable.vue';

/** 宿主注入的受控读取端口（两者都可缺省：缺省时对应块退化为只读展示）。 */
export interface MessagePorts {
  attachment?: AttachmentApi;
  citation?: CitationApi;
}

const props = withDefaults(
  defineProps<{
    /** 允许的链接 Origin（空数组＝任意 http/https）。 */
    allowedOrigins?: string[];
    block: MessageBlock;
    ports?: MessagePorts;
  }>(),
  { allowedOrigins: () => [], ports: () => ({}) },
);

const emit = defineEmits<{
  answer: [value: string];
  confirm: [actionId: string];
  openReport: [reportId: string];
  reject: [actionId: string];
}>();

const markdownNodes = computed<MarkdownNode[]>(() =>
  props.block.kind === 'text'
    ? parseMarkdown(props.block.text, {
        allowedOrigins: props.allowedOrigins,
      })
    : [],
);

const chartSpec = computed<ChartSpec | null>(() =>
  props.block.kind === 'chart' ? props.block.spec : null,
);
</script>

<template>
  <section class="ai-message-block" :data-block-kind="block.kind">
    <template v-if="block.kind === 'text'">
      <MarkdownBlockNodes :nodes="markdownNodes" />
    </template>
    <template v-else-if="block.kind === 'table'">
      <ResultTable :block="block" />
    </template>
    <template v-else-if="block.kind === 'chart'">
      <ChartRenderer v-if="chartSpec" :spec="chartSpec" />
    </template>
    <template v-else-if="block.kind === 'error'">
      <p data-testid="ai-message-error" role="alert">{{ block.message }}</p>
    </template>
    <template v-else-if="block.kind === 'report'">
      <AiReportView
        v-if="block.spec"
        :data="block.data ?? null"
        :spec="block.spec"
      />
      <section v-else data-testid="ai-message-report">
        <p data-testid="ai-message-report-title">
          {{ block.title ?? block.reportId }}
        </p>
        <p data-testid="ai-message-report-version">
          {{ block.reportId }} · 版本 {{ block.version }}
        </p>
        <button
          data-testid="ai-message-report-open"
          type="button"
          @click="emit('openReport', block.reportId)"
        >
          打开报表
        </button>
      </section>
    </template>
    <template v-else-if="block.kind === 'citation'">
      <CitationCard :api="ports.citation" :citation="block" />
    </template>
    <template v-else-if="block.kind === 'file'">
      <AttachmentCard :api="ports.attachment" :file="block" />
    </template>
    <template v-else-if="block.kind === 'action'">
      <ActionCard
        :block="block"
        @confirm="emit('confirm', $event)"
        @reject="emit('reject', $event)"
      />
    </template>
    <template v-else-if="block.kind === 'clarification'">
      <ClarificationCard :block="block" @answer="emit('answer', $event)" />
    </template>
  </section>
</template>
