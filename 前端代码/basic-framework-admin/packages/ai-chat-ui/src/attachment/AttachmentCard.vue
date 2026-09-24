<script setup lang="ts">
/**
 * 附件卡片（C03）：名称/大小/媒体类型 + **受控**预览与下载。
 *
 * <p>块里没有 URL（见 attachment.ts 的说明），下载与预览都只能由宿主按 `fileId` 发起；
 * 文件名只用于显示与建议下载名，先经 {@link sanitizeFileName} 剥离目录成分与控制字符。
 * 失败同样是固定提示（不回显宿主异常文本，避免把带票据的请求地址带进界面）。
 */
import type { FileBlock } from '../message/blocks';
import type { AttachmentAction, AttachmentApi } from './attachment';

import { computed, ref } from 'vue';

import {
  attachmentSummary,
  downloadAttachment,
  isPreviewable,
  readAttachment,
  sanitizeFileName,
} from './attachment';

const props = defineProps<{ api?: AttachmentApi; file: FileBlock }>();

const preview = ref('');
const error = ref('');
const notice = ref('');
const running = ref(false);

const hasApi = computed(() => props.api !== undefined);
const canPreview = computed(
  () => hasApi.value && isPreviewable(props.file.mime),
);
const shownName = computed(() => sanitizeFileName(props.file.name));

async function run(action: AttachmentAction): Promise<void> {
  const api = props.api;
  if (!api || running.value) {
    return;
  }
  running.value = true;
  error.value = '';
  notice.value = '';
  const outcome =
    action === 'read'
      ? await readAttachment(api, props.file.fileId)
      : await downloadAttachment(api, props.file);
  running.value = false;
  if (!outcome.ok) {
    // 失权/撤回：不保留上一次的内容
    preview.value = '';
    error.value = outcome.message;
    return;
  }
  if (action === 'read') {
    preview.value = outcome.content;
  } else {
    notice.value = '已按当前权限请求下载';
  }
}
</script>

<template>
  <section class="ai-attachment" data-testid="ai-message-file">
    <p class="ai-attachment__name" data-testid="ai-attachment-name">
      {{ shownName }}
    </p>
    <p class="ai-attachment__summary" data-testid="ai-attachment-summary">
      {{ attachmentSummary(file) }}
    </p>
    <p
      v-if="error"
      class="ai-attachment__error"
      data-testid="ai-attachment-error"
      role="alert"
    >
      {{ error }}
    </p>
    <p
      v-if="notice"
      class="ai-attachment__notice"
      data-testid="ai-attachment-notice"
    >
      {{ notice }}
    </p>
    <pre v-if="preview" data-testid="ai-attachment-text">{{ preview }}</pre>
    <span class="ai-attachment__controls">
      <button
        v-if="canPreview"
        :disabled="running"
        data-testid="ai-attachment-preview-action"
        type="button"
        @click="run('read')"
      >
        预览
      </button>
      <button
        v-if="hasApi"
        :disabled="running"
        data-testid="ai-attachment-download"
        type="button"
        @click="run('download')"
      >
        下载
      </button>
      <span v-if="!hasApi" data-testid="ai-attachment-readonly">
        未接入文件读取端口：仅展示附件信息
      </span>
    </span>
  </section>
</template>
