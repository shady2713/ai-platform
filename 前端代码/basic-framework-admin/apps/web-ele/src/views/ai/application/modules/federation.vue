<script lang="ts" setup>
/**
 * 跨系统主体联邦弹窗（Y01）：显式登记 → 独立审批 → 撤销。
 *
 * 三条前端纪律：
 * 1. 不按同名推断：表单要求逐对填写两侧身份，任何"自动匹配相同 externalUserId"的入口都不提供；
 * 2. 批准人不由前端提交（服务端按登录态判定，且必须不同于提交人）；
 * 3. 撤销立即生效：撤销后按钮刷新列表，不本地保留"已批准"的旧状态。
 */
import type { AiApplicationApi } from '#/api/ai/application';
import type { AiDiscoveryApi } from '#/api/ai/application/discovery';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { useVbenForm } from '#/adapter/form';
import {
  approveSubjectFederation,
  getSubjectFederationPage,
  revokeSubjectFederation,
  submitSubjectFederation,
} from '#/api/ai/application/discovery';
import { showRequestError, showSuccessMessage } from '#/utils/feedback';

import {
  FEDERATION_APPROVAL_HINT,
  FEDERATION_NO_INFERENCE_HINT,
  federationIdentityText,
  federationStatusLabel,
  useFederationFormSchema,
} from '../data';

interface FederationFormValues {
  sourceExternalUserId?: string;
  sourceSubjectType: AiDiscoveryApi.SubjectType;
  targetApplicationId: string;
  targetExternalUserId?: string;
  targetSubjectType: AiDiscoveryApi.SubjectType;
}

const application = ref<AiApplicationApi.Application>();
const rows = ref<AiDiscoveryApi.Federation[]>([]);

const [Form, formApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' } },
  layout: 'horizontal',
  schema: useFederationFormSchema(),
  showDefaultActions: false,
  wrapperClass: 'grid-cols-1',
});

const [Modal, modalApi] = useVbenModal({
  onOpenChange(isOpen: boolean) {
    if (isOpen) {
      application.value = modalApi.getData<AiApplicationApi.Application>();
      modalApi.setState({
        title: `跨系统主体联邦：${application.value?.name ?? ''}`,
      });
      void loadPage();
      return;
    }
    application.value = undefined;
    rows.value = [];
  },
  showConfirmButton: false,
});

async function loadPage() {
  if (!application.value?.id) {
    return;
  }
  try {
    const page = await getSubjectFederationPage({
      pageNo: 1,
      pageSize: 20,
      sourceApplicationId: application.value.id,
    });
    rows.value = page.list ?? [];
  } catch (error) {
    showRequestError(error, '联邦映射列表加载失败');
  }
}

async function handleSubmit() {
  if (!application.value?.id) {
    return;
  }
  const values = (await formApi.getValues()) as FederationFormValues;
  const targetApplicationId = Number(values.targetApplicationId);
  if (!Number.isInteger(targetApplicationId) || targetApplicationId <= 0) {
    showRequestError(new Error('目标应用编号必须是正整数'), '登记被拒绝');
    return;
  }
  modalApi.lock();
  try {
    await submitSubjectFederation({
      sourceApplicationId: application.value.id,
      sourceExternalUserId:
        values.sourceSubjectType === 'USER'
          ? values.sourceExternalUserId
          : undefined,
      sourceSubjectType: values.sourceSubjectType,
      targetApplicationId,
      targetExternalUserId:
        values.targetSubjectType === 'USER'
          ? values.targetExternalUserId
          : undefined,
      targetSubjectType: values.targetSubjectType,
    });
    showSuccessMessage('已提交，等待另一位操作员独立审批');
    await loadPage();
  } catch (error) {
    showRequestError(error, '登记被拒绝');
  } finally {
    modalApi.unlock();
  }
}

async function handleApprove(federation: AiDiscoveryApi.Federation) {
  modalApi.lock();
  try {
    await approveSubjectFederation({
      approvalNote: `独立审批通过（映射 #${federation.id}）`,
      id: federation.id,
      version: federation.version,
    });
    showSuccessMessage('已批准：该映射开始参与授权发现');
    await loadPage();
  } catch (error) {
    showRequestError(error, '审批被拒绝');
  } finally {
    modalApi.unlock();
  }
}

async function handleRevoke(federation: AiDiscoveryApi.Federation) {
  modalApi.lock();
  try {
    await revokeSubjectFederation(federation.id, federation.version);
    showSuccessMessage('已撤销：下一次授权发现不再包含该目标系统');
    await loadPage();
  } catch (error) {
    showRequestError(error, '撤销被拒绝');
  } finally {
    modalApi.unlock();
  }
}
</script>

<template>
  <Modal class="w-[760px]">
    <div class="flex flex-col gap-4 text-sm">
      <div class="text-xs text-amber-600" data-test="federation-approval-hint">
        {{ FEDERATION_APPROVAL_HINT }}
      </div>
      <div class="text-xs text-amber-600" data-test="federation-no-inference">
        {{ FEDERATION_NO_INFERENCE_HINT }}
      </div>
      <Form />
      <div>
        <button
          class="rounded border px-3 py-1"
          data-test="federation-submit"
          type="button"
          @click="handleSubmit"
        >
          登记联邦映射
        </button>
      </div>
      <div
        v-for="row in rows"
        :key="row.id"
        class="flex items-center justify-between rounded border p-2"
        :data-test="`federation-${row.id}`"
      >
        <div class="flex flex-col">
          <span>{{ federationIdentityText(row) }}</span>
          <span class="text-xs text-gray-500">
            {{ federationStatusLabel(row.status) }} · 映射版本
            {{ row.revision }}
          </span>
        </div>
        <div class="flex gap-2">
          <button
            v-if="row.status === 'PENDING'"
            class="rounded border px-2 py-1"
            :data-test="`federation-approve-${row.id}`"
            type="button"
            @click="handleApprove(row)"
          >
            独立审批通过
          </button>
          <button
            v-if="row.status !== 'REVOKED'"
            class="rounded border px-2 py-1"
            :data-test="`federation-revoke-${row.id}`"
            type="button"
            @click="handleRevoke(row)"
          >
            撤销
          </button>
        </div>
      </div>
      <div
        v-if="rows.length === 0"
        class="text-xs text-gray-500"
        data-test="federation-empty"
      >
        当前应用还没有联邦映射：没有映射时，跨系统分析只能看到当前系统。
      </div>
    </div>
  </Modal>
</template>
