<script setup lang="ts">
/**
 * 图片附件卡片（X02）：受控图片预览 + 图片理解 / OCR 结果引用与失败态。
 *
 * <p>三条界面约定：
 * <ol>
 *   <li><b>结果只引用私有文件标识</b>：预览字节由宿主按 `fileId` 取回（失权即失败），
 *       结果区只显示文本与 `#fileId`，界面不出现任何上游地址；</li>
 *   <li><b>失败给固定提示</b>：能力未开通按稳定原因码提示，宿主异常原文不进入界面，失败即清空上一次结果；</li>
 *   <li><b>不伪装识别质量</b>：OCR 结果固定展示页码/范围/置信度来源与"机器识别未核验"。</li>
 * </ol>
 */
import type { MultimodalAvailability } from '@vben/ai-contracts';

import type {
  PrivateImageRef,
  VisionAttachmentApi,
  VisionOk,
  VisionOutcome,
} from './vision-attachment';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { sanitizeFileName } from './attachment';
import {
  availabilityNoticeText,
  capabilityUsable,
  failureFromThrown,
  IMAGE_PREVIEW_FAILURE_MESSAGE,
  imageAttachmentSummary,
  interpretVisionResponse,
  ocrProvenanceText,
  regionText,
  usageText,
  VISION_READONLY_MESSAGE,
} from './vision-attachment';

const props = defineProps<{
  api?: VisionAttachmentApi;
  availability?: MultimodalAvailability;
  file: PrivateImageRef;
  instruction?: string;
  languageHint?: string;
}>();

const previewUrl = ref('');
const previewError = ref('');
const outcome = ref<null | VisionOutcome>(null);
const running = ref('');

const hasApi = computed(() => props.api !== undefined);
const shownName = computed(() => sanitizeFileName(props.file.name));
const summary = computed(() => imageAttachmentSummary(props.file));
const unavailableNotice = computed(() =>
  availabilityNoticeText('IMAGE_OCR', props.availability),
);
const canOcr = computed(
  () =>
    hasApi.value &&
    props.api?.ocr !== undefined &&
    capabilityUsable(props.availability),
);
const canUnderstand = computed(
  () =>
    hasApi.value &&
    props.api?.understand !== undefined &&
    capabilityUsable(props.availability),
);
const failedOutcome = computed(() =>
  outcome.value?.state === 'FAILED' ? outcome.value : null,
);
const unavailableOutcome = computed(() =>
  outcome.value?.state === 'UNAVAILABLE' ? outcome.value : null,
);
const ocrOk = computed<null | VisionOk>(() =>
  outcome.value?.state === 'OK' && outcome.value.capability === 'IMAGE_OCR'
    ? outcome.value
    : null,
);
const understandOk = computed<null | VisionOk>(() =>
  outcome.value?.state === 'OK' &&
  outcome.value.capability === 'IMAGE_UNDERSTANDING'
    ? outcome.value
    : null,
);

function revokePreview(): void {
  if (previewUrl.value !== '' && typeof URL.revokeObjectURL === 'function') {
    URL.revokeObjectURL(previewUrl.value);
  }
  previewUrl.value = '';
}

async function loadPreview(): Promise<void> {
  revokePreview();
  previewError.value = '';
  const api = props.api;
  if (api === undefined || !Number.isInteger(props.file.fileId)) {
    return;
  }
  try {
    const blob = await api.readImage(props.file.fileId);
    if (typeof URL.createObjectURL === 'function') {
      previewUrl.value = URL.createObjectURL(blob);
    }
  } catch {
    // 失权/撤回/不存在同语义：只给固定提示，不回显宿主异常文本
    previewError.value = IMAGE_PREVIEW_FAILURE_MESSAGE;
  }
}

async function run(action: 'ocr' | 'understand'): Promise<void> {
  const api = props.api;
  if (api === undefined || running.value !== '') {
    return;
  }
  running.value = action;
  revokeOutcome();
  try {
    if (action === 'ocr') {
      if (api.ocr === undefined) {
        return;
      }
      outcome.value = interpretVisionResponse(
        await api.ocr({
          image: props.file,
          languageHint: props.languageHint,
        }),
      );
      return;
    }
    if (api.understand === undefined) {
      return;
    }
    outcome.value = interpretVisionResponse(
      await api.understand({
        image: props.file,
        instruction: props.instruction ?? '描述这张图片的内容',
      }),
    );
  } catch (error: unknown) {
    // 宿主异常可能是带票据的请求错误：只映射稳定码，不解析异常文本
    outcome.value = failureFromThrown(
      action === 'ocr' ? 'IMAGE_OCR' : 'IMAGE_UNDERSTANDING',
      error,
    );
  } finally {
    running.value = '';
  }
}

function revokeOutcome(): void {
  outcome.value = null;
}

watch(() => props.file.fileId, loadPreview, { immediate: true });

onBeforeUnmount(revokePreview);
</script>

<template>
  <section class="ai-image-attachment" data-testid="ai-image-attachment">
    <p class="ai-image-attachment__name" data-testid="ai-image-name">
      {{ shownName }}
    </p>
    <p class="ai-image-attachment__summary" data-testid="ai-image-summary">
      {{ summary }}
    </p>
    <img
      v-if="previewUrl"
      :alt="shownName"
      class="ai-image-attachment__preview"
      data-testid="ai-image-preview"
      :src="previewUrl"
    />
    <p
      v-if="previewError"
      class="ai-image-attachment__error"
      data-testid="ai-image-preview-error"
      role="alert"
    >
      {{ previewError }}
    </p>
    <p
      v-if="unavailableNotice"
      class="ai-image-attachment__unavailable"
      data-testid="ai-image-unavailable"
    >
      {{ unavailableNotice }}
    </p>
    <p
      v-if="!hasApi"
      class="ai-image-attachment__notice"
      data-testid="ai-image-readonly"
    >
      {{ VISION_READONLY_MESSAGE }}
    </p>
    <p
      v-if="unavailableOutcome"
      class="ai-image-attachment__unavailable"
      data-testid="ai-image-unavailable-outcome"
    >
      {{ unavailableOutcome.message }}（{{ unavailableOutcome.errorCode }}）
    </p>
    <p
      v-if="failedOutcome"
      class="ai-image-attachment__error"
      data-testid="ai-image-failure"
      role="alert"
    >
      {{ failedOutcome.message }}
    </p>
    <div v-if="ocrOk" data-testid="ai-image-ocr-result">
      <p
        class="ai-image-attachment__provenance"
        data-testid="ai-image-ocr-provenance"
      >
        {{ ocrProvenanceText(ocrOk) }}
      </p>
      <pre class="ai-image-attachment__text" data-testid="ai-image-ocr-text">{{
        ocrOk.text
      }}</pre>
      <ul
        v-if="ocrOk.regions && ocrOk.regions.length > 0"
        data-testid="ai-image-ocr-regions"
      >
        <li
          v-for="(region, index) in ocrOk.regions"
          :key="index"
          data-testid="ai-image-ocr-region"
        >
          {{ regionText(region) }}
        </li>
      </ul>
      <p class="ai-image-attachment__usage" data-testid="ai-image-ocr-usage">
        {{ usageText(ocrOk.usage) }}
      </p>
    </div>
    <div v-if="understandOk" data-testid="ai-image-understand-result">
      <!-- 文本容器本身只用单属性：`<pre>` 是空白敏感元素，多行属性会让格式工具产生分歧 -->
      <pre class="ai-image-attachment__text">{{ understandOk.text }}</pre>
      <p
        class="ai-image-attachment__usage"
        data-testid="ai-image-understand-usage"
      >
        {{ usageText(understandOk.usage) }}
      </p>
    </div>
    <span class="ai-image-attachment__controls">
      <button
        v-if="canOcr"
        :disabled="running !== ''"
        data-testid="ai-image-ocr-action"
        type="button"
        @click="run('ocr')"
      >
        识别文字（OCR）
      </button>
      <button
        v-if="canUnderstand"
        :disabled="running !== ''"
        data-testid="ai-image-understand-action"
        type="button"
        @click="run('understand')"
      >
        理解图片
      </button>
    </span>
  </section>
</template>
