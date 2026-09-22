<script lang="ts" setup>
/**
 * 连接器表单（D10）：只编辑声明式结构化字段。
 *
 * 界面刻意**不提供**整段连接串、任意 SQL 或脚本输入：秘密单独输入且不回显，
 * MySQL 只填写主机/端口/库/只读账号/传输模式/授权对象。
 */
import type { AiConnectorApi } from '#/api/ai/data';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { cloneDeep } from '@vben/utils';

import { useVbenForm } from '#/adapter/form';
import { createConnector, updateConnector } from '#/api/ai/data';
import { $t } from '#/locales';
import { showSuccessMessage } from '#/utils/feedback';

import { buildConfigJson, useFormSchema } from '../data';

const emit = defineEmits<{ success: [] }>();

const editingId = ref<number>();
const editingVersion = ref<number>();

const [Form, formApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' }, labelWidth: 160 },
  layout: 'horizontal',
  schema: useFormSchema(),
  showDefaultActions: false,
  wrapperClass: 'grid-cols-1',
});

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await formApi.validate();
    if (!valid) {
      return;
    }
    const values = cloneDeep(await formApi.getValues());
    const payload: AiConnectorApi.ConnectorSaveReq = {
      code: String(values.code),
      configJson: buildConfigJson(values),
      connectorType: String(values.connectorType),
      name: String(values.name),
    };
    if (values.credential) {
      payload.credential = String(values.credential);
    }
    if (editingId.value) {
      payload.id = editingId.value;
      payload.version = editingVersion.value;
      await updateConnector(payload);
      showSuccessMessage($t('ui.actionMessage.updateSuccess', [payload.name]));
    } else {
      await createConnector(payload);
      showSuccessMessage($t('ui.actionMessage.createSuccess', [payload.name]));
    }
    modalApi.close();
    emit('success');
  },
  onOpenChange(isOpen) {
    if (!isOpen) {
      return;
    }
    const data = modalApi.getData<AiConnectorApi.Connector>();
    if (data?.id) {
      editingId.value = data.id;
      editingVersion.value = data.version;
      modalApi.setState({ title: '编辑连接器' });
      const config = JSON.parse(data.configJson || '{}') as Record<
        string,
        unknown
      >;
      formApi.setValues({
        ...config,
        allowedObjects: Array.isArray(config.allowedObjects)
          ? (config.allowedObjects as string[]).join('\n')
          : '',
        code: data.code,
        configKind: data.connectorType === 'MYSQL' ? 'MYSQL' : 'HTTP',
        connectorType: data.connectorType,
        credential: '',
        id: data.id,
        name: data.name,
      });
      return;
    }
    editingId.value = undefined;
    editingVersion.value = undefined;
    modalApi.setState({ title: '新增连接器' });
    formApi.resetForm();
  },
});

const title = computed(() => (editingId.value ? '编辑连接器' : '新增连接器'));
</script>

<template>
  <Modal :title="title" class="w-[720px]">
    <Form />
    <p class="px-4 pb-2 text-xs text-muted-foreground">
      秘密只在写入时提交，接口永不回显；连接器不做任意
      SQL/脚本执行，只按已发布接口的声明参数取数。
    </p>
  </Modal>
</template>
