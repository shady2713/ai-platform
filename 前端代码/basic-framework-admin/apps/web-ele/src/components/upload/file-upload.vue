<script lang="ts" setup>
import type { UploadFile } from 'element-plus';

import type { FileUploadProps, UploadModelValue } from './typing';

import { toRefs } from 'vue';

import { IconifyIcon } from '@vben/icons';
import { $t } from '@vben/locales';
import { defaultFileAccepts, openWindow } from '@vben/utils';

import { ElButton, ElUpload } from 'element-plus';

import { showErrorMessage } from '#/utils/feedback';

import { isSafeUploadUrl } from './upload-security';
import { useUploadType } from './use-upload';
import { useUploadListState } from './use-upload-list-state';

defineOptions({ name: 'FileUpload', inheritAttrs: false });

const props = withDefaults(defineProps<FileUploadProps>(), {
  accept: () => defaultFileAccepts,
  api: undefined,
  directory: undefined,
  disabled: false,
  drag: false,
  helpText: '',
  listType: 'text',
  maxNumber: 1,
  maxSize: 2,
  modelValue: undefined,
  multiple: false,
  publicRead: false,
  resultField: '',
  showDescription: false,
  value: () => [],
});
const emit = defineEmits<{
  change: [value: UploadModelValue];
  delete: [file: UploadFile];
  preview: [file: UploadFile];
  'update:modelValue': [value: UploadModelValue];
  'update:value': [value: UploadModelValue];
}>();
const { accept, helpText, maxNumber, maxSize } = toRefs(props);
const { getHelpText, getStringAccept } = useUploadType({
  acceptRef: accept,
  helpTextRef: helpText,
  maxNumberRef: maxNumber,
  maxSizeRef: maxSize,
});

function emitModelChange(value: UploadModelValue) {
  emit('update:value', value);
  emit('update:modelValue', value);
  emit('change', value);
}

const { beforeUpload, customRequest, fileList, handleExceed, handleRemove } =
  useUploadListState({
    imageOnly: false,
    logScope: 'upload:file',
    onDelete: (file) => emit('delete', file),
    onModelChange: emitModelChange,
    props,
  });

function handlePreview(file: UploadFile) {
  emit('preview', file);
  if (!file.url || !isSafeUploadUrl(file.url)) {
    showErrorMessage($t('ui.upload.previewUnavailable'));
    return;
  }
  openWindow(file.url);
}
</script>

<template>
  <div>
    <ElUpload
      v-bind="$attrs"
      v-model:file-list="fileList"
      :accept="getStringAccept"
      :before-upload="beforeUpload"
      :disabled="disabled"
      :drag="drag"
      :http-request="customRequest"
      :limit="maxNumber === Infinity ? undefined : maxNumber"
      :list-type="listType"
      :multiple="multiple"
      :on-exceed="handleExceed"
      :on-preview="handlePreview"
      :on-remove="handleRemove"
    >
      <template v-if="drag">
        <div class="upload-drag-area">
          <p class="upload-drag-icon" aria-hidden="true">
            <IconifyIcon icon="lucide:cloud-upload" :size="48" />
          </p>
          <p class="upload-drag-text">{{ $t('ui.upload.dragDescription') }}</p>
          <p class="upload-drag-hint">{{ getHelpText }}</p>
        </div>
      </template>
      <template v-else>
        <ElButton
          v-if="fileList.length < maxNumber"
          :aria-label="$t('ui.upload.upload')"
          type="primary"
        >
          <IconifyIcon
            icon="lucide:cloud-upload"
            class="mr-1"
            aria-hidden="true"
          />
          {{ $t('ui.upload.upload') }}
        </ElButton>
      </template>
    </ElUpload>
    <p
      v-if="showDescription && !drag"
      class="mt-2 text-sm text-muted-foreground"
      aria-live="polite"
    >
      {{ getHelpText }}
    </p>
  </div>
</template>

<style scoped>
.upload-drag-area {
  padding: 20px;
  text-align: center;
  background-color: var(--el-fill-color-lighter);
  border-radius: 8px;
  transition: border-color 0.3s;
}

.upload-drag-area:hover {
  border-color: var(--el-color-primary);
}

.upload-drag-icon {
  margin-bottom: 16px;
  color: var(--el-text-color-placeholder);
}

.upload-drag-text {
  margin-bottom: 8px;
  font-size: 16px;
  color: var(--el-text-color-primary);
}

.upload-drag-hint {
  font-size: 14px;
  color: var(--el-text-color-secondary);
}
</style>
