<script lang="ts" setup>
import type { UploadFile } from 'element-plus';

import type { FileUploadProps, UploadModelValue } from './typing';

import { ref, toRefs } from 'vue';

import { IconifyIcon } from '@vben/icons';
import { $t } from '@vben/locales';
import { defaultImageAccepts } from '@vben/utils';

import { ElDialog, ElUpload } from 'element-plus';

import { showError, showErrorMessage } from '#/utils/feedback';

import { isSafeUploadUrl } from './upload-security';
import { useUploadType } from './use-upload';
import { useUploadListState } from './use-upload-list-state';

defineOptions({ name: 'ImageUpload', inheritAttrs: false });

const props = withDefaults(defineProps<FileUploadProps>(), {
  accept: () => defaultImageAccepts,
  api: undefined,
  directory: undefined,
  disabled: false,
  drag: false,
  helpText: '',
  listType: 'picture-card',
  maxNumber: 1,
  maxSize: 2,
  modelValue: undefined,
  multiple: false,
  publicRead: false,
  resultField: '',
  showDescription: true,
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
const previewOpen = ref(false);
const previewImage = ref('');
const previewTitle = ref('');

function emitModelChange(value: UploadModelValue) {
  emit('update:value', value);
  emit('update:modelValue', value);
  emit('change', value);
}

const { beforeUpload, customRequest, fileList, handleExceed, handleRemove } =
  useUploadListState({
    imageOnly: true,
    logScope: 'upload:image',
    onDelete: (file) => emit('delete', file),
    onModelChange: emitModelChange,
    props,
  });

function readImageDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.addEventListener('load', () => {
      const result = reader.result;
      if (typeof result === 'string' && isSafeUploadUrl(result, true)) {
        resolve(result);
      } else {
        reject(new Error('无法生成安全的图片预览'));
      }
    });
    reader.addEventListener('error', () => {
      reject(reader.error ?? new Error('读取图片失败'));
    });
    reader.readAsDataURL(file);
  });
}

async function handlePreview(file: UploadFile) {
  emit('preview', file);
  try {
    let source = '';
    if (file.url && isSafeUploadUrl(file.url, true)) {
      source = file.url;
    } else if (file.raw) {
      source = await readImageDataUrl(file.raw);
    }
    if (!source) {
      showErrorMessage($t('ui.upload.previewUnavailable'));
      return;
    }
    previewImage.value = source;
    previewTitle.value = file.name || $t('ui.upload.previewTitle');
    previewOpen.value = true;
  } catch (error: unknown) {
    showError(error, $t('ui.upload.previewUnavailable'));
  }
}

function handleCancel() {
  previewOpen.value = false;
  previewImage.value = '';
  previewTitle.value = '';
}
</script>

<template>
  <div>
    <ElUpload
      v-bind="$attrs"
      v-model:file-list="fileList"
      :accept="getStringAccept"
      :before-upload="beforeUpload"
      :class="{ 'upload-limit-reached': fileList.length >= maxNumber }"
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
      <div
        class="flex flex-col items-center justify-center"
        :aria-label="$t('ui.upload.imgUpload')"
      >
        <IconifyIcon icon="lucide:cloud-upload" :size="24" aria-hidden="true" />
        <div class="mt-2">{{ $t('ui.upload.imgUpload') }}</div>
      </div>
    </ElUpload>
    <p
      v-if="showDescription"
      class="mt-2 text-sm text-muted-foreground"
      aria-live="polite"
    >
      {{ getHelpText }}
    </p>
    <ElDialog
      v-model="previewOpen"
      :title="previewTitle"
      width="min(720px, calc(100vw - 32px))"
      @close="handleCancel"
    >
      <img
        v-if="previewImage"
        :src="previewImage"
        :alt="previewTitle"
        class="w-full"
      />
    </ElDialog>
  </div>
</template>

<style scoped>
.el-upload--picture-card {
  display: flex;
  align-items: center;
  justify-content: center;
}

.upload-limit-reached :deep(.el-upload--picture-card) {
  display: none;
}
</style>
