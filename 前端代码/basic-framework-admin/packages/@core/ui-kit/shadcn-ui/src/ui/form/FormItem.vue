<script lang="ts" setup>
import type { HTMLAttributes } from 'vue';

import { provide, useId } from 'vue';

import { cn } from '@vben-core/shared/utils';

import { FORM_ITEM_INJECTION_KEY } from './injectionKeys';

const props = defineProps<{
  class?: HTMLAttributes['class'];
}>();

const id = useId() as string;
provide(FORM_ITEM_INJECTION_KEY, id);
</script>

<template>
  <div :class="cn(props.class)">
    <slot></slot>
  </div>
</template>
