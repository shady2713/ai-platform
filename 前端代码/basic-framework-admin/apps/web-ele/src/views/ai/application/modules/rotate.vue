<script lang="ts" setup>
/**
 * 凭据轮换弹窗（A09）：确认后调用轮换接口，响应里的一次性秘密交给父组件展示。
 */
import type { AiApplicationApi } from '#/api/ai/application';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { rotateCredential } from '#/api/ai/application';

import { GRANT_SCOPE_WARNING } from '../data';

const emit = defineEmits(['secret']);
const application = ref<AiApplicationApi.Application>();

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    if (!application.value) {
      return;
    }
    modalApi.lock();
    try {
      const issue = await rotateCredential(
        application.value.id,
        application.value.version,
      );
      emit('secret', { appCode: issue.appCode, secret: issue.secret });
      await modalApi.close();
    } finally {
      modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      application.value = undefined;
      return;
    }
    application.value = modalApi.getData<AiApplicationApi.Application>();
    modalApi.setState({ title: `轮换凭据：${application.value?.name ?? ''}` });
  },
});
</script>

<template>
  <Modal class="w-[560px]">
    <div class="flex flex-col gap-3 text-sm">
      <div>
        轮换后**旧凭据立即失效**，对接方需要立刻更新配置；新凭据只在下一步展示一次。
      </div>
      <div class="text-amber-600">{{ GRANT_SCOPE_WARNING }}</div>
    </div>
  </Modal>
</template>
