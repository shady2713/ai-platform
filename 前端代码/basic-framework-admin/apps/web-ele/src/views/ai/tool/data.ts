import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { AiToolApi } from '#/api/ai/data';

import { z } from '@vben/common-ui';

/** 工具权限码（与 V70 迁移的 system_menu 种子一一对应） */
export const AI_TOOL_PERMISSIONS = {
  create: 'ai:tool:create',
  delete: 'ai:tool:delete',
  query: 'ai:tool:query',
  update: 'ai:tool:update',
  version: 'ai:tool:version',
} as const;

/** 执行政策（AUTO/CONFIRM/DENY；默认 DENY，界面必须显式选择） */
export const TOOL_POLICY_OPTIONS = [
  { label: 'DENY（禁止执行，默认）', value: 'DENY' },
  { label: 'CONFIRM（需人工确认）', value: 'CONFIRM' },
  { label: 'AUTO（自动执行）', value: 'AUTO' },
];

/** 工具类型（首期只允许发布读工具） */
export const TOOL_TYPE_OPTIONS = [
  { label: 'READ（只读）', value: 'READ' },
  { label: 'WRITE（写操作，首期不可发布）', value: 'WRITE' },
];

/** 启停状态 */
export const TOOL_STATUS_OPTIONS = [
  { label: '启用', value: 'ENABLED' },
  { label: '停用', value: 'DISABLED' },
];

/** 表格列 */
export function useGridColumns(): VxeTableGridOptions<AiToolApi.Tool>['columns'] {
  return [
    { field: 'code', title: '标识', minWidth: 140 },
    { field: 'name', title: '名称', minWidth: 140 },
    { field: 'connectorId', title: '连接器', width: 100 },
    {
      field: 'status',
      title: '状态',
      width: 90,
      formatter: ({ cellValue }) => (cellValue === 'ENABLED' ? '启用' : '停用'),
    },
    { field: 'latestVersionNo', title: '最新版本', width: 100 },
    { field: 'version', title: '版本', width: 80 },
    {
      title: '操作',
      width: 280,
      fixed: 'right',
      slots: { default: 'actions' },
    },
  ];
}

/** 搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      componentProps: { placeholder: '请输入工具标识' },
      fieldName: 'code',
      label: '标识',
    },
    {
      component: 'InputNumber',
      componentProps: { min: 1 },
      fieldName: 'connectorId',
      label: '连接器',
    },
    {
      component: 'Select',
      componentProps: {
        options: TOOL_STATUS_OPTIONS,
        placeholder: '请选择状态',
      },
      fieldName: 'status',
      label: '状态',
    },
  ];
}

/** 新增/修改表单 */
export function useFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      componentProps: { disabled: true },
      dependencies: { show: () => false, triggerFields: ['id'] },
      fieldName: 'id',
      label: 'id',
    },
    {
      component: 'Input',
      componentProps: { placeholder: '如 query-orders' },
      fieldName: 'code',
      label: '标识',
      rules: z
        .string()
        .min(3, '标识至少 3 个字符')
        .max(64, '标识最多 64 个字符')
        .regex(/^[A-Z][\w-]{2,63}$/i, '字母开头，仅字母数字与 -_'),
    },
    {
      component: 'Input',
      componentProps: { placeholder: '如 查询订单' },
      fieldName: 'name',
      label: '名称',
      rules: z.string().min(1, '请输入名称').max(128, '名称最多 128 个字符'),
    },
    {
      component: 'InputNumber',
      componentProps: { min: 1 },
      fieldName: 'connectorId',
      label: '连接器编号',
      rules: z.coerce.number().int().positive('请输入连接器编号'),
    },
    {
      component: 'Input',
      componentProps: { rows: 2, type: 'textarea' },
      fieldName: 'description',
      label: '说明（供模型理解用途）',
    },
  ];
}

/** 版本表单：政策与来源都显式选择/填写，输入输出 schema 是声明式 JSON */
export function useVersionFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Select',
      componentProps: { options: TOOL_TYPE_OPTIONS },
      defaultValue: 'READ',
      fieldName: 'toolType',
      label: '类型',
    },
    {
      component: 'Select',
      componentProps: { options: TOOL_POLICY_OPTIONS },
      defaultValue: 'DENY',
      fieldName: 'policy',
      label: '执行政策',
    },
    {
      component: 'Input',
      componentProps: { disabled: true },
      defaultValue: 'HTTP_OPERATION',
      fieldName: 'sourceKind',
      label: '来源类型',
    },
    {
      component: 'Input',
      componentProps: { placeholder: 'getOrders（已发布的 operationKey）' },
      fieldName: 'sourceRef',
      label: '来源操作',
      rules: z.string().min(1, '请填写已发布的 operationKey'),
    },
    {
      component: 'Input',
      componentProps: {
        placeholder: '{"region":{"type":"string","required":true}}',
        rows: 4,
        type: 'textarea',
      },
      fieldName: 'inputSchemaJson',
      label: '输入 schema',
      rules: z.string().min(2, '请输入输入 schema'),
    },
    {
      component: 'Input',
      componentProps: {
        placeholder: '{"columns":[]}',
        rows: 3,
        type: 'textarea',
      },
      fieldName: 'outputSchemaJson',
      label: '输出 schema',
      rules: z.string().min(2, '请输入输出 schema'),
    },
  ];
}

/** 政策与类型的可读说明（界面用文字把"默认拒绝"讲清楚） */
export function describePolicy(policy: string, toolType: string): string {
  let policyText = '禁止执行（默认）';
  if (policy === 'AUTO') {
    policyText = '自动执行';
  } else if (policy === 'CONFIRM') {
    policyText = '需人工确认后执行';
  }
  const typeText = toolType === 'READ' ? '只读工具' : '写工具';
  return `${typeText} · ${policyText}`;
}
