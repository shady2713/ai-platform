<script setup lang="ts">
/**
 * 图片生成/编辑卡片（X03）：任务状态、产物、取消与受控预览/下载。
 *
 * <p>三个"不"：
 * <ol>
 *   <li><b>不自己取数</b>：只渲染宿主给的 `view`（平台侧视图数据），组件内没有请求；</li>
 *   <li><b>不接触上游地址</b>：产物只认平台文件编号 `fileId`，界面上不出现任何上游 URL
 *       （连字段都没有，见 image-generation.ts）；宿主若注入 `resolveFileUrl`，
 *       卡片也**只用 fileId** 去换平台端点的展示地址用于就地预览；</li>
 *   <li><b>不编造</b>：没有进度不画进度条、尺寸/用量未知就写"未知"、失败不摆空图占位。</li>
 * </ol>
 *
 * <p>副作用只往上 emit 标识：`cancel`（无载荷）/ `download(fileId)` / `preview(fileId)`。
 * 解析失败与"解析回来时组件已销毁"都被 {@link resolvePreviewUrl} 吸收成普通返回值：
 * 界面只给固定提示（不回显含票据的异常文本），销毁后不再更新任何状态，也不留下未处理的 Promise。
 */
import type { ImageGenerationView } from './image-generation';

import { computed, onBeforeUnmount, ref } from 'vue';

import { formatSize } from '../attachment/attachment';
import {
  capabilityLabel,
  IMAGE_PREVIEW_FAILURE,
  imageDimensionText,
  imageStatusLabel,
  isImageTaskActive,
  progressPercentOf,
  resolvePreviewUrl,
  usageText,
} from './image-generation';

const props = defineProps<{
  /** 平台端点解析（宿主注入，参数只有 fileId）；缺省时预览只 emit 事件。 */
  resolveFileUrl?: (fileId: number) => Promise<null | string>;
  /** 编辑模式下源图不可读（失权/撤回）：只给固定提示，不展示产物。 */
  sourceUnavailable?: boolean;
  /** 平台侧视图数据：卡片从不自行取数。 */
  view: ImageGenerationView;
}>();

const emit = defineEmits<{
  cancel: [];
  download: [fileId: number];
  preview: [fileId: number];
}>();

/** 组件是否已销毁：销毁后一律不再更新状态（异步解析回来时尤其重要）。 */
let disposed = false;
const cancelRequested = ref(false);
const resolvingFileId = ref<null | number>(null);
const previewedFileId = ref<null | number>(null);
const previewUrl = ref('');
const previewError = ref('');

const statusLabel = computed(() => imageStatusLabel(props.view.status));
const kindLabel = computed(() => capabilityLabel(props.view.requestKind));
/** 排队/进行中才可取消；终态不给取消入口。 */
const active = computed(() => isImageTaskActive(props.view.status));
const progress = computed(() => progressPercentOf(props.view.progressPercent));

/**
 * 编辑模式下源图不可读：显式失权标记，或根本没有源图编号（两者都无法编辑）。
 * 生成模式没有源图，不受这个标记影响。
 */
const sourceBlocked = computed(
  () =>
    props.view.requestKind === 'IMAGE_EDIT' &&
    (props.sourceUnavailable === true || props.view.sourceFileId === null),
);
/** 只展示成功且可读的产物：失败/进行中/源图失权都不摆占位。 */
const showImages = computed(
  () => props.view.status === 'SUCCEEDED' && !sourceBlocked.value,
);
const images = computed(() => (showImages.value ? props.view.images : []));
/** 失败时才展示错误码，且空串不算错误码（不编造编号）。 */
const failureCode = computed(() => {
  const code = props.view.failureCode;
  return props.view.status === 'FAILED' && code !== null && code.trim() !== ''
    ? code
    : null;
});

function cancel(): void {
  if (cancelRequested.value) {
    return;
  }
  cancelRequested.value = true;
  emit('cancel');
}

function downloadImage(fileId: number): void {
  emit('download', fileId);
}

async function previewImage(fileId: number): Promise<void> {
  emit('preview', fileId);
  const resolve = props.resolveFileUrl;
  if (resolve === undefined || resolvingFileId.value !== null) {
    return;
  }
  resolvingFileId.value = fileId;
  previewedFileId.value = fileId;
  previewUrl.value = '';
  previewError.value = '';
  const outcome = await resolvePreviewUrl(resolve, fileId, () => disposed);
  if (disposed) {
    return;
  }
  resolvingFileId.value = null;
  if (outcome.ok) {
    previewUrl.value = outcome.url;
    return;
  }
  if (outcome.reason === 'FAILED') {
    previewError.value = IMAGE_PREVIEW_FAILURE;
  }
}

onBeforeUnmount(() => {
  disposed = true;
});
</script>

<template>
  <section
    class="ai-image-generation"
    data-testid="ai-message-image-generation"
    :data-request-kind="view.requestKind"
    :data-status="view.status"
  >
    <p class="ai-image-generation__kind" data-testid="ai-image-kind">
      {{ kindLabel }}
    </p>
    <p
      class="ai-image-generation__status"
      data-testid="ai-image-status"
      role="status"
    >
      {{ statusLabel }}
    </p>
    <div
      v-if="progress !== null"
      class="ai-image-generation__progress"
      data-testid="ai-image-progress"
    >
      <progress aria-label="任务进度" :max="100" :value="progress"></progress>
      <span data-testid="ai-image-progress-text">{{ progress }}%</span>
    </div>
    <p class="ai-image-generation__prompt" data-testid="ai-image-prompt">
      {{ view.prompt }}
    </p>

    <p
      v-if="view.requestKind === 'IMAGE_EDIT' && !sourceBlocked"
      class="ai-image-generation__source"
      data-testid="ai-image-source"
    >
      基于源图编辑 · 源图文件 #{{ view.sourceFileId }}
    </p>
    <p
      v-else-if="sourceBlocked"
      class="ai-image-generation__source-blocked"
      data-testid="ai-image-source-blocked"
      role="alert"
    >
      源图不可读，无法编辑
    </p>

    <template v-if="showImages">
      <ul
        v-if="images.length > 0"
        class="ai-image-generation__images"
        data-testid="ai-image-list"
      >
        <li
          v-for="image in images"
          :key="image.fileId"
          class="ai-image-generation__image"
          data-testid="ai-image-item"
        >
          <p class="ai-image-generation__meta">
            <span data-testid="ai-image-dimension">
              {{ imageDimensionText(image.width, image.height) }}
            </span>
            <span data-testid="ai-image-size">
              {{ formatSize(image.sizeBytes) }}
            </span>
            <span data-testid="ai-image-mime">{{ image.mimeType }}</span>
            <span data-testid="ai-image-file-id">文件 #{{ image.fileId }}</span>
          </p>
          <img
            v-if="previewedFileId === image.fileId && previewUrl.length > 0"
            :alt="`图片产物 文件 #${image.fileId}`"
            class="ai-image-generation__preview"
            data-testid="ai-image-preview"
            :src="previewUrl"
          />
          <p
            v-if="previewedFileId === image.fileId && previewError.length > 0"
            class="ai-image-generation__preview-error"
            data-testid="ai-image-preview-error"
            role="alert"
          >
            {{ previewError }}
          </p>
          <span class="ai-image-generation__controls">
            <button
              :disabled="resolvingFileId === image.fileId"
              data-testid="ai-image-preview-action"
              type="button"
              @click="previewImage(image.fileId)"
            >
              预览
            </button>
            <button
              data-testid="ai-image-download"
              type="button"
              @click="downloadImage(image.fileId)"
            >
              下载
            </button>
          </span>
        </li>
      </ul>
      <p v-else class="ai-image-generation__empty" data-testid="ai-image-empty">
        任务已完成，但没有返回图片产物
      </p>
    </template>

    <section
      v-if="view.status === 'FAILED'"
      class="ai-image-generation__failure"
      data-testid="ai-image-failure"
      role="alert"
    >
      <p data-testid="ai-image-failure-label">任务失败</p>
      <p v-if="failureCode" data-testid="ai-image-failure-code">
        错误码：{{ failureCode }}
      </p>
      <p data-testid="ai-image-retry-hint">可重试：重新发起即可</p>
    </section>

    <p class="ai-image-generation__usage" data-testid="ai-image-usage">
      用量：{{ usageText(view.usage) }}
    </p>

    <button
      v-if="active"
      :disabled="cancelRequested"
      class="ai-image-generation__cancel"
      data-testid="ai-image-cancel"
      type="button"
      @click="cancel"
    >
      取消任务
    </button>
    <p
      v-if="active && cancelRequested"
      class="ai-image-generation__cancel-pending"
      data-testid="ai-image-cancel-pending"
    >
      已请求取消，等待任务结束
    </p>
  </section>
</template>
