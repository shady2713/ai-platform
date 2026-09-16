<script lang="ts" setup>
/** 文件列表图片单元格：私有图片经认证请求转为 object URL 后显示 */
import type { InfraFileApi } from '#/api/infra/file';

import { onUnmounted, ref, watch } from 'vue';

import { ElImage } from 'element-plus';

import { fetchFileObjectUrl, isPrivateFile } from '../file-access';

const props = defineProps<{ row: InfraFileApi.File }>();

const src = ref('');
let objectUrl = '';
let disposed = false;
let loadSeq = 0;

function releaseObjectUrl() {
  if (objectUrl) {
    URL.revokeObjectURL(objectUrl);
    objectUrl = '';
  }
}

async function load(row: InfraFileApi.File) {
  const seq = ++loadSeq;
  releaseObjectUrl();
  if (!isPrivateFile(row)) {
    src.value = row.url;
    return;
  }
  src.value = '';
  try {
    const url = await fetchFileObjectUrl(row);
    // 异步返回时行可能已切换或组件已卸载：只应用最新一次加载，过期 blob 立即回收
    if (disposed || seq !== loadSeq) {
      URL.revokeObjectURL(url);
      return;
    }
    objectUrl = url;
    src.value = url;
  } catch {
    // 吞掉读取失败（401/网络错误）：保持空占位，由列表刷新驱动重试
  }
}

watch(() => props.row, load, { immediate: true });

onUnmounted(() => {
  disposed = true;
  releaseObjectUrl();
});
</script>

<template>
  <ElImage v-if="src" :src="src" />
</template>
