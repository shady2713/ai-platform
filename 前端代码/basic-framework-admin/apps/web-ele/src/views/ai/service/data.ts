import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { AiServiceApi } from '#/api/ai/service';

import { z } from '@vben/common-ui';

/**
 * 服务权限码（与 V56–V58 迁移的 system_menu 种子一一对应）：
 * 没有发布权（activate）的用户看不到发布/回退入口，后端仍会二次校验。
 */
export const AI_SERVICE_PERMISSIONS = {
  activate: 'ai:service:activate',
  bind: 'ai:service:bind',
  create: 'ai:service:create',
  debug: 'ai:service:debug',
  delete: 'ai:service:delete',
  evaluate: 'ai:service:evaluate',
  publish: 'ai:service:publish',
  query: 'ai:service:query',
  release: 'ai:service:release',
  update: 'ai:service:update',
} as const;

/** 资源类型（与后端 AiResourceType 一致） */
export const RESOURCE_TYPE_OPTIONS = [
  { label: '报表', value: 'REPORT' },
  { label: '知识库', value: 'KNOWLEDGE_BASE' },
  { label: '文件', value: 'FILE' },
  { label: '工具', value: 'TOOL' },
  { label: '数据集', value: 'DATASET' },
];

/** 资源动作（与后端 AiAction 一致） */
export const RESOURCE_ACTION_OPTIONS = [
  { label: '读取', value: 'READ' },
  { label: '执行', value: 'EXECUTE' },
  { label: '写入', value: 'WRITE' },
];

/** 调试数据分级（与后端 AiOutboundLevel 一致） */
export const DATA_LEVEL_OPTIONS = [
  { label: 'L1 公开', value: 'L1_PUBLIC' },
  { label: 'L2 内部', value: 'L2_INTERNAL' },
  { label: 'L3 个人信息', value: 'L3_PERSONAL' },
  { label: 'L4 敏感', value: 'L4_SECRET' },
];

/** 调试测试主体类型 */
export const TEST_SUBJECT_OPTIONS = [
  { label: '用户主体（外部用户标识）', value: 'USER' },
  { label: '应用主体', value: 'APP' },
];

/** 发布版本状态文案 */
export const RELEASE_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '生效中',
  CANDIDATE: '候选（未对运行开放）',
  RETIRED: '已退役',
};

/** 服务草稿状态文案 */
export const SERVICE_STATUS_LABELS: Record<string, string> = {
  ARCHIVED: '已归档',
  DRAFT: '草稿',
  READY: '可发布',
};

/** JSON Schema 即时校验结果 */
export interface SchemaCheckResult {
  ok: boolean;
  message?: string;
}

/**
 * JSON Schema 即时校验（前端提示；后端仍会拒绝非法 Schema，前端提示不是放行依据）。
 *
 * 规则与后端保持一致：必须是可解析的 JSON，且是 JSON **对象**（数组、标量都不算）。
 */
export function validateJsonObjectSchema(text: string): SchemaCheckResult {
  const trimmed = (text ?? '').trim();
  if (trimmed.length === 0) {
    return { ok: false, message: 'Schema 不能为空' };
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    return { ok: false, message: '不是合法 JSON，请检查括号与引号' };
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return {
      ok: false,
      message: 'Schema 必须是 JSON 对象（例如 {"type":"object"}）',
    };
  }
  return { ok: true };
}

/** 当前生效版本（同一服务最多一条 ACTIVE） */
export function currentRelease(
  releases: AiServiceApi.Release[],
): AiServiceApi.Release | undefined {
  return releases.find((release) => release.status === 'ACTIVE');
}

/**
 * 回退影响说明：回退只切换发布别名，只影响**后续运行**；
 * 已固定版本的会话沿用它们开始的版本，资源授权与停用状态始终按当前值判定。
 */
export function describeRollbackImpact(
  releases: AiServiceApi.Release[],
  targetReleaseId: number,
): string {
  const active = currentRelease(releases);
  const target = releases.find((release) => release.id === targetReleaseId);
  if (!target) {
    return '目标版本不存在，请刷新后重试';
  }
  const from = active ? `v${active.releaseVersion}` : '（当前无生效版本）';
  return [
    `回退后：${from} → v${target.releaseVersion}。`,
    '只影响后续新运行；已固定版本的会话仍按原版本执行。',
    '资源授权与资源停用状态始终按当前值判定，历史版本不会恢复旧权限。',
    '回退与发布使用同一套预检查，未通过时不改变当前生效版本。',
  ].join('');
}

/** 版本行提示：固定值组成与不可变性说明 */
export function describeReleaseContent(release: AiServiceApi.Release): string {
  return [
    `内容摘要 ${release.contentHash.slice(0, 12)}…`,
    `端点配置版本 v${release.endpointConfigRevision}`,
    `评测门槛 ${release.evalThreshold}`,
    '发布内容写入后不可修改',
  ].join(' · ');
}

/** 列表查询表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      componentProps: { clearable: true, placeholder: '请输入服务标识' },
      fieldName: 'code',
      label: '服务标识',
    },
    {
      component: 'InputNumber',
      componentProps: { class: 'w-full', min: 1, placeholder: '应用编号' },
      fieldName: 'appId',
      label: '应用编号',
    },
    {
      component: 'Select',
      componentProps: {
        clearable: true,
        options: Object.entries(SERVICE_STATUS_LABELS).map(
          ([value, label]) => ({
            label,
            value,
          }),
        ),
        placeholder: '请选择状态',
      },
      fieldName: 'status',
      label: '状态',
    },
  ];
}

/** 列表列 */
export function useGridColumns(): VxeTableGridOptions['columns'] {
  return [
    { field: 'name', minWidth: 160, title: '服务名称' },
    { field: 'code', minWidth: 140, title: '服务标识' },
    {
      field: 'status',
      minWidth: 120,
      title: '状态',
      formatter: ({ cellValue }) =>
        SERVICE_STATUS_LABELS[cellValue] ?? cellValue,
    },
    { field: 'modelEndpointId', title: '模型端点', width: 110 },
    {
      field: 'requiredCapabilities',
      minWidth: 160,
      title: '所需能力',
      formatter: ({ cellValue }) =>
        Array.isArray(cellValue) ? cellValue.join('、') : '',
    },
    { field: 'evalThreshold', title: '评测门槛', width: 110 },
    { field: 'draftRevision', title: '草稿修订', width: 110 },
  ];
}

/** 草稿编辑器表单（模型/提示词/资源/Schema/执行限制） */
export function useDraftSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      dependencies: { show: () => false, triggerFields: [''] },
      fieldName: 'id',
    },
    {
      component: 'InputNumber',
      componentProps: { class: 'w-full', min: 1, placeholder: '所属应用编号' },
      fieldName: 'appId',
      label: '所属应用',
      rules: 'required',
    },
    {
      component: 'Input',
      componentProps: {
        maxlength: 40,
        placeholder: 'svc_order_qa（创建后不可修改）',
      },
      fieldName: 'code',
      label: '服务标识',
      rules: z
        .string()
        .min(1, '请输入服务标识')
        .max(40, '服务标识不能超过40个字符'),
    },
    {
      component: 'Input',
      componentProps: { maxlength: 128, placeholder: '请输入服务名称' },
      fieldName: 'name',
      label: '服务名称',
      rules: z
        .string()
        .min(1, '请输入服务名称')
        .max(128, '服务名称不能超过128个字符'),
    },
    {
      component: 'InputNumber',
      componentProps: { class: 'w-full', min: 1, placeholder: '模型端点编号' },
      fieldName: 'modelEndpointId',
      label: '模型端点',
      rules: 'required',
    },
    {
      component: 'Textarea',
      componentProps: {
        maxlength: 8000,
        placeholder: '系统指令；业务说明不能授予权限，也不能覆盖平台政策',
        rows: 6,
      },
      fieldName: 'promptTemplate',
      label: '提示词模板',
      rules: z
        .string()
        .min(1, '请输入提示词模板')
        .max(8000, '提示词不能超过8000个字符'),
    },
    {
      component: 'Textarea',
      componentProps: {
        maxlength: 4000,
        placeholder: '{"type":"object"}',
        rows: 3,
      },
      fieldName: 'inputSchema',
      label: '输入 Schema',
      rules: z.string().min(1, '请输入输入 Schema'),
    },
    {
      component: 'Textarea',
      componentProps: {
        maxlength: 4000,
        placeholder: '{"type":"object"}（结构化输出时必填）',
        rows: 3,
      },
      fieldName: 'outputSchema',
      label: '输出 Schema',
    },
    {
      component: 'Select',
      componentProps: {
        multiple: true,
        options: [
          { label: '文本生成', value: 'TEXT' },
          { label: '结构化输出', value: 'STRUCTURED_OUTPUT' },
          { label: '嵌入', value: 'EMBEDDING' },
          { label: '工具调用', value: 'TOOL_CALLING' },
        ],
        placeholder: '端点探测确认的能力才是可发布范围',
      },
      fieldName: 'requiredCapabilities',
      label: '所需能力',
      rules: 'required',
    },
    {
      component: 'Select',
      componentProps: {
        options: [
          { label: '用户主体', value: 'USER' },
          { label: '应用主体', value: 'APP' },
        ],
      },
      fieldName: 'runSubjectType',
      label: '运行主体',
      rules: 'required',
    },
    {
      component: 'InputNumber',
      componentProps: { class: 'w-full', max: 100, min: 0 },
      fieldName: 'evalThreshold',
      label: '评测门槛',
      rules: 'required',
    },
    {
      component: 'Input',
      dependencies: { show: () => false, triggerFields: [''] },
      fieldName: 'version',
    },
  ];
}

/** 调试表单（必须显式给出测试主体与数据分级） */
export function useDebugSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Select',
      componentProps: { options: TEST_SUBJECT_OPTIONS },
      fieldName: 'testSubjectType',
      label: '测试主体',
      rules: 'required',
    },
    {
      component: 'Input',
      componentProps: {
        maxlength: 64,
        placeholder: '外部用户标识（用户主体必填，不接受隐式当前用户）',
      },
      fieldName: 'testSubjectId',
      label: '测试主体标识',
    },
    {
      component: 'Select',
      componentProps: { options: DATA_LEVEL_OPTIONS },
      fieldName: 'dataLevel',
      label: '数据分级',
      rules: 'required',
    },
    {
      component: 'Textarea',
      componentProps: {
        maxlength: 16_000,
        placeholder: '请输入调试消息',
        rows: 3,
      },
      fieldName: 'userMessage',
      label: '调试消息',
      rules: z
        .string()
        .min(1, '请输入调试消息')
        .max(16_000, '消息不能超过16000个字符'),
    },
    {
      component: 'Textarea',
      componentProps: {
        maxlength: 4000,
        placeholder: '{"page":"order","objectId":"A-1"}（只接受已注册字段）',
        rows: 2,
      },
      fieldName: 'businessContext',
      label: '业务上下文',
    },
    {
      component: 'InputNumber',
      componentProps: { class: 'w-full', max: 120_000, min: 1 },
      fieldName: 'timeoutMillis',
      label: '超时（毫秒）',
    },
    {
      component: 'InputNumber',
      componentProps: { class: 'w-full', min: 1 },
      fieldName: 'maxTokens',
      label: '输入 token 预算',
    },
  ];
}

/** 评测表单 */
export function useEvaluationSchema(): VbenFormSchema[] {
  return [
    {
      component: 'InputNumber',
      componentProps: { class: 'w-full', max: 100, min: 0 },
      fieldName: 'score',
      label: '评测得分（0-100）',
      rules: 'required',
    },
    {
      component: 'InputNumber',
      componentProps: { class: 'w-full', min: 1 },
      fieldName: 'caseCount',
      label: '用例数',
      rules: 'required',
    },
    {
      component: 'Input',
      componentProps: {
        maxlength: 512,
        placeholder: '备注（不得写入提示词或响应正文）',
      },
      fieldName: 'notes',
      label: '备注',
    },
  ];
}
