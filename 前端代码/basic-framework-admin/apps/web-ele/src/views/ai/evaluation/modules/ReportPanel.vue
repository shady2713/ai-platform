<script lang="ts" setup>
import type { ReportDisplay } from '../analysis';
import type { PanelFeedback } from '../data';

/**
 * 可复现报告面板（Q05）：读取 `/ai/eval/report` 返回的 JSON 并**只读格式化展示**。
 *
 * 报告不含提示词与响应正文；展示层只做 JSON 缩进，绝不渲染 HTML（解析失败时按原文展示
 * 并明确标注，既不吞内容也不当成功）。
 */
import type { AiEvalApi } from '#/api/ai/evaluation';

import { computed, ref, watch } from 'vue';

import { ElAlert, ElButton, ElCard } from 'element-plus';

import { getReport } from '#/api/ai/evaluation';

import { reportDisplay } from '../analysis';
import { AI_EVAL_PERMISSIONS, digestText } from '../data';

const props = defineProps<{
  run?: AiEvalApi.Run;
}>();

const emit = defineEmits<{
  (e: 'clearFeedback'): void;
  (e: 'feedback', payload: PanelFeedback): void;
}>();

const report = ref<ReportDisplay>();
const reportLoading = ref(false);

const panelTitle = computed(() => {
  const run = props.run;
  return run ? `可复现报告：运行 ${run.id}` : '可复现报告：请先选择运行';
});

// 换运行就清掉上一个运行的报告，避免把旧报告当新运行的结果读
watch(
  () => props.run?.id,
  () => {
    report.value = undefined;
  },
);

async function loadReport(): Promise<void> {
  const run = props.run;
  if (run === undefined) {
    emit('feedback', {
      kind: 'error',
      message: '请先在运行列表点「查看结果」选择运行',
    });
    return;
  }
  reportLoading.value = true;
  emit('clearFeedback');
  try {
    report.value = reportDisplay(await getReport(run.id));
  } catch {
    emit('feedback', {
      kind: 'error',
      message: `读取评测报告失败：请确认当前账号有 ${AI_EVAL_PERMISSIONS.query} 权限`,
    });
  } finally {
    reportLoading.value = false;
  }
}
</script>

<template>
  <ElCard :header="panelTitle">
    <div class="mb-3 flex flex-wrap items-center gap-3">
      <ElButton
        data-testid="ai-eval-report-load"
        :disabled="run === undefined"
        :loading="reportLoading"
        @click="loadReport"
      >
        读取报告
      </ElButton>
      <span v-if="run" class="text-gray-500">
        套件摘要 {{ digestText(run.suiteDigest) }} · 报告只读展示，不做 HTML
        渲染
      </span>
    </div>
    <ElAlert
      v-if="report?.parseFailed"
      data-testid="ai-eval-report-raw"
      :closable="false"
      title="报告不是合法 JSON：按原文只读展示"
      type="warning"
    />
    <div
      v-if="report"
      class="max-h-96 overflow-auto rounded bg-gray-50 p-3 text-xs"
    >
      <pre data-testid="ai-eval-report">{{ report.text }}</pre>
    </div>
  </ElCard>
</template>
