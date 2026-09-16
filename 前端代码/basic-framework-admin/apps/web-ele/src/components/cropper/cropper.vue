<script lang="ts" setup>
import type { CSSProperties } from 'vue';

import type { CropendResult, CropperProps } from './typing';

import { computed, onMounted, onUnmounted, ref, useAttrs } from 'vue';

import { useDebounceFn } from '@vueuse/core';
import Cropper from 'cropperjs';

import { toCssDimension } from './cropper-utils';
import { defaultOptions } from './typing';

import 'cropperjs/dist/cropper.css';

defineOptions({ name: 'CropperImage' });

const props = withDefaults(defineProps<CropperProps>(), {
  src: '',
  alt: '',
  circled: false,
  realTimePreview: true,
  height: '360px',
  crossorigin: undefined,
  imageStyle: () => ({}),
  options: () => ({}),
});

const emit = defineEmits<{
  cropend: [result: CropendResult];
  cropendError: [error?: unknown];
  ready: [cropper: Cropper];
}>();
const attrs = useAttrs();

const imgElRef = ref<HTMLImageElement | null>(null);
const cropper = ref<Cropper | null>(null);
const isReady = ref(false);

const debounceRealTimeCropped = useDebounceFn(realTimeCropped, 80);

const getImageStyle = computed((): CSSProperties => {
  return {
    height: props.height,
    maxWidth: '100%',
    ...props.imageStyle,
  };
});

const getClass = computed(() => {
  return [
    attrs.class,
    {
      'cropper-image--circled': props.circled,
    },
  ];
});

const getWrapperStyle = computed((): CSSProperties => {
  return { height: toCssDimension(props.height) };
});

onMounted(init);

onUnmounted(() => {
  cropper.value?.destroy();
  cropper.value = null;
});

function init() {
  const imgEl = imgElRef.value;
  if (!imgEl) return;

  const {
    crop: onCrop,
    cropmove: onCropMove,
    ready: onReady,
    zoom: onZoom,
    ...options
  } = props.options;
  cropper.value = new Cropper(imgEl, {
    ...defaultOptions,
    ...options,
    ready: (event) => {
      isReady.value = true;
      realTimeCropped();
      if (cropper.value) emit('ready', cropper.value);
      onReady?.(event);
    },
    crop(event) {
      debounceRealTimeCropped();
      onCrop?.(event);
    },
    zoom(event) {
      debounceRealTimeCropped();
      onZoom?.(event);
    },
    cropmove(event) {
      debounceRealTimeCropped();
      onCropMove?.(event);
    },
  });
}

function realTimeCropped() {
  if (props.realTimePreview) cropped();
}

function cropped() {
  const instance = cropper.value;
  if (!instance) return;

  try {
    const imgInfo = instance.getData();
    const canvas = props.circled
      ? getRoundedCanvas(instance)
      : instance.getCroppedCanvas();
    if (!canvas) {
      emit('cropendError', new Error('Cropper canvas is unavailable'));
      return;
    }
    canvas.toBlob((blob) => {
      if (!blob) {
        emit('cropendError', new Error('Cropped image is empty'));
        return;
      }
      const fileReader = new FileReader();
      fileReader.addEventListener('loadend', (event) => {
        const result = event.target?.result;
        if (typeof result !== 'string') {
          emit('cropendError', new Error('Cropped image could not be read'));
          return;
        }
        emit('cropend', { imgBase64: result, imgInfo });
      });
      fileReader.addEventListener('error', () => {
        emit('cropendError', fileReader.error ?? undefined);
      });
      fileReader.readAsDataURL(blob);
    }, 'image/png');
  } catch (error) {
    emit('cropendError', error);
  }
}

function getRoundedCanvas(instance: Cropper) {
  const sourceCanvas = instance.getCroppedCanvas();
  const canvas = document.createElement('canvas');
  const context = canvas.getContext('2d');
  if (!context) return undefined;
  const width = sourceCanvas.width;
  const height = sourceCanvas.height;
  canvas.width = width;
  canvas.height = height;
  context.imageSmoothingEnabled = true;
  context.drawImage(sourceCanvas, 0, 0, width, height);
  context.globalCompositeOperation = 'destination-in';
  context.beginPath();
  context.arc(
    width / 2,
    height / 2,
    Math.min(width, height) / 2,
    0,
    2 * Math.PI,
    true,
  );
  context.fill();
  return canvas;
}
</script>

<template>
  <div :class="getClass" :style="getWrapperStyle">
    <img
      v-show="isReady"
      ref="imgElRef"
      :alt="alt"
      :crossorigin="crossorigin"
      :src="src"
      :style="getImageStyle"
      class="h-auto max-w-full"
    />
  </div>
</template>

<style lang="scss">
.cropper-image {
  &--circled {
    .cropper-view-box,
    .cropper-face {
      border-radius: 50%;
    }
  }
}
</style>
