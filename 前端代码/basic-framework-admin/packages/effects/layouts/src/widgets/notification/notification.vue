<script lang="ts" setup>
import type { HistoryState, LocationQueryRaw } from 'vue-router';

import type { NotificationItem } from './types';

import { useRouter } from 'vue-router';

import { Bell, CircleCheckBig, CircleX, MailCheck } from '@vben/icons';
import { $t } from '@vben/locales';
import { openWindow } from '@vben/utils';

import {
  VbenButton,
  VbenIconButton,
  VbenPopover,
  VbenScrollbar,
} from '@vben-core/shadcn-ui';

import { useToggle } from '@vueuse/core';

import { resolveNavigationDestination } from '../../navigation-destination';

interface Props {
  /**
   * 显示圆点
   */
  dot?: boolean;
  /**
   * 消息列表
   */
  notifications?: NotificationItem[];
}

defineOptions({ name: 'NotificationPopup' });

withDefaults(defineProps<Props>(), {
  dot: false,
  notifications: () => [],
});

const emit = defineEmits<{
  clear: [];
  makeAll: [];
  open: [boolean];
  read: [NotificationItem];
  remove: [NotificationItem];
  viewAll: [];
}>();

const router = useRouter();
const [open, toggle] = useToggle();

function close() {
  open.value = false;
}

function handleViewAll() {
  emit('viewAll');
  close();
}

function handleMakeAll() {
  emit('makeAll');
}

function handleClear() {
  emit('clear');
}

function handleClick(item: NotificationItem) {
  if (item.link) {
    navigateTo(item.link, item.query, item.state);
  }
}

function navigateTo(
  link: string,
  query?: LocationQueryRaw,
  state?: HistoryState,
) {
  const destination = resolveNavigationDestination(link);
  if (!destination) {
    return;
  }
  if (destination.kind === 'external') {
    openWindow(destination.url);
    return;
  }
  router.push({
    path: destination.path,
    query: query ?? {},
    state,
  });
}

function handleOpen() {
  toggle();
  emit('open', open.value);
}
</script>
<template>
  <VbenPopover
    v-model:open="open"
    content-class="relative right-2 w-[360px] p-0"
  >
    <template #trigger>
      <div class="flex-center mr-2 h-full">
        <VbenIconButton
          :aria-label="$t('ui.widgets.notifications')"
          :tooltip="$t('ui.widgets.notifications')"
          class="bell-button text-foreground relative"
          @click.stop="handleOpen"
        >
          <span
            v-if="dot"
            aria-hidden="true"
            class="bg-primary absolute right-0.5 top-0.5 h-2 w-2 rounded"
          ></span>
          <Bell class="size-4" />
        </VbenIconButton>
      </div>
    </template>

    <div class="relative">
      <div class="flex items-center justify-between p-4 py-3">
        <h2 class="text-foreground">{{ $t('ui.widgets.notifications') }}</h2>
        <VbenIconButton
          :disabled="notifications.length <= 0"
          :tooltip="$t('ui.widgets.markAllAsRead')"
          @click="handleMakeAll"
        >
          <MailCheck class="size-4" />
        </VbenIconButton>
      </div>
      <VbenScrollbar v-if="notifications.length > 0">
        <ul class="!flex max-h-[360px] w-full flex-col">
          <template v-for="item in notifications" :key="item.id">
            <li
              class="hover:bg-accent border-border relative flex w-full items-center border-t"
            >
              <span
                v-if="!item.isRead"
                aria-hidden="true"
                class="bg-primary absolute right-2 top-2 h-2 w-2 rounded"
              ></span>

              <component
                :is="item.link ? 'button' : 'div'"
                v-bind="
                  item.link
                    ? { onClick: () => handleClick(item), type: 'button' }
                    : {}
                "
                class="notification-content focus-visible:ring-ring flex min-w-0 flex-1 items-start gap-5 border-0 bg-transparent px-3 py-3 text-left focus-visible:ring-2 focus-visible:ring-inset"
              >
                <span
                  class="relative flex h-10 w-10 shrink-0 overflow-hidden rounded-full"
                >
                  <img
                    :src="item.avatar"
                    alt=""
                    class="aspect-square h-full w-full object-cover"
                  />
                </span>
                <span class="flex min-w-0 flex-col gap-1 leading-none">
                  <span class="font-semibold">{{ item.title }}</span>
                  <span class="text-muted-foreground my-1 line-clamp-2 text-xs">
                    {{ item.message }}
                  </span>
                  <span class="text-muted-foreground line-clamp-2 text-xs">
                    {{ item.date }}
                  </span>
                </span>
              </component>
              <div class="mr-3 flex shrink-0 flex-col gap-2">
                <VbenIconButton
                  v-if="!item.isRead"
                  :aria-label="$t('ui.widgets.markAsRead')"
                  :tooltip="$t('ui.widgets.markAsRead')"
                  size="xs"
                  variant="ghost"
                  class="h-6 px-2"
                  @click.stop="emit('read', item)"
                >
                  <CircleCheckBig class="size-4" />
                </VbenIconButton>
                <VbenIconButton
                  v-if="item.isRead"
                  :aria-label="$t('common.delete')"
                  :tooltip="$t('common.delete')"
                  size="xs"
                  variant="ghost"
                  class="text-destructive h-6 px-2"
                  @click.stop="emit('remove', item)"
                >
                  <CircleX class="size-4" />
                </VbenIconButton>
              </div>
            </li>
          </template>
        </ul>
      </VbenScrollbar>

      <template v-else>
        <div class="flex-center text-muted-foreground min-h-[150px] w-full">
          {{ $t('common.noData') }}
        </div>
      </template>

      <div
        class="border-border flex items-center justify-between border-t px-4 py-3"
      >
        <VbenButton
          :disabled="notifications.length <= 0"
          size="sm"
          variant="ghost"
          @click="handleClear"
        >
          {{ $t('ui.widgets.clearNotifications') }}
        </VbenButton>
        <VbenButton size="sm" @click="handleViewAll">
          {{ $t('ui.widgets.viewAll') }}
        </VbenButton>
      </div>
    </div>
  </VbenPopover>
</template>

<style scoped>
:deep(.bell-button) {
  &:hover {
    svg {
      animation: bell-ring 1s both;
    }
  }
}

@keyframes bell-ring {
  0%,
  100% {
    transform-origin: top;
  }

  15% {
    transform: rotateZ(10deg);
  }

  30% {
    transform: rotateZ(-10deg);
  }

  45% {
    transform: rotateZ(5deg);
  }

  60% {
    transform: rotateZ(-5deg);
  }

  75% {
    transform: rotateZ(2deg);
  }
}
</style>
