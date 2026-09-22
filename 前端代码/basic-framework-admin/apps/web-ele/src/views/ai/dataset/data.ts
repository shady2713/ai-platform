import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { AiDatasetApi } from '#/api/ai/data';

import { z } from '@vben/common-ui';

/**
 * 数据集权限码（与 V68 迁移的 system_menu 种子一一对应）：
 * 版本创建/验证/发布各自独立授权，后端仍会二次校验。
 */
export const AI_DATASET_PERMISSIONS = {
  create: 'ai:dataset:create',
  delete: 'ai:dataset:delete',
  query: 'ai:dataset:query',
  update: 'ai:dataset:update',
  versionCreate: 'ai:dataset:version:create',
  versionPublish: 'ai:dataset:version:publish',
  versionVerify: 'ai:dataset:version:verify',
} as const;

/** 启停状态 */
export const DATASET_STATUS_OPTIONS = [
  { label: '启用', value: 'ENABLED' },
  { label: '停用', value: 'DISABLED' },
];

/** 版本状态（发布后不可修改） */
export const DATASET_VERSION_STATUS_OPTIONS = [
  { label: '草稿', value: 'DRAFT' },
  { label: '已发布', value: 'PUBLISHED' },
];

/** 验证状态（漂移即"待验证"，必须重新验证后才能发布） */
export const DATASET_VERIFICATION_OPTIONS = [
  { label: '未验证', value: 'UNVERIFIED' },
  { label: '已验证', value: 'VERIFIED' },
  { label: '已漂移（待处理）', value: 'DRIFTED' },
];

/** 表格列 */
export function useGridColumns(): VxeTableGridOptions<AiDatasetApi.Dataset>['columns'] {
  return [
    { field: 'code', title: '标识', minWidth: 140 },
    { field: 'name', title: '名称', minWidth: 140 },
    { field: 'sourceObject', title: '来源对象', minWidth: 180 },
    { field: 'connectorId', title: '连接器', width: 100 },
    {
      field: 'status',
      title: '状态',
      width: 90,
      formatter: ({ cellValue }) => (cellValue === 'ENABLED' ? '启用' : '停用'),
    },
    { field: 'latestVersionNo', title: '最新版本', width: 100 },
    { field: 'publishedVersionNo', title: '已发布版本', width: 110 },
    { field: 'version', title: '版本', width: 80 },
    {
      title: '操作',
      width: 300,
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
      componentProps: { placeholder: '请输入数据集标识' },
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
        options: DATASET_STATUS_OPTIONS,
        placeholder: '请选择状态',
      },
      fieldName: 'status',
      label: '状态',
    },
  ];
}

/** 新增/修改表单：来源对象必须是连接器授权白名单内的 schema.table */
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
      componentProps: { placeholder: '如 crm-orders' },
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
      componentProps: { placeholder: '如 CRM 订单' },
      fieldName: 'name',
      label: '名称',
      rules: z.string().min(1, '请输入名称').max(128, '名称最多 128 个字符'),
    },
    {
      component: 'Input',
      componentProps: { placeholder: '连接器编号' },
      fieldName: 'connectorId',
      label: '连接器编号',
      rules: z.coerce.number().int().positive('请输入连接器编号'),
    },
    {
      component: 'Input',
      componentProps: { placeholder: 'crm.orders' },
      fieldName: 'sourceObject',
      label: '来源对象',
      rules: z.string().regex(/^\w+\.\w+$/, '必须是 schema.table'),
    },
    {
      component: 'Input',
      componentProps: { rows: 2, type: 'textarea' },
      fieldName: 'description',
      label: '说明',
    },
  ];
}

/** 版本表单：语义定义是**声明式 JSON**（字段/指标/维度/时间/单位/权限策略），不是 SQL */
export function useVersionFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      componentProps: {
        placeholder:
          '{"grain":"一行一单","fields":[{"name":"order_id","sourceColumn":"id","type":"NUMBER","unit":"COUNT","visibility":"PUBLIC"}],"metrics":[],"dimensions":[]}',
        rows: 8,
        type: 'textarea',
      },
      fieldName: 'definitionJson',
      label: '语义定义（JSON）',
      rules: z.string().min(2, '请输入语义定义'),
    },
  ];
}

/** 漂移/不可执行原因（只回列名与结论，不回上游数据） */
export function describeVerification(
  result: AiDatasetApi.DatasetVersionVerifyResp,
): string {
  if (result.publishable) {
    return result.addedColumns.length > 0
      ? `可发布（上游新增列：${result.addedColumns.join('、')}）`
      : '可发布';
  }
  const reasons: string[] = [];
  if (result.missingColumns.length > 0) {
    reasons.push(`上游缺少列：${result.missingColumns.join('、')}`);
  }
  if (result.typeChangedColumns.length > 0) {
    reasons.push(`类型不再兼容：${result.typeChangedColumns.join('、')}`);
  }
  return reasons.length > 0 ? `不可发布：${reasons.join('；')}` : '不可发布';
}
