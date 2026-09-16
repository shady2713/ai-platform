<script lang="ts" setup>
import { Maximize, Minimize } from '@vben-core/icons';

import { useFullscreen } from '@vueuse/core';

import { VbenIconButton } from '../button';

defineOptions({ name: 'FullScreen' });

const { isFullscreen, toggle } = useFullscreen();

// 重新检查全屏状态
isFullscreen.value = !!(
  document.fullscreenElement ||
  // @ts-expect-error 兼容旧版 WebKit 的非标准全屏属性
  document.webkitFullscreenElement ||
  // @ts-expect-error 兼容旧版 Firefox 的非标准全屏属性
  document.mozFullScreenElement ||
  // @ts-expect-error 兼容旧版 IE 的非标准全屏属性
  document.msFullscreenElement
);
</script>
<template>
  <VbenIconButton
    class="hover:animate-[shrink_0.3s_ease-in-out]"
    @click="toggle"
  >
    <Minimize v-if="isFullscreen" class="size-4 text-foreground" />
    <Maximize v-else class="size-4 text-foreground" />
  </VbenIconButton>
</template>
