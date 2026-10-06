import type { VbenFormSchema } from '#/adapter/form';
import type { AiQueryApi } from '#/api/ai/query';

import { z } from '@vben/common-ui';

/**
 * 查询规划权限码（与 V69 迁移的 system_menu 种子一一对应：4070/4071）。
 * 两个端点各自独立授权，页面据此决定是否渲染提交入口（后端仍会二次校验）。
 */
export const AI_QUERY_PERMISSIONS = {
  plan: 'ai:query:plan',
  summary: 'ai:query:summary',
} as const;

/** 修复次数上限：与后端 AiQueryPlanRequestDTO.MAX_REPAIRS 保持一致 */
export const MAX_REPAIRS_LIMIT = 2;

/** 两种正常结果的文案：CLARIFICATION 不是失败，是"需要你先选择口径" */
export const PLAN_KIND_LABELS: Record<string, string> = {
  CLARIFICATION: '需要澄清',
  PLAN: '已校验计划',
};

/** 澄清原因文案（取自后端 QueryPlanOutcome.Clarification 的三个稳定原因码） */
export const CLARIFICATION_REASON_LABELS: Record<string, string> = {
  AMBIGUOUS: '口径或字段有歧义，请在候选中选择',
  OUT_OF_SCOPE: '超出当前数据集或服务范围',
  UNSUPPORTED: '问题不受支持（例如要求 SQL 或全库扫描）',
};

/** 契约提示：让用户知道"不写 SQL"是后端强制的，而不是页面功能缺失 */
export const NO_SQL_NOTICE =
  '本接口只接受自然语言问题与授权字段：后端不接受 SQL，模型输出 SQL 片段会被直接拒绝。';

/** 计划表单取值（allowedFieldCodes 在表单里是逗号分隔文本，提交前才转数组） */
export interface PlanFormValues {
  allowedFieldCodes?: string;
  datasetId?: number;
  datasetVersionId?: number;
  endpointId?: number;
  maxRepairs?: null | number;
  question?: string;
}

/** 摘要表单取值：与计划表单是同一批数据集字段的子集 */
export interface SummaryFormValues {
  allowedFieldCodes?: string;
  datasetId?: number;
  datasetVersionId?: number;
}

/** 计划表单：问题必填，编号必须为正整数（对齐后端 @Positive） */
export function usePlanFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'InputNumber',
      componentProps: { min: 1, placeholder: '数据集编号' },
      fieldName: 'datasetId',
      label: '数据集编号',
      rules: z.coerce.number().int().positive('请输入数据集编号'),
    },
    {
      component: 'InputNumber',
      componentProps: { min: 1, placeholder: '留空取最新已发布版本' },
      fieldName: 'datasetVersionId',
      label: '数据集版本编号',
    },
    {
      component: 'InputNumber',
      componentProps: { min: 1, placeholder: '模型端点编号' },
      fieldName: 'endpointId',
      label: '模型端点编号',
      rules: z.coerce.number().int().positive('请输入模型端点编号'),
    },
    {
      component: 'Input',
      componentProps: {
        placeholder: '例如：最近 30 天各渠道的成交金额',
        rows: 3,
        type: 'textarea',
      },
      fieldName: 'question',
      label: '问题',
      rules: z
        .string()
        .trim()
        .min(1, '请输入问题')
        .max(2000, '问题最多 2000 个字符（与后端 @Size 一致）'),
    },
    {
      component: 'Input',
      componentProps: {
        placeholder: '逗号分隔，如 amount, channel；留空表示数据集全部字段',
      },
      fieldName: 'allowedFieldCodes',
      label: '允许的字段/指标码',
    },
    {
      component: 'InputNumber',
      componentProps: { max: MAX_REPAIRS_LIMIT, min: 0 },
      fieldName: 'maxRepairs',
      label: '允许的修复次数',
      rules: z.coerce
        .number()
        .int('修复次数必须是整数')
        .min(0, '修复次数不能为负')
        .max(MAX_REPAIRS_LIMIT, `修复次数上限为 ${MAX_REPAIRS_LIMIT}`)
        .optional(),
    },
  ];
}

/**
 * 摘要表单：只列后端 summary 端点真正接受的三个参数。
 * 刻意不共用计划表单——把 question/endpointId 混进摘要请求会让页面看起来
 * 好像摘要也依赖问题与端点，从而掩盖两个端点是两套独立契约的事实。
 */
export function useSummaryFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'InputNumber',
      componentProps: { min: 1, placeholder: '数据集编号' },
      fieldName: 'datasetId',
      label: '数据集编号',
      rules: z.coerce.number().int().positive('请输入数据集编号'),
    },
    {
      component: 'InputNumber',
      componentProps: { min: 1, placeholder: '留空取最新已发布版本' },
      fieldName: 'datasetVersionId',
      label: '数据集版本编号',
    },
    {
      component: 'Input',
      componentProps: {
        placeholder: '逗号分隔，如 amount, channel；留空表示数据集全部字段',
      },
      fieldName: 'allowedFieldCodes',
      label: '允许的字段/指标码',
    },
  ];
}

/**
 * 把逗号分隔的字段码文本转成请求数组。
 * 空输入返回 undefined 而不是空数组：后端对"未给集合"和"空集合"的处理不同，
 * 用 undefined 才能真正落到"数据集定义全部字段"这个缺省语义上。
 */
export function parseFieldCodes(raw?: string): string[] | undefined {
  const codes = (raw ?? '')
    .split(/[,，;；\s]+/u)
    .map((code) => code.trim())
    .filter((code) => code.length > 0);
  const unique = [...new Set(codes)];
  return unique.length > 0 ? unique : undefined;
}

/** 留空的修复次数不下发：让后端用自己的缺省值，而不是被前端静默改成 0 */
function optionalRepairs(value?: null | number): number | undefined {
  return value === null || value === undefined ? undefined : value;
}

/** 计划请求：只带后端 AiQueryPlanReqVO 声明的字段，可选项为空即省略 */
export function buildPlanRequest(values: PlanFormValues): AiQueryApi.PlanReq {
  return {
    ...(parseFieldCodes(values.allowedFieldCodes) === undefined
      ? {}
      : { allowedFieldCodes: parseFieldCodes(values.allowedFieldCodes) }),
    datasetId: Number(values.datasetId),
    ...(values.datasetVersionId === undefined
      ? {}
      : { datasetVersionId: Number(values.datasetVersionId) }),
    endpointId: Number(values.endpointId),
    ...(optionalRepairs(values.maxRepairs) === undefined
      ? {}
      : { maxRepairs: optionalRepairs(values.maxRepairs) }),
    question: String(values.question ?? '').trim(),
  };
}

/** 摘要请求：绝不携带 question/endpointId/maxRepairs（summary 端点不接收它们） */
export function buildSummaryParams(
  values: SummaryFormValues,
): AiQueryApi.SummaryParams {
  return {
    ...(parseFieldCodes(values.allowedFieldCodes) === undefined
      ? {}
      : { allowedFieldCodes: parseFieldCodes(values.allowedFieldCodes) }),
    datasetId: Number(values.datasetId),
    ...(values.datasetVersionId === undefined
      ? {}
      : { datasetVersionId: Number(values.datasetVersionId) }),
  };
}

/**
 * 结果 JSON 展示：能解析就缩进展示，解析不了就原样回显。
 * 后端 planJson 是规范化 JSON 文本，页面不二次加工内容，只做展示格式化。
 */
export function formatJsonForRead(raw?: string): string {
  if (!raw) {
    return '';
  }
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

/** 澄清原因文案；未知原因码原样回显，避免把新原因码静默显示成"未知" */
export function describeClarificationReason(reason?: string): string {
  if (!reason) {
    return '未返回原因码';
  }
  return CLARIFICATION_REASON_LABELS[reason] ?? `未识别原因码：${reason}`;
}

/**
 * 计划结果的一句话结论：把版本锚点、哈希与模型次数摊平成可核对的一行。
 * 澄清结果只报原因与候选数——候选本身由页面单独列出，避免这行变得不可读。
 */
export function describePlanResult(result: AiQueryApi.PlanResult): string {
  const anchors = [
    result.datasetCode ? `数据集 ${result.datasetCode}` : '',
    result.planDatasetId ? `计划标识 ${result.planDatasetId}` : '',
    result.datasetVersionNo === undefined
      ? ''
      : `语义版本 v${result.datasetVersionNo}`,
  ].filter((item) => item.length > 0);
  // 澄清路径常常没有版本锚点：没有锚点就不要留一个空分隔符在前头
  const head = anchors.length > 0 ? `${anchors.join(' · ')}｜` : '';
  if (result.kind === 'CLARIFICATION') {
    const count = result.candidates?.length ?? 0;
    return `${head}${describeClarificationReason(result.reason)}｜候选 ${count} 项`;
  }
  return `${head}计划哈希 ${result.planHash ?? '（未返回）'}｜定义哈希 ${
    result.schemaHash ?? '（未返回）'
  }｜模型输出 ${result.attempts ?? 0} 次`;
}
