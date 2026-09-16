<script lang="ts" setup>
import type {
  CropendResult,
  CropperModalProps,
  CropperType,
  CropperUploadError,
  CropperUploadSuccess,
} from './typing';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { $t } from '@vben/locales';
import { dataURLtoBlob } from '@vben/utils';

import { ElAvatar, ElButton, ElSpace, ElTooltip, ElUpload } from 'element-plus';

import { showWarningMessage } from '#/utils/feedback';

import { isImageFile } from './cropper-utils';
import CropperImage from './cropper.vue';

defineOptions({ name: 'CropperModal' });

const props = withDefaults(defineProps<CropperModalProps>(), {
  circled: true,
  size: 0,
  src: '',
  uploadApi: () => Promise.resolve(),
});

const emit = defineEmits<{
  uploadError: [payload: CropperUploadError];
  uploadSuccess: [payload: CropperUploadSuccess];
}>();

type ToolbarAction = 'reset' | 'rotate' | 'scaleX' | 'scaleY' | 'zoom';

let filename = 'avatar.png';
const src = ref(props.src || '');
const previewSource = ref('');
const cropper = ref<CropperType>();
const cropperKey = ref(0);
let scaleX = 1;
let scaleY = 1;

const [Modal, modalApi] = useVbenModal({
  onConfirm: handleOk,
  onOpenChange(isOpen) {
    if (isOpen) {
      src.value = props.src;
      previewSource.value = '';
      resetTransforms();
      cropperKey.value += 1;
      modalLoading(Boolean(src.value));
    } else {
      previewSource.value = '';
      cropper.value = undefined;
      modalLoading(false);
    }
  },
});

function modalLoading(loading: boolean) {
  modalApi.setState({ confirmLoading: loading, loading });
}

function handleBeforeUpload(file: File) {
  if (!isImageFile(file)) {
    reportUploadError($t('ui.cropper.invalidImageType'));
    return false;
  }
  if (props.size > 0 && file.size > 1024 * 1024 * props.size) {
    reportUploadError($t('ui.cropper.imageTooBig'));
    return false;
  }

  const reader = new FileReader();
  const previousSource = src.value;
  modalLoading(true);
  src.value = '';
  previewSource.value = '';
  reader.addEventListener('load', (e) => {
    const result = e.target?.result;
    if (typeof result !== 'string') {
      src.value = previousSource;
      modalLoading(false);
      reportUploadError($t('ui.cropper.imageReadError'));
      return;
    }
    src.value = result;
    filename = file.name;
    resetTransforms();
  });
  reader.addEventListener('error', () => {
    src.value = previousSource;
    modalLoading(false);
    reportUploadError($t('ui.cropper.imageReadError'), reader.error);
  });
  reader.readAsDataURL(file);
  return false;
}

function handleCropend({ imgBase64 }: CropendResult) {
  previewSource.value = imgBase64;
}

function handleReady(cropperInstance: CropperType) {
  cropper.value = cropperInstance;
  modalLoading(false);
}

function handlerToolbar(action: ToolbarAction, amount = 0) {
  const instance = cropper.value;
  if (!instance) return;

  switch (action) {
    case 'reset': {
      instance.reset();
      resetTransforms();
      break;
    }
    case 'rotate': {
      instance.rotate(amount);
      break;
    }
    case 'scaleX': {
      scaleX = scaleX === -1 ? 1 : -1;
      instance.scaleX(scaleX);
      break;
    }
    case 'scaleY': {
      scaleY = scaleY === -1 ? 1 : -1;
      instance.scaleY(scaleY);
      break;
    }
    case 'zoom': {
      instance.zoom(amount);
      break;
    }
    default: {
      throw new Error(`Unsupported cropper toolbar action: ${action}`);
    }
  }
}

function resetTransforms() {
  scaleX = 1;
  scaleY = 1;
}

function reportUploadError(message: string, error?: unknown) {
  showWarningMessage(message);
  emit('uploadError', { error, msg: message });
}

async function handleOk() {
  if (!previewSource.value) {
    showWarningMessage('未选择图片');
    return;
  }

  try {
    const blob = dataURLtoBlob(previewSource.value);
    modalLoading(true);
    const result = await props.uploadApi({
      file: blob,
      filename,
      name: 'file',
    });
    emit('uploadSuccess', { data: result, source: previewSource.value });
    await modalApi.close();
  } catch (error) {
    reportUploadError($t('ui.cropper.uploadError'), error);
  } finally {
    modalLoading(false);
  }
}
</script>

<template>
  <Modal
    v-bind="$attrs"
    :confirm-text="$t('ui.cropper.okText')"
    :fullscreen-button="false"
    :title="$t('ui.cropper.modalTitle')"
    class="w-[min(960px,calc(100vw-32px))]"
  >
    <div class="flex min-h-96 flex-col gap-6 md:flex-row">
      <div class="w-full md:w-3/5">
        <div
          class="relative h-[300px] bg-gradient-to-b from-neutral-50 to-neutral-200"
        >
          <CropperImage
            v-if="src"
            :key="cropperKey"
            :circled="circled"
            :src="src"
            height="300px"
            @cropend="handleCropend"
            @cropend-error="
              reportUploadError($t('ui.cropper.imageProcessError'), $event)
            "
            @ready="handleReady"
          />
        </div>

        <div class="mt-4 flex flex-wrap items-center gap-2">
          <ElUpload
            :before-upload="handleBeforeUpload"
            :file-list="[]"
            accept="image/*"
          >
            <ElTooltip
              :content="$t('ui.cropper.selectImage')"
              placement="bottom"
            >
              <ElButton
                :aria-label="$t('ui.cropper.selectImage')"
                size="small"
                type="primary"
              >
                <template #icon>
                  <div class="flex items-center justify-center">
                    <IconifyIcon icon="lucide:upload" />
                  </div>
                </template>
              </ElButton>
            </ElTooltip>
          </ElUpload>
          <ElSpace wrap>
            <ElTooltip :content="$t('ui.cropper.btn_reset')" placement="bottom">
              <ElButton
                :aria-label="$t('ui.cropper.btn_reset')"
                :disabled="!src"
                size="small"
                type="primary"
                @click="handlerToolbar('reset')"
              >
                <template #icon>
                  <div class="flex items-center justify-center">
                    <IconifyIcon icon="lucide:rotate-ccw" />
                  </div>
                </template>
              </ElButton>
            </ElTooltip>
            <ElTooltip
              :content="$t('ui.cropper.btn_rotate_left')"
              placement="bottom"
            >
              <ElButton
                :aria-label="$t('ui.cropper.btn_rotate_left')"
                :disabled="!src"
                size="small"
                type="primary"
                @click="handlerToolbar('rotate', -45)"
              >
                <template #icon>
                  <div class="flex items-center justify-center">
                    <IconifyIcon icon="ant-design:rotate-left-outlined" />
                  </div>
                </template>
              </ElButton>
            </ElTooltip>
            <ElTooltip
              :content="$t('ui.cropper.btn_rotate_right')"
              placement="bottom"
            >
              <ElButton
                :aria-label="$t('ui.cropper.btn_rotate_right')"
                :disabled="!src"
                size="small"
                type="primary"
                @click="handlerToolbar('rotate', 45)"
              >
                <template #icon>
                  <div class="flex items-center justify-center">
                    <IconifyIcon icon="ant-design:rotate-right-outlined" />
                  </div>
                </template>
              </ElButton>
            </ElTooltip>
            <ElTooltip
              :content="$t('ui.cropper.btn_scale_x')"
              placement="bottom"
            >
              <ElButton
                :aria-label="$t('ui.cropper.btn_scale_x')"
                :disabled="!src"
                size="small"
                type="primary"
                @click="handlerToolbar('scaleX')"
              >
                <template #icon>
                  <div class="flex items-center justify-center">
                    <IconifyIcon icon="vaadin:arrows-long-h" />
                  </div>
                </template>
              </ElButton>
            </ElTooltip>
            <ElTooltip
              :content="$t('ui.cropper.btn_scale_y')"
              placement="bottom"
            >
              <ElButton
                :aria-label="$t('ui.cropper.btn_scale_y')"
                :disabled="!src"
                size="small"
                type="primary"
                @click="handlerToolbar('scaleY')"
              >
                <template #icon>
                  <div class="flex items-center justify-center">
                    <IconifyIcon icon="vaadin:arrows-long-v" />
                  </div>
                </template>
              </ElButton>
            </ElTooltip>
            <ElTooltip
              :content="$t('ui.cropper.btn_zoom_in')"
              placement="bottom"
            >
              <ElButton
                :aria-label="$t('ui.cropper.btn_zoom_in')"
                :disabled="!src"
                size="small"
                type="primary"
                @click="handlerToolbar('zoom', 0.1)"
              >
                <template #icon>
                  <div class="flex items-center justify-center">
                    <IconifyIcon icon="lucide:zoom-in" />
                  </div>
                </template>
              </ElButton>
            </ElTooltip>
            <ElTooltip
              :content="$t('ui.cropper.btn_zoom_out')"
              placement="bottom"
            >
              <ElButton
                :aria-label="$t('ui.cropper.btn_zoom_out')"
                :disabled="!src"
                size="small"
                type="primary"
                @click="handlerToolbar('zoom', -0.1)"
              >
                <template #icon>
                  <div class="flex items-center justify-center">
                    <IconifyIcon icon="lucide:zoom-out" />
                  </div>
                </template>
              </ElButton>
            </ElTooltip>
          </ElSpace>
        </div>
      </div>

      <div class="w-full md:w-2/5">
        <div
          :class="circled ? 'rounded-full' : 'rounded-md'"
          class="mx-auto h-56 w-56 max-w-full overflow-hidden border border-gray-200"
        >
          <img
            v-if="previewSource"
            :alt="$t('ui.cropper.preview')"
            :src="previewSource"
            class="h-full w-full object-cover"
          />
        </div>
        <template v-if="previewSource">
          <div
            class="mt-2 flex items-center justify-around border-t border-gray-200 pt-2"
          >
            <ElAvatar :src="previewSource" size="large" />
            <ElAvatar :size="48" :src="previewSource" />
            <ElAvatar :size="64" :src="previewSource" />
            <ElAvatar :size="80" :src="previewSource" />
          </div>
        </template>
      </div>
    </div>
  </Modal>
</template>
