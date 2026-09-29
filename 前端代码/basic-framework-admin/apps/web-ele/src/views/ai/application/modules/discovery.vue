<script lang="ts" setup>
/**
 * 授权发现与范围选择弹窗（Y01）。
 *
 * 前端只做两件事：把发现结果如实展示（无权系统不出现），以及把"显式选择"的需求变成必填项。
 * 它不推断范围、不缓存目录、不合成目标系统清单：选择请求必须携带发现接口给出的目录指纹，
 * 服务端会用当前事实重算并比对（事实变化即 409）。
 */
import type { AiApplicationApi } from '#/api/ai/application';
import type { AiDiscoveryApi } from '#/api/ai/application/discovery';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { useVbenForm } from '#/adapter/form';
import {
  getSystemCatalog,
  selectAnalysisScope,
  verifyAnalysisScope,
} from '#/api/ai/application/discovery';
import { showRequestError, showSuccessMessage } from '#/utils/feedback';

import {
  analysisScopeModeLabel,
  CROSS_SYSTEM_SELECTION_HINT,
  DISCOVERY_DENIED_HINT,
  scopeSummary,
  useDiscoveryFormSchema,
} from '../data';

interface DiscoveryFormValues {
  externalUserId?: string;
  subjectType: string;
}

const application = ref<AiApplicationApi.Application>();
const catalog = ref<AiDiscoveryApi.Catalog>();
const selection = ref<AiDiscoveryApi.AnalysisScopeSelection>();
const selectedCodes = ref<string[]>([]);
const verifying = ref(false);
const verifyingResult = ref('');

const [Form, formApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' } },
  layout: 'horizontal',
  schema: useDiscoveryFormSchema(),
  showDefaultActions: false,
  wrapperClass: 'grid-cols-1',
});

const [Modal, modalApi] = useVbenModal({
  onOpenChange(isOpen: boolean) {
    if (isOpen) {
      application.value = modalApi.getData<AiApplicationApi.Application>();
      modalApi.setState({
        title: `授权发现：${application.value?.name ?? ''}`,
      });
      return;
    }
    reset();
  },
  showConfirmButton: false,
});

const entries = computed(() => catalog.value?.entries ?? []);

function reset() {
  application.value = undefined;
  catalog.value = undefined;
  selection.value = undefined;
  selectedCodes.value = [];
  verifyingResult.value = '';
}

async function handleDiscover() {
  if (!application.value?.id) {
    return;
  }
  const values = (await formApi.getValues()) as DiscoveryFormValues;
  modalApi.lock();
  try {
    catalog.value = await getSystemCatalog({
      applicationId: application.value.id,
      externalUserId:
        values.subjectType === 'USER' ? values.externalUserId : undefined,
      subjectType: values.subjectType,
    });
    // 默认全选可访问系统（含当前系统）；用户可取消勾选，但不能添加目录之外的系统
    selectedCodes.value = entries.value.map((entry) => entry.appCode);
    selection.value = undefined;
    verifyingResult.value = '';
  } catch (error) {
    showRequestError(error, '授权发现失败');
  } finally {
    modalApi.unlock();
  }
}

function toggleSystem(appCode: string) {
  selectedCodes.value = selectedCodes.value.includes(appCode)
    ? selectedCodes.value.filter((code) => code !== appCode)
    : [...selectedCodes.value, appCode];
}

async function handleSelect() {
  if (!application.value?.id || !catalog.value) {
    return;
  }
  const values = (await formApi.getValues()) as DiscoveryFormValues;
  const currentCode = entries.value.find(
    (entry) => entry.currentSystem,
  )?.appCode;
  const targets = [...selectedCodes.value];
  const mode: AiDiscoveryApi.AnalysisScopeMode =
    targets.length > 1 && currentCode && targets.includes(currentCode)
      ? 'CROSS_SYSTEM'
      : 'CURRENT_SYSTEM';
  modalApi.lock();
  try {
    selection.value = await selectAnalysisScope({
      applicationId: application.value.id,
      catalogFingerprint: catalog.value.catalogFingerprint,
      externalUserId:
        values.subjectType === 'USER' ? values.externalUserId : undefined,
      mode,
      subjectType: values.subjectType,
      targetSystemCodes: mode === 'CROSS_SYSTEM' ? targets : undefined,
    });
    verifyingResult.value = '';
    showSuccessMessage(
      mode === 'CROSS_SYSTEM' ? '已固定跨系统分析范围' : '已固定当前系统范围',
    );
  } catch (error) {
    showRequestError(error, '范围选择被拒绝');
  } finally {
    modalApi.unlock();
  }
}

async function handleVerify() {
  if (!selection.value) {
    return;
  }
  verifying.value = true;
  try {
    const verified = await verifyAnalysisScope({
      applicationId: selection.value.applicationId,
      catalogFingerprint: selection.value.catalogFingerprint,
      externalUserId: selection.value.externalUserId,
      mode: selection.value.mode,
      selectionFingerprint: selection.value.selectionFingerprint,
      subjectType: selection.value.subjectType,
      targetSystemCodes: selection.value.targetSystemCodes,
    });
    verifyingResult.value = `核验通过：${verified.systems.length} 个系统的授权事实未变化`;
  } catch (error) {
    verifyingResult.value =
      '核验失败：授权或映射事实已变化，请重新发现后再选择';
    showRequestError(error, '范围选择核验失败');
  } finally {
    verifying.value = false;
  }
}
</script>

<template>
  <Modal class="w-[720px]">
    <div class="flex flex-col gap-4 text-sm">
      <Form />
      <div class="flex gap-2">
        <button
          class="rounded border px-3 py-1"
          data-test="discover-run"
          type="button"
          @click="handleDiscover"
        >
          发现可访问系统
        </button>
      </div>
      <div
        v-if="catalog && catalog.denied"
        class="rounded bg-amber-50 p-3 text-amber-700"
        data-test="discovery-denied"
      >
        {{ DISCOVERY_DENIED_HINT }}
      </div>
      <div v-else-if="catalog" class="flex flex-col gap-2">
        <div class="text-xs text-gray-500">
          目录指纹：{{ catalog.catalogFingerprint }}
        </div>
        <div
          v-for="entry in entries"
          :key="entry.appCode"
          class="flex items-start gap-2 rounded border p-2"
          :data-test="`entry-${entry.appCode}`"
        >
          <input
            :checked="selectedCodes.includes(entry.appCode)"
            :data-test="`toggle-${entry.appCode}`"
            type="checkbox"
            @change="toggleSystem(entry.appCode)"
          />
          <div class="flex flex-col">
            <span>
              {{ entry.systemName }}（{{ entry.appCode }}）
              <span v-if="entry.currentSystem">· 当前系统</span>
              <span v-else>· 联邦映射 #{{ entry.federationId }}</span>
            </span>
            <span
              class="text-xs text-gray-500"
              :data-test="`scope-${entry.appCode}`"
            >
              {{ scopeSummary(entry) }}
            </span>
          </div>
        </div>
        <div class="text-xs text-gray-500">
          {{ CROSS_SYSTEM_SELECTION_HINT }}
        </div>
        <div class="flex gap-2">
          <button
            class="rounded border px-3 py-1"
            data-test="discover-select"
            type="button"
            @click="handleSelect"
          >
            固定范围选择
          </button>
          <button
            v-if="selection"
            class="rounded border px-3 py-1"
            data-test="discover-verify"
            :disabled="verifying"
            type="button"
            @click="handleVerify"
          >
            再核验选择
          </button>
        </div>
        <div
          v-if="selection"
          class="flex flex-col gap-1"
          data-test="selection-result"
        >
          <div>
            模式：{{ analysisScopeModeLabel(selection.mode) }}；系统：{{
              selection.targetSystemCodes.join('、')
            }}
          </div>
          <div class="text-xs text-gray-500">
            选择指纹：{{ selection.selectionFingerprint }}
          </div>
          <div
            class="text-xs text-gray-500"
            data-test="selection-verify-result"
          >
            {{ verifyingResult }}
          </div>
        </div>
      </div>
    </div>
  </Modal>
</template>
