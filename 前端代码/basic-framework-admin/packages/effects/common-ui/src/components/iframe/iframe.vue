<script setup lang="ts">
import { onMounted, ref } from 'vue';

interface IFrameProps {
  /** iframe 的源地址 */
  src: string;
}

defineProps<IFrameProps>();

const loading = ref(true);
const height = ref('');

function init() {
  height.value = `${document.documentElement.clientHeight - 94.5}px`;
  loading.value = false;
}

onMounted(() => {
  setTimeout(() => {
    init();
  }, 300);
});
</script>

<template>
  <div v-loading="loading" :style="`height:${height}`">
    <iframe
      :src="src"
      class="h-full w-full"
      frameborder="no"
      scrolling="auto"
    ></iframe>
  </div>
</template>
