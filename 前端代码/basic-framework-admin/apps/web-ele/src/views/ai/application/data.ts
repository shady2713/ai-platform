import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { AiDiscoveryApi } from '#/api/ai/application/discovery';

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

/** 跨系统联邦权限码（与 V91 迁移的 system_menu 种子 4016 一一对应） */
export const AI_FEDERATION_PERMISSIONS = {
  manage: 'ai:application:federation',
} as const;

/** 分析范围模式选项（与服务端 AiAnalysisScopeMode 一一对应） */
export const ANALYSIS_SCOPE_MODE_OPTIONS: Array<{
  label: string;
  value: AiDiscoveryApi.AnalysisScopeMode;
}> = [
  { label: '仅当前系统', value: 'CURRENT_SYSTEM' },
  { label: '跨系统分析', value: 'CROSS_SYSTEM' },
];

/** 主体类型选项（USER 需要外部用户标识，APP 主体不用） */
export const SUBJECT_TYPE_OPTIONS: Array<{
  label: string;
  value: AiDiscoveryApi.SubjectType;
}> = [
  { label: '用户主体', value: 'USER' },
  { label: '应用主体', value: 'APP' },
];

/** 联邦映射状态说明（批准前不参与授权发现） */
export const FEDERATION_STATUS_LABELS: Record<string, string> = {
  APPROVED: '已批准（参与发现）',
  PENDING: '待独立审批（不参与发现）',
  REVOKED: '已撤销（不参与发现）',
};

/** 目录被拒绝时的固定提示：不区分"未登记/已停用/没有授权"（防枚举） */
export const DISCOVERY_DENIED_HINT =
  '当前主体在平台没有任何可访问系统：可能是未登记、已停用或没有任何有效授权。平台不区分这几种情况，也不返回"存在但无权"的系统。';

/**
 * 独立审批提示：拥有应用修改权不等于拥有跨系统身份映射权，
 * 且批准人必须不同于提交人（服务端按登录态校验，前端不能自报批准人）。
 */
export const FEDERATION_APPROVAL_HINT =
  '联邦映射需要独立审批：提交后由另一位持有 ai:application:federation 权限的操作员批准才生效；批准前目标系统不会出现在任何人的授权发现里。批准人由服务端按登录态判定，前端不提交批准人。';

/** 不按同名推断提示（卡片逐步实施第 3 条） */
export const FEDERATION_NO_INFERENCE_HINT =
  '两个应用里 externalUserId 相同**不代表**同一个人：映射必须逐对显式登记，平台绝不按同名或显示名自动关联。';

/** 跨系统选择的显式性提示 */
export const CROSS_SYSTEM_SELECTION_HINT =
  '跨系统分析必须显式包含当前系统与至少一个其它系统；范围选择会固定当时的目录指纹，之后授权或映射变化时核验会失败并需要重新发现。';

/** 模式文案；未知模式原样返回（前端不猜语义） */
export function analysisScopeModeLabel(mode: string): string {
  return (
    ANALYSIS_SCOPE_MODE_OPTIONS.find((option) => option.value === mode)
      ?.label ?? mode
  );
}

/** 状态文案；未知状态原样返回 */
export function federationStatusLabel(status: string): string {
  return FEDERATION_STATUS_LABELS[status] ?? status;
}

/** 单条范围的展示文本（资源类型/标识 + 动作） */
export function formatScopeRow(scope: AiDiscoveryApi.SystemScopeRow): string {
  const actions = (scope.actions ?? []).join('/');
  return actions.length > 0
    ? `${scope.resourceType}/${scope.resourceKey}（${actions}）`
    : `${scope.resourceType}/${scope.resourceKey}`;
}

/** 系统条目的范围摘要（空范围显示"无可访问范围"，不隐藏事实） */
export function scopeSummary(entry: AiDiscoveryApi.SystemEntry): string {
  const rows = entry.scopes ?? [];
  return rows.length === 0
    ? '无可访问范围'
    : rows.map((row) => formatScopeRow(row)).join('；');
}

/** 联邦映射的身份展示文本（来源 → 目标） */
export function federationIdentityText(
  federation: AiDiscoveryApi.Federation,
): string {
  return `${identityText(federation.sourceSubjectType, federation.sourceExternalUserId)} → ${identityText(
    federation.targetSubjectType,
    federation.targetExternalUserId,
  )}`;
}

function identityText(subjectType: string, externalUserId?: string): string {
  return subjectType === 'APP'
    ? 'APP 主体'
    : `${subjectType}:${externalUserId ?? ''}`;
}

/** 发现表单：身份只用于查询，不参与任何授权判定 */
export function useDiscoveryFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'subjectType',
      label: '主体类型',
      component: 'Select',
      componentProps: {
        options: SUBJECT_TYPE_OPTIONS,
        placeholder: '请选择主体类型',
      },
      defaultValue: 'USER',
    },
    {
      fieldName: 'externalUserId',
      label: '外部用户标识',
      component: 'Input',
      componentProps: {
        maxlength: 128,
        placeholder: '业务系统签发的外部用户标识（APP 主体留空）',
      },
    },
  ];
}

/** 联邦登记表单：六段身份 + 目标应用编号 */
export function useFederationFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'sourceSubjectType',
      label: '来源主体类型',
      component: 'Select',
      componentProps: { options: SUBJECT_TYPE_OPTIONS },
      defaultValue: 'USER',
      rules: 'required',
    },
    {
      fieldName: 'sourceExternalUserId',
      label: '来源外部用户标识',
      component: 'Input',
      componentProps: { maxlength: 128, placeholder: '当前系统里的用户标识' },
    },
    {
      fieldName: 'targetApplicationId',
      label: '目标应用编号',
      component: 'Input',
      componentProps: { placeholder: '被联邦的另一个应用（系统）编号' },
      rules: 'required',
    },
    {
      fieldName: 'targetSubjectType',
      label: '目标主体类型',
      component: 'Select',
      componentProps: { options: SUBJECT_TYPE_OPTIONS },
      defaultValue: 'USER',
      rules: 'required',
    },
    {
      fieldName: 'targetExternalUserId',
      label: '目标外部用户标识',
      component: 'Input',
      componentProps: { maxlength: 128, placeholder: '目标系统里的用户标识' },
    },
  ];
}
