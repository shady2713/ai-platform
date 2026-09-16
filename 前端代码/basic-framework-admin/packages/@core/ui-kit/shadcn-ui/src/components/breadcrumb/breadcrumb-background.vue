<script lang="ts" setup>
import type { BreadcrumbProps } from './types';

import { VbenIcon } from '../icon';

type Props = BreadcrumbProps;

defineOptions({ name: 'Breadcrumb' });
const { breadcrumbs, showIcon } = defineProps<Props>();

const emit = defineEmits<{ select: [string] }>();

function handleClick(path?: string) {
  if (!path) {
    return;
  }
  emit('select', path);
}
</script>
<template>
  <ul class="flex">
    <TransitionGroup name="breadcrumb-transition">
      <template
        v-for="(item, index) in breadcrumbs"
        :key="`${item.path}-${item.title}-${index}`"
      >
        <li>
          <button
            v-if="index !== breadcrumbs.length - 1"
            class="breadcrumb-node"
            type="button"
            @click.stop="handleClick(item.path)"
          >
            <span class="flex-center z-10 h-full">
              <VbenIcon
                v-if="showIcon"
                :icon="item.icon"
                class="mr-1 size-4 flex-shrink-0"
              />
              <span
                :class="{
                  'font-normal text-foreground':
                    index === breadcrumbs.length - 1,
                }"
                >{{ item.title }}
              </span>
            </span>
          </button>
          <span v-else aria-current="page" class="breadcrumb-node">
            <span class="flex-center z-10 h-full">
              <VbenIcon
                v-if="showIcon"
                :icon="item.icon"
                class="mr-1 size-4 flex-shrink-0"
              />
              <span class="font-normal text-foreground">{{ item.title }}</span>
            </span>
          </span>
        </li>
      </template>
    </TransitionGroup>
  </ul>
</template>
<style scoped>
li {
  @apply h-7;
}

.breadcrumb-node {
  @apply relative mr-9 flex h-7 items-center bg-accent py-0 pl-[5px] pr-2 text-[13px] text-muted-foreground;
}

li .breadcrumb-node > span {
  @apply -ml-3;
}

li:first-child .breadcrumb-node > span {
  @apply -ml-1;
}

li:first-child .breadcrumb-node {
  @apply rounded-[4px_0_0_4px] pl-[15px];
}

li:first-child .breadcrumb-node::before {
  @apply border-none;
}

li:last-child .breadcrumb-node {
  @apply rounded-[0_4px_4px_0] pr-[15px];
}

li:last-child .breadcrumb-node::after {
  @apply border-none;
}

li .breadcrumb-node::before,
li .breadcrumb-node::after {
  @apply absolute top-0 h-0 w-0 border-[.875rem] border-solid border-accent content-[''];
}

li .breadcrumb-node::before {
  @apply -left-7 z-10 border-l-transparent;
}

li .breadcrumb-node::after {
  @apply left-full border-transparent border-l-accent;
}

li:not(:last-child) .breadcrumb-node:hover {
  @apply bg-accent-hover;
}

li:not(:last-child) .breadcrumb-node:hover::before {
  @apply border-accent-hover border-l-transparent;
}

li:not(:last-child) .breadcrumb-node:hover::after {
  @apply border-l-accent-hover;
}
</style>
