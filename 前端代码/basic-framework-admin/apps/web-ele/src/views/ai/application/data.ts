import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';

import { z } from '@vben/common-ui';

/** 应用权限码（与 V50 迁移的 system_menu 种子一一对应；无权用户看不到授权/轮换入口） */
export const AI_APPLICATION_PERMISSIONS = {
  create: 'ai:application:create',
  delete: 'ai:application:delete',
  query: 'ai:application:query',
  revoke: 'ai:application:revoke',
  rotate: 'ai:application:rotate',
  update: 'ai:application:update',
} as const;

/** 授权权限码（与 V52 迁移的 system_menu 种子一一对应） */
export const AI_GRANT_PERMISSIONS = {
  create: 'ai:grant:create',
  query: 'ai:grant:query',
  revoke: 'ai:grant:revoke',
  update: 'ai:grant:update',
} as const;

/**
 * 精确 Origin 校验（与后端 ApplicationOrigins 规则一致）：
 * 只接受 scheme://host[:port]，禁止路径、查询、通配与用户信息。
 */
export const EXACT_ORIGIN_PATTERN = /^https?:\/\/[a-z0-9.-]+(:\d{1,5})?$/i;

/** 解析多行 Origin 文本（每行一个），去空行 */
export function parseOrigins(text: string): string[] {
  return text
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
}

/** 一次性秘密提示文案（与后端"不保存明文"语义一致） */
export const SECRET_ONCE_TITLE = '客户端秘密（仅此一次）';

export const SECRET_ONCE_WARNING =
  '平台只保存摘要，关闭后无法再次查看；请立即复制并妥善保管。轮换凭据会让旧凭据立即失效。';

/** 授权变更作用范围提示（A09：授权变更提示作用范围） */
export const GRANT_SCOPE_WARNING =
  '授权变更立即生效：撤销后新请求、后续工具步骤与历史产物读取都会被拒绝；修改动作白名单会递增授权版本，历史产物需要重新鉴权。';

/** 列表查询表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'appCode',
      label: '应用标识',
      component: 'Input',
      componentProps: { clearable: true, placeholder: '请输入应用标识' },
    },
    {
      fieldName: 'enabled',
      label: '状态',
      component: 'Select',
      componentProps: {
        clearable: true,
        options: [
          { label: '启用', value: true },
          { label: '停用', value: false },
        ],
        placeholder: '请选择状态',
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
      fieldName: 'appCode',
      label: '应用标识',
      component: 'Input',
      componentProps: {
        maxlength: 64,
        placeholder: '小写字母开头（3-64 位），创建后不可修改',
        disabled: false,
      },
      rules: z
        .string()
        .min(3, '应用标识至少 3 位')
        .max(64, '应用标识不能超过 64 个字符')
        .regex(
          /^[a-z][a-z0-9_-]{2,63}$/,
          '只允许小写字母、数字、下划线与连字符',
        ),
    },
    {
      fieldName: 'name',
      label: '应用名称',
      component: 'Input',
      componentProps: { maxlength: 128, placeholder: '请输入应用名称' },
      rules: z
        .string()
        .min(1, '请输入应用名称')
        .max(128, '应用名称不能超过 128 个字符'),
    },
    {
      fieldName: 'description',
      label: '应用说明',
      component: 'Input',
      componentProps: { maxlength: 512, placeholder: '选填' },
    },
    {
      fieldName: 'originsText',
      label: '精确 Origin',
      component: 'Input',
      componentProps: {
        type: 'textarea',
        rows: 3,
        placeholder: '每行一个，例如：\nhttps://crm.example.com',
      },
      help: '只接受精确来源（scheme://host[:port]），禁止路径、通配与查询参数',
      rules: z
        .string()
        .refine(
          (text) =>
            parseOrigins(text ?? '').length > 0 &&
            parseOrigins(text ?? '').every((origin) =>
              EXACT_ORIGIN_PATTERN.test(origin),
            ),
          '每行必须是一个精确 Origin（https://host[:port]）',
        ),
    },
    {
      fieldName: 'credential',
      label: '初始凭据',
      component: 'InputPassword',
      componentProps: {
        maxlength: 2048,
        placeholder: '留空表示由平台生成首个凭据',
        autocomplete: 'new-password',
      },
      help: '创建成功后只显示一次；编辑时留空表示不改动',
      rules: z.string().max(2048, '凭据不能超过 2048 个字符').optional(),
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
    { field: 'appCode', title: '应用标识', minWidth: 160 },
    { field: 'name', title: '应用名称', minWidth: 160 },
    {
      field: 'origins',
      title: '精确 Origin',
      minWidth: 240,
      showOverflow: 'tooltip',
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
  ];
}
