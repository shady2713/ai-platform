<script lang="ts" setup>
/** 服务草稿编辑器（S05）：模型/提示词/资源/Schema/执行限制，Schema 即时校验但仍由后端判定。 */
import type { AiServiceApi } from '#/api/ai/service';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { useVbenForm } from '#/adapter/form';
import {
  bindServiceResource,
  createService,
  getService,
  listServiceResources,
  unbindServiceResource,
  updateService,
} from '#/api/ai/service';
import { $t } from '#/locales';
import { showSuccessMessage } from '#/utils/feedback';

import {
  RESOURCE_ACTION_OPTIONS,
  useDraftSchema,
  validateJsonObjectSchema,
} from '../data';

interface DraftFormValues {
  appId: number;
  code: string;
  evalThreshold: number;
  id?: number;
  inputSchema: string;
  modelEndpointId: number;
  name: string;
  outputSchema?: string;
  promptTemplate: string;
  requiredCapabilities: string[];
  runSubjectType: string;
  version?: number;
}

const emit = defineEmits(['success']);
const formData = ref<AiServiceApi.Service>();
const bindings = ref<AiServiceApi.ServiceResource[]>([]);
const schemaHint = ref('');
const bindingForm = ref({
  actions: ['READ'] as string[],
  resourceKey: '',
  resourceType: 'REPORT',
});

const serviceId = computed(() => formData.value?.id);

/** Schema 即时提示：字段变化即校验，提交前再校验一次；后端仍会独立拒绝非法 Schema */
function refreshSchemaHint(values: Record<string, unknown>, changed: string[]) {
  for (const field of ['inputSchema', 'outputSchema'] as const) {
    if (!changed.includes(field)) {
      continue;
    }
    const value = String(values[field] ?? '');
    if (field === 'outputSchema' && value.trim().length === 0) {
      schemaHint.value = '';
      continue;
    }
    const result = validateJsonObjectSchema(value);
    schemaHint.value = result.ok ? '' : (result.message ?? '');
  }
}

const [Form, formApi] = useVbenForm({
  commonConfig: {
    componentProps: { class: 'w-full' },
    formItemClass: 'col-span-2',
    labelWidth: 110,
  },
  handleValuesChange: (values, fieldsChanged) => {
    refreshSchemaHint(values as Record<string, unknown>, fieldsChanged);
  },
  layout: 'horizontal',
  schema: useDraftSchema(),
  showDefaultActions: false,
});

async function loadBindings(id: number) {
  bindings.value = await listServiceResources(id);
}

async function handleBind() {
  if (!serviceId.value) {
    return;
  }
  if (!bindingForm.value.resourceKey) {
    showSuccessMessage('请填写资源标识');
    return;
  }
  await bindServiceResource({
    actions: bindingForm.value.actions,
    resourceKey: bindingForm.value.resourceKey,
    resourceType: bindingForm.value.resourceType,
    serviceId: serviceId.value,
  });
  bindingForm.value.resourceKey = '';
  await loadBindings(serviceId.value);
  showSuccessMessage('资源已绑定（越权绑定会被后端拒绝）');
}

async function handleUnbind(binding: AiServiceApi.ServiceResource) {
  await unbindServiceResource(binding.id, binding.version);
  if (serviceId.value) {
    await loadBindings(serviceId.value);
  }
  showSuccessMessage('资源已解绑');
}

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await formApi.validate();
    if (!valid) {
      return;
    }
    const values = (await formApi.getValues()) as DraftFormValues;
    // 即时校验不通过时不提交，避免明知非法仍打后端；后端仍会独立拒绝
    for (const field of ['inputSchema', 'outputSchema'] as const) {
      if (field === 'outputSchema' && !values.outputSchema) {
        continue;
      }
      const result = validateJsonObjectSchema(values[field] ?? '');
      if (!result.ok) {
        schemaHint.value = result.message ?? '';
        return;
      }
    }
    modalApi.lock();
    try {
      const payload: AiServiceApi.ServiceSaveReq = {
        appId: values.appId,
        code: values.code,
        evalThreshold: values.evalThreshold ?? 0,
        id: values.id,
        inputSchema: values.inputSchema,
        modelEndpointId: values.modelEndpointId,
        name: values.name,
        promptTemplate: values.promptTemplate,
        requiredCapabilities: values.requiredCapabilities,
        runSubjectType: values.runSubjectType,
        version: values.version,
      };
      if (values.outputSchema) {
        payload.outputSchema = values.outputSchema;
      }
      if (values.id) {
        await updateService(payload);
        showSuccessMessage($t('ui.actionMessage.operationSuccess'));
      } else {
        const id = await createService(payload);
        formData.value = await getService(id);
        await loadBindings(id);
        showSuccessMessage('草稿已创建，可继续绑定资源');
      }
      emit('success');
    } finally {
      modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      formData.value = undefined;
      bindings.value = [];
      schemaHint.value = '';
      return;
    }
    const row = modalApi.getData<AiServiceApi.Service>();
    if (!row?.id) {
      await formApi.resetForm();
      modalApi.setState({ title: $t('ui.actionTitle.create', ['服务']) });
      return;
    }
    modalApi.setState({ title: $t('ui.actionTitle.edit', ['服务']) });
    const detail = await getService(row.id);
    formData.value = detail;
    await formApi.setValues({
      appId: detail.appId,
      code: detail.code,
      evalThreshold: detail.evalThreshold,
      id: detail.id,
      inputSchema: detail.inputSchema,
      modelEndpointId: detail.modelEndpointId,
      name: detail.name,
      outputSchema: detail.outputSchema,
      promptTemplate: detail.promptTemplate,
      requiredCapabilities: detail.requiredCapabilities,
      runSubjectType: detail.runSubjectType,
      version: detail.version,
    });
    await loadBindings(detail.id);
  },
});
</script>

<template>
  <Modal class="w-[860px]">
    <Form />
    <p v-if="schemaHint" class="pl-[110px] text-xs text-destructive">
      {{ schemaHint }}（后端仍会拒绝非法 Schema）
    </p>
    <section v-if="serviceId" class="mt-4 border-t pt-3">
      <h4 class="mb-2 text-sm font-medium">资源绑定（越权绑定会被拒绝）</h4>
      <ul class="mb-2 space-y-1 text-xs">
        <li
          v-for="binding in bindings"
          :key="binding.id"
          class="flex items-center gap-2"
        >
          <span>{{ binding.resourceType }}：{{ binding.resourceKey }}</span>
          <span class="text-muted-foreground">
            {{ binding.actions.join('、') }} · {{ binding.status }}
          </span>
          <button
            v-if="binding.status === 'ACTIVE'"
            class="text-destructive"
            type="button"
            @click="handleUnbind(binding)"
          >
            解绑
          </button>
        </li>
        <li v-if="bindings.length === 0" class="text-muted-foreground">
          尚未绑定资源
        </li>
      </ul>
      <div class="flex flex-wrap items-center gap-2">
        <select
          v-model="bindingForm.resourceType"
          class="rounded border p-1 text-xs"
        >
          <option value="REPORT">报表</option>
          <option value="KNOWLEDGE_BASE">知识库</option>
          <option value="FILE">文件</option>
          <option value="TOOL">工具</option>
          <option value="DATASET">数据集</option>
        </select>
        <input
          v-model="bindingForm.resourceKey"
          class="rounded border p-1 text-xs"
          placeholder="资源标识"
        />
        <select
          v-model="bindingForm.actions"
          class="rounded border p-1 text-xs"
          multiple
        >
          <option
            v-for="action in RESOURCE_ACTION_OPTIONS"
            :key="action.value"
            :value="action.value"
          >
            {{ action.label }}
          </option>
        </select>
        <button class="text-xs text-primary" type="button" @click="handleBind">
          绑定资源
        </button>
      </div>
    </section>
  </Modal>
</template>
