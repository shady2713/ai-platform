<script lang="ts" setup>
import type { SystemNotifyMessageApi } from '#/api/system/notify/message';

import { onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  ElButton,
  ElCard,
  ElEmpty,
  ElPagination,
  ElTabPane,
  ElTabs,
  ElTag,
} from 'element-plus';

import {
  getNotifyMessagePage,
  updateNotifyMessageRead,
} from '#/api/system/notify/message';
import { showSuccessMessage } from '#/utils/feedback';

defineOptions({ name: 'MyNotifyMessage' });

const loading = ref(false);
const messages = ref<SystemNotifyMessageApi.Message[]>([]);
const total = ref(0);
const currentPage = ref(1);
const pageSize = ref(10);
const activeTab = ref('unread');

const READ_STATUS_BY_TAB: Record<string, boolean | undefined> = {
  read: true,
  unread: false,
};

async function loadMessages() {
  loading.value = true;
  try {
    const data = await getNotifyMessagePage({
      pageNo: currentPage.value,
      pageSize: pageSize.value,
      readStatus: READ_STATUS_BY_TAB[activeTab.value],
    });
    messages.value = data.list || [];
    total.value = data.total || 0;
  } catch {
    messages.value = [];
  } finally {
    loading.value = false;
  }
}

async function handleRead(id: number) {
  await updateNotifyMessageRead(id);
  showSuccessMessage('已标记为已读');
  await loadMessages();
}

async function handleReadAll() {
  for (const msg of messages.value) {
    if (!msg.readStatus) {
      await updateNotifyMessageRead(msg.id);
    }
  }
  showSuccessMessage('全部标记为已读');
  await loadMessages();
}

function handleTabChange() {
  currentPage.value = 1;
  loadMessages();
}

function handlePageChange(page: number) {
  currentPage.value = page;
  loadMessages();
}

onMounted(() => {
  loadMessages();
});
</script>

<template>
  <Page auto-content-height>
    <ElCard shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span class="text-lg font-medium">我的站内信</span>
          <ElButton type="primary" size="small" @click="handleReadAll">
            全部已读
          </ElButton>
        </div>
      </template>

      <ElTabs v-model="activeTab" @tab-change="handleTabChange">
        <ElTabPane label="未读" name="unread" />
        <ElTabPane label="已读" name="read" />
        <ElTabPane label="全部" name="all" />
      </ElTabs>

      <div v-loading="loading" class="message-list">
        <ElEmpty v-if="messages.length === 0" description="暂无消息" />

        <div
          v-for="msg in messages"
          :key="msg.id"
          class="message-item"
          :class="{ unread: !msg.readStatus }"
        >
          <div class="flex items-start justify-between">
            <div class="flex-1">
              <div class="mb-1 flex items-center gap-2">
                <ElTag v-if="!msg.readStatus" type="danger" size="small">
                  未读
                </ElTag>
                <span class="font-medium">{{
                  msg.templateNickname || '系统通知'
                }}</span>
                <span class="text-sm text-gray-400">{{ msg.createTime }}</span>
              </div>
              <div class="text-gray-600">{{ msg.templateContent }}</div>
            </div>
            <ElButton
              v-if="!msg.readStatus"
              type="primary"
              link
              size="small"
              @click="handleRead(msg.id)"
            >
              标记已读
            </ElButton>
          </div>
        </div>
      </div>

      <div v-if="total > pageSize" class="mt-4 flex justify-end">
        <ElPagination
          :current-page="currentPage"
          :page-size="pageSize"
          :total="total"
          layout="prev, pager, next"
          @current-change="handlePageChange"
        />
      </div>
    </ElCard>
  </Page>
</template>

<style scoped>
.message-list {
  min-height: 200px;
}

.message-item {
  padding: 12px 0;
  border-bottom: 1px solid #f0f0f0;
}

.message-item:last-child {
  border-bottom: none;
}

.message-item.unread {
  padding: 12px 8px;
  background-color: #fafafa;
  border-radius: 4px;
}
</style>
