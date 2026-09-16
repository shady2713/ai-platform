<script lang="ts" setup>
import type { CSSProperties } from 'vue';

import type { CropperAvatarProps, CropperUploadSuccess } from './typing';

import { computed, ref, watch } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { $t } from '@vben/locales';

import { ElButton } from 'element-plus';

import { showSuccessMessage } from '#/utils/feedback';

import cropperModal from './cropper-modal.vue';
import { toCssDimension } from './cropper-utils';

defineOptions({ name: 'CropperAvatar' });

const props = withDefaults(defineProps<CropperAvatarProps>(), {
  width: 200,
  value: '',
  showBtn: true,
  btnProps: () => ({}),
  btnText: '',
  uploadApi: () => Promise.resolve(),
  size: 5,
});

const emit = defineEmits<{
  change: [payload: CropperUploadSuccess];
  'update:value': [value: string];
}>();

const sourceValue = ref(props.value || '');
const [CropperModal, modalApi] = useVbenModal({
  connectedComponent: cropperModal,
});

const getWidth = computed(() => toCssDimension(props.width));
const isDisabled = computed(() => Boolean(props.btnProps.disabled));

const getStyle = computed((): CSSProperties => ({ width: getWidth.value }));

const getImageWrapperStyle = computed(
  (): CSSProperties => ({ height: getWidth.value, width: getWidth.value }),
);

watch(
  () => props.value,
  (value) => {
    sourceValue.value = value || '';
  },
);

function handleUploadSuccess({ data, source }: CropperUploadSuccess) {
  sourceValue.value = source;
  emit('update:value', source);
  emit('change', { data, source });
  showSuccessMessage($t('ui.cropper.uploadSuccess'));
}

const closeModal = () => modalApi.close();
const openModal = () => {
  if (!isDisabled.value) modalApi.open();
};

defineExpose({
  closeModal,
  openModal,
});
</script>

<template>
  <div class="inline-block text-center" :style="getStyle">
    <button
      :aria-label="$t('ui.cropper.selectImage')"
      :disabled="isDisabled"
      class="group relative overflow-hidden rounded-full border border-gray-200 bg-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60"
      :style="getImageWrapperStyle"
      type="button"
      @click="openModal"
    >
      <div
        aria-hidden="true"
        class="absolute inset-0 flex items-center justify-center rounded-full bg-black bg-opacity-40 opacity-60 transition-opacity sm:opacity-0 sm:group-hover:opacity-100 sm:group-focus-visible:opacity-100"
        :style="getImageWrapperStyle"
      >
        <IconifyIcon
          icon="lucide:cloud-upload"
          class="m-auto h-1/2 w-1/2 text-gray-200"
        />
      </div>
      <img
        v-if="sourceValue"
        :src="sourceValue"
        :alt="$t('ui.cropper.preview')"
        class="h-full w-full object-cover"
      />
    </button>
    <ElButton
      v-if="showBtn"
      v-bind="btnProps"
      class="mx-auto mt-2"
      @click="openModal"
    >
      {{ btnText ? btnText : $t('ui.cropper.selectImage') }}
    </ElButton>

    <CropperModal
      :size="size"
      :src="sourceValue"
      :upload-api="uploadApi"
      @upload-success="handleUploadSuccess"
    />
  </div>
</template>
