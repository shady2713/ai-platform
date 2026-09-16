<script setup lang="ts">
import type { HTMLAttributes } from 'vue';

import type { AvatarVariants } from './avatar';

import { cn } from '@vben-core/shared/utils';

import { AvatarRoot } from 'reka-ui';

import { avatarVariant } from './avatar';

const props = withDefaults(
  defineProps<{
    class?: HTMLAttributes['class'];
    shape?: AvatarVariants['shape'];
    size?: AvatarVariants['size'];
  }>(),
  {
    shape: 'circle',
    size: 'sm',
  },
);
</script>

<template>
  <AvatarRoot :class="cn(avatarVariant({ size, shape }), props.class)">
    <slot></slot>
  </AvatarRoot>
</template>
