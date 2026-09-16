import type {
  UploadFile,
  UploadRawFile,
  UploadRequestOptions,
} from 'element-plus';

import type { FileUploadProps, UploadModelValue } from './typing';

import { computed, ref, watch } from 'vue';

import { $t } from '@vben/locales';
import { logError } from '@vben/utils';

import {
  showError,
  showErrorMessage,
  showSuccessMessage,
} from '#/utils/feedback';

import {
  getUploadModelValue,
  normalizeUploadFileList,
  removeUploadFile,
  removeUploadFileByUid,
  upsertUploadedFile,
} from './upload-list';
import {
  assertUploadConfiguration,
  isAllowedUploadFile,
} from './upload-security';
import { requestUpload } from './use-upload-core';

interface UploadListStateOptions {
  imageOnly: boolean;
  logScope: 'upload:file' | 'upload:image';
  onDelete: (file: UploadFile) => void;
  onModelChange: (value: UploadModelValue) => void;
  props: Readonly<FileUploadProps>;
}

function requiredMaxNumber(props: Readonly<FileUploadProps>) {
  return props.maxNumber ?? 1;
}

function requiredMaxSize(props: Readonly<FileUploadProps>) {
  return props.maxSize ?? 2;
}

export function useUploadListState(options: UploadListStateOptions) {
  const { imageOnly, logScope, onDelete, onModelChange, props } = options;
  const pendingIdentifiers = new Set<number>();
  const currentValue = computed(() =>
    props.modelValue === undefined ? props.value : props.modelValue,
  );
  const fileList = ref<UploadFile[]>([]);

  watch(
    [() => requiredMaxNumber(props), () => requiredMaxSize(props)],
    ([maxNumber, maxSize]) => {
      assertUploadConfiguration(maxNumber, maxSize);
    },
    { immediate: true },
  );
  watch(
    currentValue,
    (value) => {
      fileList.value = normalizeUploadFileList(value, requiredMaxNumber(props));
    },
    { deep: true, immediate: true },
  );

  function commitValue() {
    onModelChange(getUploadModelValue(fileList.value, props));
  }

  function beforeUpload(file: UploadRawFile) {
    const maxNumber = requiredMaxNumber(props);
    const successfulCount = fileList.value.filter(
      (item) => item.status === 'success',
    ).length;
    if (successfulCount + pendingIdentifiers.size >= maxNumber) {
      showErrorMessage($t('ui.upload.maxNumber', [maxNumber]));
      return false;
    }
    const accept = props.accept ?? [];
    if (!isAllowedUploadFile(file, accept, imageOnly)) {
      showErrorMessage($t('ui.upload.acceptUpload', [accept]));
      return false;
    }
    const maxSize = requiredMaxSize(props);
    if (file.size / 1024 / 1024 > maxSize) {
      showErrorMessage($t('ui.upload.maxSizeMultiple', [maxSize]));
      return false;
    }
    pendingIdentifiers.add(file.uid);
    return true;
  }

  async function customRequest(uploadOptions: UploadRequestOptions) {
    const file = uploadOptions.file;
    try {
      const response = await requestUpload(props, uploadOptions);
      upsertUploadedFile(fileList.value, file, response, imageOnly);
      commitValue();
      uploadOptions.onSuccess?.(response);
      showSuccessMessage($t('ui.upload.uploadSuccess'));
    } catch (error: unknown) {
      removeUploadFileByUid(fileList.value, file.uid);
      logError(`${logScope}:request`, error);
      uploadOptions.onError?.(error as never);
      showError(error, $t('ui.upload.uploadError'));
    } finally {
      pendingIdentifiers.delete(file.uid);
    }
  }

  function handleExceed() {
    showErrorMessage($t('ui.upload.maxNumber', [requiredMaxNumber(props)]));
  }

  function handleRemove(file: UploadFile) {
    removeUploadFile(fileList.value, file);
    commitValue();
    onDelete(file);
  }

  return {
    beforeUpload,
    customRequest,
    fileList,
    handleExceed,
    handleRemove,
  };
}
