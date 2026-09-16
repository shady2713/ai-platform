<script setup lang="ts">
import type { PrimitiveProps } from 'reka-ui';

import type { HTMLAttributes } from 'vue';

import type { ButtonVariants, ButtonVariantSize } from './types';

import { cn } from '@vben-core/shared/utils';

import { Primitive } from 'reka-ui';

import { buttonVariants } from './button';

interface Props extends PrimitiveProps {
  class?: HTMLAttributes['class'];
  size?: ButtonVariantSize;
  variant?: ButtonVariants;
}

const props = withDefaults(defineProps<Props>(), {
  as: 'button',
  class: '',
});
</script>

<template>
  <Primitive
    :as="as"
    :as-child="asChild"
    :class="cn(buttonVariants({ variant, size }), props.class)"
  >
    <slot></slot>
  </Primitive>
</template>
