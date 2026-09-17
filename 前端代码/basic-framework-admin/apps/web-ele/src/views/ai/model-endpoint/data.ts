import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';

import { z } from '@vben/common-ui';

/** 端点权限码（与 V48/V49 迁移的 system_menu 种子一一对应，页面按钮只按权限码显隐） */
export const AI_MODEL_ENDPOINT_PERMISSIONS = {
  query: 'ai:model-endpoint:query',
  create: 'ai:model-endpoint:create',
  update: 'ai:model-endpoint:update',
  delete: 'ai:model-endpoint:delete',
  probe: 'ai:model-endpoint:probe',
} as const;

/** 支持的能力词汇（与后端 ModelCapability 一致；后续多模态按同一枚举扩展） */
export const CAPABILITY_OPTIONS = [
  { label: '文本生成', value: 'TEXT' },
  { label: '文本流式生成', value: 'TEXT_STREAM' },
  { label: '结构化输出', value: 'STRUCTURED_OUTPUT' },
  { label: '工具调用', value: 'TOOL_CALLING' },
  { label: '文本嵌入', value: 'EMBEDDING' },
];

/** 探测状态展示文案 */
export const PROBE_STATUS_OPTIONS = [
  { label: '可用', value: 'SUPPORTED', color: 'success' },
  { label: '不支持', value: 'UNSUPPORTED', color: 'info' },
  { label: '探测失败', value: 'FAILED', color: 'danger' },
];

/** 探测类型展示文案 */
export const PROBE_KIND_LABELS: Record<string, string> = {
  CONNECTIVITY: '连接',
  TEXT: '文本',
  TEXT_STREAM: '流式文本',
  STRUCTURED_OUTPUT: '结构化输出',
  TOOL_CALLING: '工具调用',
  EMBEDDING: '嵌入',
};

/** 提供方标识（当前只支持 OpenAI 兼容协议） */
export const PROVIDER_OPTIONS = [
  { label: 'OpenAI 兼容', value: 'openai_compatible' },
];

/** 列表查询表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'name',
      label: '端点名称',
      component: 'Input',
      componentProps: { clearable: true, placeholder: '请输入端点名称' },
    },
    {
      fieldName: 'provider',
      label: '提供方',
      component: 'Select',
      componentProps: {
        clearable: true,
        options: PROVIDER_OPTIONS,
        placeholder: '请选择提供方',
      },
    },
  ];
}

/** 新增/修改表单 */
export function useFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      fieldName: 'id',
      dependencies: { triggerFields: [''], show: () => false },
    },
    {
      fieldName: 'name',
      label: '端点名称',
      component: 'Input',
      componentProps: { maxlength: 128, placeholder: '请输入端点名称' },
      rules: z
        .string()
        .min(1, '请输入端点名称')
        .max(128, '端点名称不能超过128个字符'),
    },
    {
      fieldName: 'provider',
      label: '提供方',
      component: 'Select',
      componentProps: {
        options: PROVIDER_OPTIONS,
        placeholder: '请选择提供方',
      },
      rules: 'required',
    },
    {
      fieldName: 'baseUrl',
      label: '基础地址',
      component: 'Input',
      componentProps: {
        maxlength: 512,
        placeholder: 'https://api.example.com/v1（必须在出站允许清单内）',
      },
      rules: z
        .string()
        .min(1, '请输入基础地址')
        .max(512, '基础地址不能超过512个字符')
        .startsWith('https://', '基础地址必须使用 https'),
    },
    {
      fieldName: 'modelId',
      label: '模型标识',
      component: 'Input',
      componentProps: { maxlength: 128, placeholder: '例如 gpt-4o-mini' },
      rules: z
        .string()
        .min(1, '请输入模型标识')
        .max(128, '模型标识不能超过128个字符'),
    },
    {
      fieldName: 'capabilities',
      label: '能力',
      component: 'Select',
      componentProps: {
        multiple: true,
        options: CAPABILITY_OPTIONS,
        placeholder: '请选择平台需要的能力',
      },
      rules: 'required',
    },
    {
      fieldName: 'credential',
      label: '凭据',
      component: 'InputPassword',
      componentProps: {
        maxlength: 2048,
        placeholder: '留空表示保留已有凭据',
        autocomplete: 'new-password',
      },
      // 凭据只提交不回显：编辑时不带出旧值，留空即保留
      rules: z.string().max(2048, '凭据不能超过2048个字符').optional(),
    },
    {
      fieldName: 'version',
      label: '版本',
      component: 'Input',
      dependencies: { triggerFields: [''], show: () => false },
    },
  ];
}

/** 轮换凭据表单 */
export function useRotateSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'credential',
      label: '新凭据',
      component: 'InputPassword',
      componentProps: {
        maxlength: 2048,
        placeholder: '请输入新凭据（保存后不再回显）',
        autocomplete: 'new-password',
      },
      rules: z
        .string()
        .min(1, '请输入新凭据')
        .max(2048, '凭据不能超过2048个字符'),
    },
  ];
}

/** 列表列 */
export function useGridColumns(): VxeTableGridOptions['columns'] {
  return [
    { field: 'name', title: '端点名称', minWidth: 160 },
    { field: 'provider', title: '提供方', width: 140 },
    { field: 'modelId', title: '模型标识', minWidth: 160 },
    {
      field: 'baseUrl',
      title: '基础地址',
      minWidth: 220,
      showOverflow: 'tooltip',
    },
    {
      field: 'capabilities',
      title: '能力',
      minWidth: 200,
      formatter: ({ cellValue }) =>
        Array.isArray(cellValue) ? cellValue.join('、') : '',
    },
    {
      field: 'credentialConfigured',
      title: '凭据',
      width: 100,
      formatter: ({ cellValue }) => (cellValue ? '已配置' : '未配置'),
    },
    {
      field: 'enabled',
      title: '状态',
      width: 100,
      formatter: ({ cellValue }) => (cellValue ? '启用' : '停用'),
    },
    {
      field: 'configRevision',
      title: '配置版本',
      width: 100,
      formatter: ({ cellValue }) => `v${cellValue}`,
    },
  ];
}
