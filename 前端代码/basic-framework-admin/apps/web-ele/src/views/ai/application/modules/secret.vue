<script lang="ts" setup>
/**
 * 一次性秘密展示弹窗（A09）：秘密只在本弹窗内出现一次，关闭即清理，页面不再持有。
 */
import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { SECRET_ONCE_TITLE, SECRET_ONCE_WARNING } from '../data';

const secret = ref<string>('');
const appCode = ref<string>('');

const [Modal, modalApi] = useVbenModal({
  footer: false,
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      // 离开即清理：不在页面/内存里保留明文
      secret.value = '';
      appCode.value = '';
      return;
    }
    const data = modalApi.getData<{ appCode: string; secret: string }>();
    appCode.value = data?.appCode ?? '';
    secret.value = data?.secret ?? '';
  },
});
</script>

<template>
  <Modal class="w-[560px]">
    <div class="flex flex-col gap-3">
      <div class="text-base font-medium">{{ SECRET_ONCE_TITLE }}</div>
      <div class="text-sm text-amber-600">{{ SECRET_ONCE_WARNING }}</div>
      <div class="text-sm text-gray-500">应用：{{ appCode }}</div>
      <code
        class="block break-all rounded bg-gray-100 p-3 text-sm"
        data-test="ai-secret-value"
      >
        {{ secret }}
      </code>
      <div class="flex justify-end">
        <button type="button" class="btn" @click="modalApi.close()">
          我已保存，关闭
        </button>
      </div>
    </div>
  </Modal>
</template>
