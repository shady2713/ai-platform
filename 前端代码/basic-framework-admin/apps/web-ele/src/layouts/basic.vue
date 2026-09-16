<script lang="ts" setup>
import type { NotificationItem } from '@vben/layouts';

import { computed, onMounted, onUnmounted, ref, watch } from 'vue';

import { AuthenticationLoginExpiredModal } from '@vben/common-ui';
import { useWatermark } from '@vben/hooks';
import { AntdProfileOutlined } from '@vben/icons';
import {
  BasicLayout,
  LockScreen,
  Notification,
  UserDropdown,
} from '@vben/layouts';
import { preferences } from '@vben/preferences';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import {
  getUnreadNotifyMessageCount,
  getUnreadNotifyMessageList,
  updateAllNotifyMessageRead,
  updateNotifyMessageRead,
} from '#/api/system/notify/message';
import { $t } from '#/locales';
import { router } from '#/router';
import { useAuthStore } from '#/store';
import LoginForm from '#/views/_core/authentication/login.vue';

const userStore = useUserStore();
const authStore = useAuthStore();
const accessStore = useAccessStore();
const { destroyWatermark, updateWatermark } = useWatermark();

const notifications = ref<NotificationItem[]>([]);
const unreadCount = ref(0);
const NOTIFICATION_POLL_INTERVAL_MS = 2 * 60 * 1000;
let notificationPollId: ReturnType<typeof setInterval> | undefined;

const menus = computed(() => [
  {
    handler: () => {
      router.push({ name: 'Profile' });
    },
    icon: AntdProfileOutlined,
    text: $t('ui.widgets.profile'),
  },
]);

const avatar = computed(() => {
  return userStore.userInfo?.avatar ?? preferences.app.defaultAvatar;
});

async function handleLogout() {
  await authStore.logout(false);
}

/** 获得未读消息数 */
async function handleNotificationGetUnreadCount() {
  const userId = userStore.userInfo?.id;
  const count = await getUnreadNotifyMessageCount();
  if (userId === userStore.userInfo?.id) unreadCount.value = count;
}

/** 获得消息列表 */
async function handleNotificationGetList() {
  const userId = userStore.userInfo?.id;
  const list = await getUnreadNotifyMessageList();
  if (userId !== userStore.userInfo?.id) return;
  notifications.value = list.map((item) => ({
    avatar: preferences.app.defaultAvatar,
    date: formatDateTime(item.createTime) as string,
    isRead: false,
    id: item.id,
    message: item.templateContent,
    title: item.templateNickname,
  }));
}

/** 跳转我的站内信 */
function handleNotificationViewAll() {
  router.push({
    name: 'MyNotifyMessage',
  });
}

/** 标记所有已读 */
async function handleNotificationMakeAll() {
  const userId = userStore.userInfo?.id;
  await updateAllNotifyMessageRead();
  if (userId !== userStore.userInfo?.id) return;
  unreadCount.value = 0;
  notifications.value = [];
}

/** 清空通知 */
async function handleNotificationClear() {
  await handleNotificationMakeAll();
}

/** 标记单个已读 */
async function handleNotificationRead(item: NotificationItem) {
  if (!item.id) {
    return;
  }
  const userId = userStore.userInfo?.id;
  await updateNotifyMessageRead(item.id);
  if (userId !== userStore.userInfo?.id) return;
  await handleNotificationGetUnreadCount();
  notifications.value = notifications.value.filter((n) => n.id !== item.id);
}

/** 处理通知打开 */
function handleNotificationOpen(open: boolean) {
  if (!open) {
    return;
  }
  handleNotificationGetList();
  handleNotificationGetUnreadCount();
}

// ========== 初始化 ==========
onMounted(() => {
  // 首次加载未读数量
  handleNotificationGetUnreadCount();
  // 轮询刷新未读数量
  notificationPollId = setInterval(() => {
    if (userStore.userInfo) {
      void handleNotificationGetUnreadCount();
    }
  }, NOTIFICATION_POLL_INTERVAL_MS);
});

onUnmounted(() => {
  if (notificationPollId !== undefined) {
    clearInterval(notificationPollId);
    notificationPollId = undefined;
  }
});

watch(
  () => userStore.userInfo?.id,
  () => {
    notifications.value = [];
    unreadCount.value = 0;
  },
  { flush: 'sync' },
);

watch(
  () => ({
    enable: preferences.app.watermark,
    content:
      preferences.app.watermarkContent ||
      (userStore.userInfo
        ? `${userStore.userInfo.id} - ${userStore.userInfo.nickname}`
        : ''),
  }),
  async ({ enable, content }) => {
    if (enable && content) {
      await updateWatermark({ content });
    } else {
      destroyWatermark();
    }
  },
  {
    immediate: true,
  },
);
</script>

<template>
  <BasicLayout @clear-preferences-and-logout="handleLogout">
    <template #user-dropdown>
      <UserDropdown
        :avatar
        :menus
        :text="userStore.userInfo?.nickname"
        :description="userStore.userInfo?.email"
        :tag-text="userStore.userInfo?.username"
        @logout="handleLogout"
      />
    </template>
    <template #notification>
      <Notification
        :dot="unreadCount > 0"
        :notifications="notifications"
        @clear="handleNotificationClear"
        @make-all="handleNotificationMakeAll"
        @view-all="handleNotificationViewAll"
        @open="handleNotificationOpen"
        @read="handleNotificationRead"
      />
    </template>
    <template #extra>
      <AuthenticationLoginExpiredModal
        v-model:open="accessStore.loginExpired"
        :avatar
      >
        <LoginForm />
      </AuthenticationLoginExpiredModal>
    </template>
    <template #lock-screen>
      <LockScreen :avatar @to-login="handleLogout" />
    </template>
  </BasicLayout>
</template>
