<script lang="ts" setup>
import type { HTMLAttributes } from 'vue';

import { cn } from '@vben-core/shared/utils';

const props = defineProps<{
  class?: HTMLAttributes['class'];
}>();
</script>

<template>
  <li
    :class="
      cn('inline-flex items-center gap-1.5 hover:text-foreground', props.class)
    "
  >
    <slot></slot>
  </li>
</template>
