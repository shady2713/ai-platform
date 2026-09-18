import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';

import { z } from '@vben/common-ui';

/** 授权页面复用的权限码（与 V52 种子一致；无权用户看不到授权/撤销入口） */
export const PERMISSIONS = {
  create: 'ai:grant:create',
  query: 'ai:grant:query',
  revoke: 'ai:grant:revoke',
  update: 'ai:grant:update',
} as const;

/** 授权变更作用范围提示（A09：授权变更提示作用范围） */
export const SCOPE_WARNING =
  '授权变更立即生效：撤销后新请求、后续工具步骤与历史产物读取都会被拒绝；修改动作白名单会递增授权版本，历史产物需要重新鉴权。';

/** 新增资源标识的二次确认文案（避免误把不存在的键授权出去） */
export const NEW_RESOURCE_CONFIRM =
  '该资源标识不在该主体已有的授权资源里：确认要为它新建授权？确认后会立即生效。';

/** 动作词表（与后端 AiAction 一致；白名单外动作会被后端拒绝） */
export const GRANT_ACTIONS = [
  { label: '读取', value: 'READ' },
  { label: '执行', value: 'EXECUTE' },
  { label: '导出', value: 'EXPORT' },
];

/** 资源类型词表（与后端 AiResourceType 一致） */
export const GRANT_RESOURCE_TYPES = [
  { label: '报表', value: 'REPORT' },
  { label: '知识库', value: 'KNOWLEDGE_BASE' },
  { label: '文件', value: 'FILE' },
  { label: '工具', value: 'TOOL' },
  { label: '数据集', value: 'DATASET' },
];

/** 授权列表查询 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'applicationId',
      label: '应用编号',
      component: 'Input',
      componentProps: { placeholder: '请输入应用编号' },
    },
    {
      fieldName: 'subjectType',
      label: '主体类型',
      component: 'Select',
      componentProps: {
        clearable: true,
        options: [
          { label: '应用', value: 'APP' },
          { label: '用户', value: 'USER' },
        ],
        placeholder: '请选择主体类型',
      },
    },
    {
      fieldName: 'externalUserId',
      label: '外部用户标识',
      component: 'Input',
      componentProps: { clearable: true, placeholder: '支持模糊匹配' },
    },
    {
      fieldName: 'resourceType',
      label: '资源类型',
      component: 'Select',
      componentProps: {
        clearable: true,
        options: GRANT_RESOURCE_TYPES,
        placeholder: '请选择资源类型',
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
      fieldName: 'applicationId',
      label: '应用编号',
      component: 'Input',
      componentProps: { placeholder: '被授权主体所在应用' },
      rules: z
        .string()
        .regex(/^\d+$/, '应用编号必须是数字')
        .min(1, '请输入应用编号'),
    },
    {
      fieldName: 'subjectType',
      label: '主体类型',
      component: 'Select',
      componentProps: {
        options: [
          { label: '应用', value: 'APP' },
          { label: '用户', value: 'USER' },
        ],
      },
      rules: 'required',
    },
    {
      fieldName: 'externalUserId',
      label: '外部用户标识',
      component: 'Input',
      componentProps: { maxlength: 128, placeholder: '主体为应用时留空' },
    },
    {
      fieldName: 'resourceType',
      label: '资源类型',
      component: 'Select',
      componentProps: { options: GRANT_RESOURCE_TYPES },
      rules: 'required',
    },
    {
      fieldName: 'resourceKey',
      label: '资源标识',
      component: 'Input',
      componentProps: { maxlength: 128, placeholder: '例如 report-1' },
      rules: z
        .string()
        .min(1, '请输入资源标识')
        .max(128, '资源标识不能超过 128 个字符'),
    },
    {
      fieldName: 'actions',
      label: '动作白名单',
      component: 'Select',
      componentProps: { multiple: true, options: GRANT_ACTIONS },
      rules: 'required',
    },
    {
      fieldName: 'version',
      label: '版本',
      component: 'Input',
      dependencies: { triggerFields: [''], show: () => false },
    },
  ];
}

/** 列表列 */
export function useGridColumns(): VxeTableGridOptions['columns'] {
  return [
    { field: 'applicationId', title: '应用编号', width: 110 },
    { field: 'subjectType', title: '主体类型', width: 100 },
    { field: 'externalUserId', title: '外部用户', minWidth: 140 },
    { field: 'resourceType', title: '资源类型', width: 130 },
    { field: 'resourceKey', title: '资源标识', minWidth: 160 },
    {
      field: 'actions',
      title: '动作白名单',
      minWidth: 160,
      formatter: ({ cellValue }) =>
        Array.isArray(cellValue) ? cellValue.join('、') : '',
    },
    { field: 'status', title: '状态', width: 100 },
    { field: 'authzRevision', title: '授权版本', width: 100 },
  ];
}

/**
 * 多选资源只列有权项（A09）：资源标识选项来自该主体**已有授权**的资源，
 * 新标识必须显式输入并二次确认，避免凭猜测越权代授。
 */
export function buildResourceOptions(
  existingGrants: Array<{ resourceKey: string; resourceType: string }>,
): Array<{ label: string; value: string }> {
  const seen = new Set<string>();
  const options: Array<{ label: string; value: string }> = [];
  for (const grant of existingGrants) {
    if (seen.has(grant.resourceKey)) {
      continue;
    }
    seen.add(grant.resourceKey);
    options.push({
      label: `${grant.resourceKey}（已授权 · ${grant.resourceType}）`,
      value: grant.resourceKey,
    });
  }
  return options;
}
