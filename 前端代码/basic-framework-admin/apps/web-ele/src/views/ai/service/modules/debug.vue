<script lang="ts" setup>
/**
 * 调试区（S05）：与发布结果分开呈现——调试只返回阶段摘要、分区统计与可见输出。
 *
 * 调试必须显式给出测试主体：平台按该主体的当前授权判定发布版本绑定的资源动作，
 * 因此调试不会比测试主体看得更多；调试不回显拼装后的提示词。
 */
import type { AiServiceApi } from '#/api/ai/service';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { useVbenForm } from '#/adapter/form';
import { runServiceDebug } from '#/api/ai/service';

import { useDebugSchema } from '../data';

const service = ref<AiServiceApi.Service>();
const result = ref<AiServiceApi.DebugResult>();
const failure = ref('');

const [DebugForm, debugApi] = useVbenForm({
  commonConfig: {
    componentProps: { class: 'w-full' },
    formItemClass: 'col-span-2',
  },
  layout: 'horizontal',
  schema: useDebugSchema(),
  showDefaultActions: false,
});

async function handleRun() {
  if (!service.value) {
    return;
  }
  const { valid } = await debugApi.validate();
  if (!valid) {
    return;
  }
  const values = (await debugApi.getValues()) as {
    businessContext?: string;
    dataLevel: string;
    maxTokens?: number;
    testSubjectId?: string;
    testSubjectType: string;
    timeoutMillis?: number;
    userMessage: string;
  };
  result.value = undefined;
  failure.value = '';
  try {
    result.value = await runServiceDebug({
      businessContext: values.businessContext,
      dataLevel: values.dataLevel,
      maxTokens: values.maxTokens,
      serviceId: service.value.id,
      testSubjectId: values.testSubjectId,
      testSubjectType: values.testSubjectType,
      timeoutMillis: values.timeoutMillis,
      userMessage: values.userMessage,
    });
  } catch {
    // 失败按平台稳定错误码结束：调试区只展示"失败"，不伪造成功结果
    failure.value = '调试失败：请按错误码处理后重试（不返回假成功结果）';
  }
}

const [Modal, modalApi] = useVbenModal({
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      service.value = undefined;
      result.value = undefined;
      failure.value = '';
      return;
    }
    const row = modalApi.getData<AiServiceApi.Service>();
    service.value = row?.id ? row : undefined;
  },
});
</script>

<template>
  <Modal class="w-[860px]" title="服务调试">
    <section class="space-y-3 text-sm">
      <p class="text-xs text-muted-foreground">
        调试使用当前生效版本与显式测试主体；不读取业务资源、不回显提示词正文，也不返回隐藏推理。
      </p>
      <DebugForm />
      <button
        class="rounded bg-primary px-3 py-1 text-xs text-primary-foreground"
        type="button"
        @click="handleRun"
      >
        执行调试
      </button>

      <p v-if="failure" class="text-xs text-destructive">{{ failure }}</p>

      <div v-if="result" class="space-y-2 rounded border p-3 text-xs">
        <p data-testid="debug-release">
          使用版本 v{{ result.releaseVersion }}（releaseId={{
            result.releaseId
          }}， 端点配置版本 v{{ result.modelRevision }}）
        </p>
        <ol class="list-decimal pl-5">
          <li v-for="stage in result.stages" :key="stage.stage">
            {{ stage.stage }} · {{ stage.status }} · {{ stage.durationMs }}ms
          </li>
        </ol>
        <ul class="list-disc pl-5">
          <li v-for="section in result.sections" :key="section.section">
            {{ section.section }}：纳入 {{ section.includedCount }}、丢弃
            {{ section.droppedCount }}、估算 {{ section.estimatedTokens }} token
            <span v-if="section.sanitized">（已中和伪造的分区标记）</span>
          </li>
        </ul>
        <p>
          输入估算 {{ result.estimatedTokens }} token · 用时
          {{ result.durationMs }}ms
        </p>
        <pre class="whitespace-pre-wrap rounded bg-gray-50 p-2">{{
          result.output
        }}</pre>
      </div>
    </section>
  </Modal>
</template>
