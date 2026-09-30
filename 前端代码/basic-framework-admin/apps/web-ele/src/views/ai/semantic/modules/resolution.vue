<script lang="ts" setup>
/**
 * 判定与目录发现弹窗（Y02）：显式版本/时刻的源键判定、源键反查与主体目录发现。
 *
 * 三条前端纪律：
 * 1. 判定请求必须携带版本号与判定时刻（默认填当前时间，但用户可见、可改——报表要按受理时刻解释）；
 * 2. 反查的 `mapped=false` 是**有效结论**（未登记即不关联），页面照实展示，不把它当错误；
 * 3. 目录发现只展示后端返回的可见条目与问题标注，无权系统由后端决定"不出现"。
 */
import type { AiSemanticApi } from '#/api/ai/semantic';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { useVbenForm } from '#/adapter/form';
import {
  getMasterObjectCatalog,
  resolveObjectKey,
  resolveSourceKey,
} from '#/api/ai/semantic';
import { showRequestError } from '#/utils/feedback';

import {
  CATALOG_HINT,
  formatWindow,
  NO_NAME_INFERENCE_HINT,
  nowIsoSeconds,
  problemTagType,
  reverseConclusion,
} from '../data';

interface ResolveFormValues {
  applicationId: number;
  entityType: string;
  revisionNo: number;
}

interface ReverseFormValues {
  applicationId: number;
  entityType: string;
  sourceKey: string;
}

interface CatalogFormValues {
  applicationId: number;
  externalUserId?: string;
  subjectType: string;
}

const masterObject = ref<AiSemanticApi.MasterObject>();
const resolution = ref<AiSemanticApi.MappingResolution>();
const reverse = ref<AiSemanticApi.MappingReverse>();
const catalog = ref<AiSemanticApi.ObjectCatalog>();
const resolveAsOf = ref(nowIsoSeconds());
const reverseAsOf = ref(nowIsoSeconds());
const catalogAsOf = ref(nowIsoSeconds());

const revisionOptions = computed(() =>
  masterObject.value && masterObject.value.currentRevision > 0
    ? [
        {
          label: `当前已发布版本 v${masterObject.value.currentRevision}`,
          value: masterObject.value.currentRevision,
        },
      ]
    : [],
);

function numberField(
  fieldName: string,
  label: string,
  placeholder: string,
): Record<string, unknown> {
  return {
    component: 'InputNumber',
    componentProps: { min: 1, placeholder },
    fieldName,
    label,
  };
}

const [ResolveForm, resolveFormApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' } },
  layout: 'horizontal',
  schema: [
    numberField('applicationId', '来源系统编号', '如 7'),
    {
      component: 'Input',
      componentProps: { placeholder: '如 customer' },
      fieldName: 'entityType',
      label: '实体类型',
    },
    {
      component: 'Select',
      componentProps: { options: [], placeholder: '必须显式选择版本' },
      fieldName: 'revisionNo',
      label: '映射版本',
    },
  ] as never,
  showDefaultActions: false,
  wrapperClass: 'grid-cols-1',
});

const [ReverseForm, reverseFormApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' } },
  layout: 'horizontal',
  schema: [
    numberField('applicationId', '来源系统编号', '如 7'),
    {
      component: 'Input',
      componentProps: { placeholder: '如 customer' },
      fieldName: 'entityType',
      label: '实体类型',
    },
    {
      component: 'Input',
      componentProps: { placeholder: '来源系统里的业务主键' },
      fieldName: 'sourceKey',
      label: '源键',
    },
  ] as never,
  showDefaultActions: false,
  wrapperClass: 'grid-cols-1',
});

const [CatalogForm, catalogFormApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' } },
  layout: 'horizontal',
  schema: [
    numberField('applicationId', '当前系统编号', '当前应用编号'),
    {
      component: 'Input',
      componentProps: { placeholder: '当前主体类型（USER/APP）' },
      defaultValue: 'USER',
      fieldName: 'subjectType',
      label: '主体类型',
    },
    {
      component: 'Input',
      componentProps: { placeholder: 'USER 主体必填' },
      fieldName: 'externalUserId',
      label: '外部用户标识',
    },
  ] as never,
  showDefaultActions: false,
  wrapperClass: 'grid-cols-1',
});

const [Modal, modalApi] = useVbenModal({
  onOpenChange(isOpen: boolean) {
    if (isOpen) {
      masterObject.value = modalApi.getData<AiSemanticApi.MasterObject>();
      resolution.value = undefined;
      reverse.value = undefined;
      catalog.value = undefined;
      resolveAsOf.value = nowIsoSeconds();
      reverseAsOf.value = nowIsoSeconds();
      catalogAsOf.value = nowIsoSeconds();
      resolveFormApi.setValues({
        revisionNo: masterObject.value?.currentRevision || undefined,
      });
      modalApi.setState({
        title: `判定与目录：${masterObject.value?.objectCode ?? ''}`,
      });
      return;
    }
    masterObject.value = undefined;
  },
  showConfirmButton: false,
});

async function handleResolve() {
  if (!masterObject.value) {
    return;
  }
  const values = (await resolveFormApi.getValues()) as ResolveFormValues;
  modalApi.lock();
  try {
    resolution.value = await resolveObjectKey({
      applicationId: Number(values.applicationId),
      asOf: resolveAsOf.value,
      entityType: values.entityType,
      objectCode: masterObject.value.objectCode,
      revisionNo: Number(values.revisionNo),
    });
  } catch (error) {
    resolution.value = undefined;
    showRequestError(error, '判定被阻断：请查看错误码（冲突/过期/未发布）');
  } finally {
    modalApi.unlock();
  }
}

async function handleReverse() {
  const values = (await reverseFormApi.getValues()) as ReverseFormValues;
  modalApi.lock();
  try {
    reverse.value = await resolveSourceKey({
      applicationId: Number(values.applicationId),
      asOf: reverseAsOf.value,
      entityType: values.entityType,
      sourceKey: values.sourceKey,
    });
  } catch (error) {
    reverse.value = undefined;
    showRequestError(error, '反查被阻断：请查看错误码（冲突/过期/停用）');
  } finally {
    modalApi.unlock();
  }
}

async function handleCatalog() {
  if (!masterObject.value) {
    return;
  }
  const values = (await catalogFormApi.getValues()) as CatalogFormValues;
  modalApi.lock();
  try {
    catalog.value = await getMasterObjectCatalog({
      applicationId: Number(values.applicationId),
      asOf: catalogAsOf.value,
      externalUserId:
        values.subjectType === 'USER' ? values.externalUserId : undefined,
      objectCode: masterObject.value.objectCode,
      revisionNo: masterObject.value.currentRevision,
      subjectType: values.subjectType,
    });
  } catch (error) {
    catalog.value = undefined;
    showRequestError(error, '目录发现失败');
  } finally {
    modalApi.unlock();
  }
}
</script>

<template>
  <Modal>
    <div class="space-y-6">
      <section data-test="resolve-panel">
        <h4>按对象 + 显式版本判定源键</h4>
        <ResolveForm />
        <label class="text-xs">
          判定时刻
          <input v-model="resolveAsOf" class="input" data-test="resolve-asof" />
        </label>
        <button
          class="btn btn-primary"
          data-test="resolve"
          type="button"
          @click="handleResolve"
        >
          判定
        </button>
        <p v-if="resolution" data-test="resolution">
          {{ resolution.objectName }}（{{ resolution.objectCode }}）· 源键
          {{ resolution.sourceKey }} · 版本 v{{ resolution.revisionNo }} · 指纹
          {{ resolution.revisionFingerprint }}
        </p>
        <p
          v-else-if="revisionOptions.length === 0"
          class="text-xs text-muted-foreground"
        >
          该对象还没有已发布版本：草稿不是可核验事实，不能用于判定。
        </p>
      </section>

      <section data-test="reverse-panel">
        <h4>按源键反查统一对象</h4>
        <ReverseForm />
        <label class="text-xs">
          判定时刻
          <input v-model="reverseAsOf" class="input" data-test="reverse-asof" />
        </label>
        <button
          class="btn"
          data-test="reverse"
          type="button"
          @click="handleReverse"
        >
          反查
        </button>
        <p v-if="reverse" data-test="reverse-result">
          {{ reverseConclusion(reverse) }}
          <span v-if="reverse.mapped">
            · 版本 v{{ reverse.revisionNo }} · 指纹
            {{ reverse.revisionFingerprint }}
          </span>
        </p>
        <p class="text-xs text-muted-foreground">
          {{ NO_NAME_INFERENCE_HINT }}
        </p>
      </section>

      <section data-test="catalog-panel">
        <h4>主体可见目录</h4>
        <CatalogForm />
        <label class="text-xs">
          判定时刻
          <input v-model="catalogAsOf" class="input" data-test="catalog-asof" />
        </label>
        <button
          class="btn"
          data-test="catalog"
          type="button"
          @click="handleCatalog"
        >
          发现
        </button>
        <div v-if="catalog">
          <p v-if="catalog.denied" data-test="catalog-denied">
            当前主体没有任何可访问系统（与"未登记"同形，平台不区分）。
          </p>
          <ul v-else data-test="catalog-entries">
            <li
              v-for="entry in catalog.entries"
              :key="`${entry.applicationId}-${entry.sourceKey}`"
            >
              {{ entry.systemName }}（{{ entry.appCode }}）·
              {{ entry.entityType }} ·
              {{ entry.sourceKey }}
              <span :class="`tag tag-${problemTagType(entry.problem)}`">
                {{ entry.problem }}
              </span>
              <span>{{ formatWindow(entry.validFrom, entry.validTo) }}</span>
            </li>
          </ul>
          <p data-test="catalog-fingerprint">
            目录指纹：{{ catalog.catalogFingerprint }}
          </p>
        </div>
        <p class="text-xs text-muted-foreground">{{ CATALOG_HINT }}</p>
      </section>
    </div>
  </Modal>
</template>
