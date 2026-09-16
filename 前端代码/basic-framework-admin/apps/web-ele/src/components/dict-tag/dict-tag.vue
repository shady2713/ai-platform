<script setup lang="ts">
import { computed } from 'vue';

import { getDictObj } from '@vben/hooks';

import { ElTag } from 'element-plus';

interface DictTagProps {
  type: string; // 字典类型
  value: boolean | number | string; // 字典值
  icon?: string; // 图标
}

type DictTagColor = 'danger' | 'info' | 'primary' | 'success' | 'warning';

const props = defineProps<DictTagProps>();

function normalizeColorType(colorType: unknown): DictTagColor {
  switch (colorType) {
    case 'danger':
    case 'info':
    case 'primary':
    case 'success':
    case 'warning': {
      return colorType;
    }
    default: {
      return 'primary';
    }
  }
}

/** 获取字典标签 */
const dictTag = computed(() => {
  const defaultDict = {
    label: '',
    colorType: 'primary' as const,
  };
  // 校验参数有效性
  if (!props.type || props.value === undefined || props.value === null) {
    return defaultDict;
  }

  // 获取字典对象
  const dict = getDictObj(props.type, String(props.value));
  if (!dict) {
    return defaultDict;
  }

  return {
    label: dict.label || '',
    colorType: normalizeColorType(dict.colorType),
  };
});
</script>

<template>
  <ElTag v-if="dictTag.label" :type="dictTag.colorType">
    {{ dictTag.label }}
  </ElTag>
</template>
