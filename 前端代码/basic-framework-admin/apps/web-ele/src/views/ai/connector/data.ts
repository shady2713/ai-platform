import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { AiConnectorApi } from '#/api/ai/data';

import { z } from '@vben/common-ui';

/**
 * 连接器权限码（与 V66/V67 迁移的 system_menu 种子一一对应）：
 * 没有探测权就看不到"连接测试"，没有导入/发布权就看不到接口管理；后端仍会二次校验。
 */
export const AI_CONNECTOR_PERMISSIONS = {
  create: 'ai:connector:create',
  delete: 'ai:connector:delete',
  import: 'ai:connector:import',
  operation: 'ai:connector:operation',
  probe: 'ai:connector:probe',
  query: 'ai:connector:query',
  update: 'ai:connector:update',
} as const;

/** 连接器类型（与后端 AiConnectorDO 一致） */
export const CONNECTOR_TYPE_OPTIONS = [
  { label: 'HTTP 接口', value: 'HTTP' },
  { label: 'MySQL 只读库', value: 'MYSQL' },
];

/** 启停状态 */
export const CONNECTOR_STATUS_OPTIONS = [
  { label: '启用', value: 'ENABLED' },
  { label: '停用', value: 'DISABLED' },
];

/** 连接测试结论（稳定词表） */
export const PROBE_STATUS_OPTIONS = [
  { label: '可用', value: 'SUPPORTED' },
  { label: '失败', value: 'FAILED' },
];

/** 操作状态（草稿不可执行） */
export const OPERATION_STATUS_OPTIONS = [
  { label: '草稿', value: 'DRAFT' },
  { label: '已发布', value: 'PUBLISHED' },
];

/** 表格列 */
export function useGridColumns(): VxeTableGridOptions<AiConnectorApi.Connector>['columns'] {
  return [
    { field: 'code', title: '标识', minWidth: 140 },
    { field: 'name', title: '名称', minWidth: 140 },
    {
      field: 'connectorType',
      title: '类型',
      width: 120,
      formatter: ({ cellValue }) =>
        cellValue === 'MYSQL' ? 'MySQL 只读库' : 'HTTP 接口',
    },
    {
      field: 'status',
      title: '状态',
      width: 90,
      formatter: ({ cellValue }) => (cellValue === 'ENABLED' ? '启用' : '停用'),
    },
    {
      field: 'credentialConfigured',
      title: '秘密',
      width: 100,
      formatter: ({ cellValue }) => (cellValue ? '已配置' : '未配置'),
    },
    { field: 'configJson', title: '声明式配置', minWidth: 220 },
    { field: 'version', title: '版本', width: 80 },
    { field: 'createTime', title: '创建时间', width: 170 },
    {
      title: '操作',
      width: 320,
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
      componentProps: { placeholder: '请输入连接器标识' },
      fieldName: 'code',
      label: '标识',
    },
    {
      component: 'Select',
      componentProps: {
        options: CONNECTOR_TYPE_OPTIONS,
        placeholder: '请选择类型',
      },
      fieldName: 'connectorType',
      label: '类型',
    },
    {
      component: 'Select',
      componentProps: {
        options: CONNECTOR_STATUS_OPTIONS,
        placeholder: '请选择状态',
      },
      fieldName: 'status',
      label: '状态',
    },
  ];
}

/**
 * 新增/修改表单：只接受**声明式结构化字段**（HTTP 基址/方法/认证方式；
 * MySQL 主机/端口/库/用户名/SSL 模式/授权对象），没有整段连接串输入框，
 * 也没有任何 SQL/脚本输入面。
 */
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
      componentProps: { placeholder: '如 crm-readonly' },
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
      componentProps: { placeholder: '如 CRM 只读库' },
      fieldName: 'name',
      label: '名称',
      rules: z.string().min(1, '请输入名称').max(128, '名称最多 128 个字符'),
    },
    {
      component: 'Select',
      componentProps: {
        options: CONNECTOR_TYPE_OPTIONS,
        placeholder: '请选择类型',
      },
      fieldName: 'connectorType',
      label: '类型',
      rules: 'selectRequired',
    },
    {
      component: 'RadioGroup',
      componentProps: {
        buttonStyle: 'solid',
        options: [
          { label: 'HTTP', value: 'HTTP' },
          { label: 'MySQL', value: 'MYSQL' },
        ],
      },
      defaultValue: 'HTTP',
      dependencies: { triggerFields: ['connectorType'] },
      fieldName: 'configKind',
      label: '配置形态',
    },
    {
      component: 'Input',
      componentProps: { placeholder: 'https://crm.example.com' },
      dependencies: {
        show: (values) => values.configKind === 'HTTP',
        triggerFields: ['configKind'],
      },
      fieldName: 'baseUrl',
      label: '基址（https）',
      rules: z
        .string()
        .url('必须是合法 URL')
        .startsWith('https://', '必须使用 https'),
    },
    {
      component: 'Select',
      componentProps: {
        options: [
          { label: 'GET', value: 'GET' },
          { label: 'POST', value: 'POST' },
        ],
      },
      dependencies: {
        show: (values) => values.configKind === 'HTTP',
        triggerFields: ['configKind'],
      },
      fieldName: 'method',
      label: '默认方法',
    },
    {
      component: 'Input',
      componentProps: { placeholder: 'db.internal' },
      dependencies: {
        show: (values) => values.configKind === 'MYSQL',
        triggerFields: ['configKind'],
      },
      fieldName: 'host',
      label: '主机',
    },
    {
      component: 'InputNumber',
      componentProps: { max: 65_535, min: 1 },
      dependencies: {
        show: (values) => values.configKind === 'MYSQL',
        triggerFields: ['configKind'],
      },
      fieldName: 'port',
      label: '端口',
      defaultValue: 3306,
    },
    {
      component: 'Input',
      componentProps: { placeholder: 'crm' },
      dependencies: {
        show: (values) => values.configKind === 'MYSQL',
        triggerFields: ['configKind'],
      },
      fieldName: 'database',
      label: '库名',
    },
    {
      component: 'Input',
      componentProps: { placeholder: 'readonly' },
      dependencies: {
        show: (values) => values.configKind === 'MYSQL',
        triggerFields: ['configKind'],
      },
      fieldName: 'username',
      label: '只读账号',
    },
    {
      component: 'Select',
      componentProps: {
        options: [
          { label: '加密（REQUIRED）', value: 'REQUIRED' },
          { label: '校验证书（VERIFY_IDENTITY）', value: 'VERIFY_IDENTITY' },
          { label: '明文（DISABLED）', value: 'DISABLED' },
        ],
      },
      defaultValue: 'REQUIRED',
      dependencies: {
        show: (values) => values.configKind === 'MYSQL',
        triggerFields: ['configKind'],
      },
      fieldName: 'sslMode',
      label: '传输模式',
    },
    {
      component: 'Input',
      componentProps: {
        placeholder: '每行一个：crm.orders',
        rows: 3,
        type: 'textarea',
      },
      dependencies: {
        show: (values) => values.configKind === 'MYSQL',
        triggerFields: ['configKind'],
      },
      fieldName: 'allowedObjects',
      label: '授权对象（每行一个 schema.table）',
    },
    {
      component: 'InputPassword',
      componentProps: { placeholder: '修改时留空表示保留' },
      fieldName: 'credential',
      label: '秘密',
    },
  ];
}

/** 表单值 → 声明式 configJson（结构化字段，绝不拼连接串） */
export function buildConfigJson(values: Record<string, unknown>): string {
  if (values.configKind === 'MYSQL') {
    const allowedObjects = String(values.allowedObjects ?? '')
      .split('\n')
      .map((item) => item.trim())
      .filter((item) => item.length > 0);
    const config: Record<string, unknown> = {
      database: values.database,
      host: values.host,
      port: values.port,
      sslMode: values.sslMode ?? 'REQUIRED',
      username: values.username,
    };
    if (allowedObjects.length > 0) {
      config.allowedObjects = allowedObjects;
    }
    return JSON.stringify(config);
  }
  return JSON.stringify({
    authType: values.credential ? 'BEARER' : 'NONE',
    baseUrl: values.baseUrl,
    method: values.method ?? 'GET',
  });
}
