import type { AiObservabilityApi } from '#/api/ai/observability';

/** 运行监控权限码（与 V82 的 system_menu 种子一一对应：查看与重试分开） */
export const AI_OBSERVABILITY_PERMISSIONS = {
  query: 'ai:observability:query',
  retry: 'ai:observability:retry',
} as const;

/** 运行状态（后端闭集，未知状态原样展示便于发现新增） */
export const RUN_STATUS_TEXT: Record<string, string> = {
  ACCEPTED: '已受理',
  CANCELLED: '已取消',
  FAILED: '失败',
  RUNNING: '执行中',
  SUCCEEDED: '成功',
};

/** 任务状态（UNKNOWN 是"结果未知"，必须人工核对，不是"失败可重试"） */
export const TASK_STATUS_TEXT: Record<string, string> = {
  FAILED: '失败（可人工重试）',
  QUEUED: '待处理',
  RUNNING: '执行中',
  SUCCEEDED: '成功',
  UNKNOWN: '结果未知（需人工核对后新建运行）',
};

/** 失败原因码说明（只解释稳定原因码，不暴露上游报文） */
export const FAILURE_REASON_TEXT: Record<string, string> = {
  DURATION_BUDGET_EXCEEDED: '超出运行耗时预算',
  STEP_BUDGET_EXCEEDED: '超出执行步数预算',
  TOOL_BUDGET_EXCEEDED: '超出工具调用预算',
  TOOL_UNSUPPORTED: '服务未绑定该工具',
};

/** 阶段名说明（未计量阶段用同一词表，避免界面自造术语） */
export const STAGE_TEXT: Record<string, string> = {
  BUSINESS_API: '业务 API',
  MODEL: '模型',
  RETRIEVAL: '检索',
  TOTAL: '总耗时',
};

export const RUN_STATUS_OPTIONS = Object.entries(RUN_STATUS_TEXT).map(
  ([value, label]) => ({ label, value }),
);

export const SUBJECT_TYPE_OPTIONS = [
  { label: '应用（APP）', value: 'APP' },
  { label: '用户（USER）', value: 'USER' },
];

export function describeRunStatus(status?: string): string {
  if (!status) {
    return '-';
  }
  return RUN_STATUS_TEXT[status] ?? status;
}

export function describeTaskStatus(status?: string): string {
  if (!status) {
    return '-';
  }
  return TASK_STATUS_TEXT[status] ?? status;
}

export function describeFailureReason(reason?: string): string {
  if (!reason) {
    return '-';
  }
  return FAILURE_REASON_TEXT[reason] ?? reason;
}

/** 毫秒展示：未知（未记录）不显示 0 */
export function formatDurationMs(value?: null | number): string {
  if (value === undefined || value === null) {
    return '未记录';
  }
  return `${value} ms`;
}

/** 是否显示重试入口：服务端说可重试才显示（同一判据，避免"能点但被拒"） */
export function canRetry(row?: { retryable?: boolean }): boolean {
  return row?.retryable === true;
}

/** 不可重试的原因文案（后端给的稳定说明） */
export function retryBlockedText(row?: {
  retryBlockedReason?: string;
}): string {
  return row?.retryBlockedReason ?? '当前状态不可重试';
}

/** 耗时分解行（未计量的阶段显示"未单独计量"，不填 0） */
export function timingRows(
  timing?: AiObservabilityApi.RunTiming,
): Array<{ label: string; note: string; stage: string; value: string }> {
  const unmeasured = new Set(timing?.unmeasuredStages);
  const modelNote =
    timing === undefined
      ? '未记录'
      : `${timing.modelInvocationCount ?? 0} 次调用，来源未知 ${
          timing.unknownUsageCount ?? 0
        } 次`;
  return [
    {
      label: STAGE_TEXT.MODEL ?? 'MODEL',
      note: modelNote,
      stage: 'MODEL',
      value: formatDurationMs(timing?.modelDurationMs),
    },
    {
      label: STAGE_TEXT.RETRIEVAL ?? 'RETRIEVAL',
      note: unmeasured.has('RETRIEVAL') ? '运行链路尚未单独计量' : '',
      stage: 'RETRIEVAL',
      value: unmeasured.has('RETRIEVAL')
        ? '未单独计量'
        : formatDurationMs(timing?.retrievalDurationMs),
    },
    {
      label: STAGE_TEXT.BUSINESS_API ?? 'BUSINESS_API',
      note: unmeasured.has('BUSINESS_API') ? '运行链路尚未单独计量' : '',
      stage: 'BUSINESS_API',
      value: unmeasured.has('BUSINESS_API')
        ? '未单独计量'
        : formatDurationMs(timing?.businessApiDurationMs),
    },
    {
      label: STAGE_TEXT.TOTAL ?? 'TOTAL',
      note: '受理到终态',
      stage: 'TOTAL',
      value: formatDurationMs(timing?.totalDurationMs),
    },
  ];
}

/** 查询参数：空值不下发，时间窗按后端要求的本地 ISO（由时间选择器直接给出） */
export function buildRunQuery(input: {
  applicationId?: number;
  pageNo: number;
  pageSize: number;
  range?: [string, string] | string[] | undefined;
  serviceId?: number;
  status?: string;
  subjectType?: string;
}): AiObservabilityApi.RunPageParams {
  const params: AiObservabilityApi.RunPageParams = {
    pageNo: input.pageNo,
    pageSize: input.pageSize,
  };
  if (input.applicationId !== undefined) {
    params.applicationId = input.applicationId;
  }
  if (input.serviceId !== undefined) {
    params.serviceId = input.serviceId;
  }
  if (input.status) {
    params.status = input.status;
  }
  if (input.subjectType) {
    params.subjectType = input.subjectType;
  }
  const [from, to] = input.range ?? [];
  if (from) {
    params.from = from;
  }
  if (to) {
    params.to = to;
  }
  return params;
}
