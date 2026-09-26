import type { AiEvalApi } from '#/api/ai/evaluation';

import {
  describeFailureCode,
  describeResultStatus,
  describeRuleKind,
  describeSeverity,
} from './data';

/**
 * 评测控制面分析口径（Q05）：verdict 解析与聚合、失败分类、运行比较、报告解析与查询参数构造。
 *
 * 所有计算都在前端从**服务端事实**推导，不臆造字段：
 *  - **计数**：`passedCount` 只计 PASSED；FAILED 与待复核（REVIEW_REQUIRED）计入 `failedCount`；
 *    ERROR 计入 `errorCount`；三项之和应等于 `caseTotal`。通过率只能用"通过 ÷ 总数"，
 *    不能只用通过样例做分母（AT-035）。
 *  - **判定**：`verdictJson` 解析失败时如实标注"无法解析"，不做静默丢弃或补 0。
 *  - **未知值**：后端闭集之外的取值原样展示（便于发现新增），不翻译成"未知成功"。
 *
 * verdictJson 是逐条判定数组：解析失败/结构不符都在页面上如实标注，不静默丢弃。
 */

/** 逐条判定的展示行（页面只读展示，不参与判定） */
export interface VerdictRow {
  expected: string;
  index: number;
  kind: string;
  kindLabel: string;
  message: string;
  observed: string;
  passed: boolean;
  path: string;
}

/** verdictJson 解析结果：解析失败时给出原因，不丢内容 */
export interface VerdictParse {
  error?: string;
  verdicts: AiEvalApi.Verdict[];
}

export interface SeverityAggregate {
  error: number;
  failed: number;
  failedRules: number;
  label: string;
  passed: number;
  pendingReview: number;
  severity: string;
  total: number;
}

export interface KindAggregate {
  checked: number;
  failed: number;
  kind: string;
  label: string;
}

export interface FailureCodeAggregate {
  code: string;
  count: number;
  label: string;
}

export interface StatusAggregate {
  count: number;
  label: string;
  status: string;
}

/** 失败分类：全部结果参与统计（不是只看失败或只看通过） */
export interface FailureClassification {
  byFailureCode: FailureCodeAggregate[];
  byKind: KindAggregate[];
  bySeverity: SeverityAggregate[];
  byStatus: StatusAggregate[];
  pendingReview: number;
  total: number;
  unparsedVerdicts: number;
}

/** 用例级运行差异（按 caseKey 对齐） */
export interface RunDiffRow {
  baseCaseDigest?: string;
  baseFailureCode?: null | string;
  baseStatus?: string;
  caseDigestChanged: boolean | null;
  caseKey: string;
  failureCodeChanged: boolean | null;
  note: string;
  onlyIn: 'BASE' | 'BOTH' | 'TARGET';
  severity: string;
  statusChanged: boolean | null;
  targetCaseDigest?: string;
  targetFailureCode?: null | string;
  targetStatus?: string;
  verdictChanged: boolean | null;
}

/** 报告展示：解析失败时原样展示，绝不吞掉内容 */
export interface ReportDisplay {
  parseFailed: boolean;
  text: string;
}

/** 级别排序权重：阻断级排最前 */
const SEVERITY_ORDER = ['BLOCKER', 'MAJOR', 'MINOR'];

/** 判定排序权重：先看待复核与失败 */
const RESULT_STATUS_ORDER = ['REVIEW_REQUIRED', 'FAILED', 'ERROR', 'PASSED'];

/** 是否为普通对象（数组与 null 不算） */
function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** 判定条目是否满足契约（index/kind/passed 是判定必需的三个字段） */
function isVerdict(value: unknown): value is AiEvalApi.Verdict {
  if (!isRecord(value)) {
    return false;
  }
  return (
    typeof value.index === 'number' &&
    typeof value.kind === 'string' &&
    typeof value.passed === 'boolean'
  );
}

/** 排序权重：命中给定顺序的排前面，其余按名称稳定排在后面 */
function rankOf(order: string[], value: string): number {
  const index = order.indexOf(value);
  return index === -1 ? order.length : index;
}

/**
 * 期望规则校验（提交前）：必须是 JSON **数组**，非空、元素为对象、最多 32 条。
 * kind 的合法取值由服务端判定，前端不拦（避免拦住后端新增的规则类型）。
 */
export function parseChecks(
  text: string,
): { message: string; ok: false } | { ok: true; value: string } {
  const trimmed = (text ?? '').trim();
  if (trimmed === '') {
    return { message: '期望规则不能为空：至少写 1 条 JSON 规则', ok: false };
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    return {
      message: '不是合法 JSON：请检查括号、引号与逗号（期望规则是数组）',
      ok: false,
    };
  }
  if (!Array.isArray(parsed)) {
    return {
      message:
        '期望规则必须是 JSON 数组，例如 [{"kind":"VALUE","path":"answer","expect":"42"}]',
      ok: false,
    };
  }
  if (parsed.length === 0) {
    return { message: '期望规则不能为空数组：至少 1 条规则', ok: false };
  }
  if (parsed.length > 32) {
    return {
      message: `期望规则最多 32 条，当前 ${parsed.length} 条`,
      ok: false,
    };
  }
  const invalidIndex = parsed.findIndex((item) => !isRecord(item));
  if (invalidIndex !== -1) {
    return {
      message: `第 ${invalidIndex + 1} 条规则必须是 JSON 对象（不能是数组或标量）`,
      ok: false,
    };
  }
  return { ok: true, value: JSON.stringify(parsed) };
}

/** verdictJson 解析：非法或结构不符时给出原因，并把原文留在页面上 */
export function parseVerdicts(raw?: null | string): VerdictParse {
  if (raw === undefined || raw === null || raw.trim() === '') {
    return { verdicts: [] };
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return {
      error: '判定 JSON 无法解析（页面按原文展示，计数不臆造）',
      verdicts: [],
    };
  }
  if (!Array.isArray(parsed)) {
    return {
      error: '判定 JSON 不是数组（契约要求逐条判定数组）',
      verdicts: [],
    };
  }
  const verdicts = parsed.filter((item) => isVerdict(item));
  if (verdicts.length !== parsed.length) {
    const unknownCount = parsed.length - verdicts.length;
    return {
      error: `判定 JSON 有 ${unknownCount} 条不符合契约（缺少 index/kind/passed），未计入统计`,
      verdicts: [],
    };
  }
  return { verdicts };
}

/** 逐条判定行（按 index 升序，与规则顺序一致） */
export function verdictRows(raw?: null | string): VerdictRow[] {
  return parseVerdicts(raw)
    .verdicts.toSorted((left, right) => left.index - right.index)
    .map((verdict) => ({
      expected: verdict.expected ?? '（未给期望值）',
      index: verdict.index,
      kind: verdict.kind,
      kindLabel: describeRuleKind(verdict.kind),
      message: verdict.message ?? '（无说明）',
      observed: verdict.observed ?? '（未给实际值）',
      passed: verdict.passed,
      path: verdict.path ?? '（缺省路径）',
    }));
}

/** 判定摘要：未产生判定、无法解析都如实说明，不写成"通过" */
export function verdictSummary(
  result: Pick<AiEvalApi.Result, 'status' | 'verdictJson'>,
): string {
  const parsed = parseVerdicts(result.verdictJson);
  if (parsed.error !== undefined) {
    return `判定不可用：${parsed.error}`;
  }
  if (parsed.verdicts.length === 0) {
    return `未产生判定（结果：${describeResultStatus(result.status)}）`;
  }
  const failed = parsed.verdicts.filter((verdict) => !verdict.passed);
  if (failed.length === 0) {
    return `全部 ${parsed.verdicts.length} 条规则通过`;
  }
  const kinds = [...new Set(failed.map((verdict) => verdict.kind))]
    .toSorted((left, right) => left.localeCompare(right))
    .map((kind) => describeRuleKind(kind));
  return `${failed.length}/${parsed.verdicts.length} 条规则未通过（${kinds.join('、')}）`;
}

/** 计数口径说明：把"通过只计 PASSED、待复核计入失败"写在页面上 */
export function countingSummary(run?: AiEvalApi.Run): string {
  if (!run) {
    return '尚未选择运行，无法解释计数口径';
  }
  return `共 ${run.caseTotal} 例：通过 ${run.passedCount}（只计 PASSED）、失败 ${run.failedCount}（FAILED 与待复核 REVIEW_REQUIRED）、错误 ${run.errorCount}（未能执行 ERROR）。通过率必须按 通过÷总数 计算，不能只用通过样例做分母。`;
}

/** 计数核对：三项之和与总数的关系必须显式展示 */
export function countingCheckText(run?: AiEvalApi.Run): string {
  if (!run) {
    return '尚未选择运行，无法核对计数';
  }
  const sum = run.passedCount + run.failedCount + run.errorCount;
  if (sum === run.caseTotal) {
    return `计数核对：${run.passedCount} + ${run.failedCount} + ${run.errorCount} = ${run.caseTotal}，与样例总数一致`;
  }
  return `计数核对：${run.passedCount} + ${run.failedCount} + ${run.errorCount} = ${sum}，与样例总数 ${run.caseTotal} 不一致，请以逐例结果为准`;
}

/** 待复核条数说明 */
export function pendingReviewText(count: number): string {
  if (count === 0) {
    return '没有等待人工复核的结果';
  }
  return `${count} 例等待人工复核：复核通过前不计入通过`;
}

/** 运行冻结的逐例快照条数（解析不了返回 undefined，界面显示"未知"） */
export function frozenCaseCount(
  summaryJson?: null | string,
): number | undefined {
  if (!summaryJson) {
    return undefined;
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(summaryJson);
  } catch {
    return undefined;
  }
  if (!isRecord(parsed) || !Array.isArray(parsed.cases)) {
    return undefined;
  }
  return parsed.cases.length;
}

/** 样例按 caseKey 升序（服务端已排序，这里保证界面顺序稳定） */
export function sortByCaseKey<T extends { caseKey: string }>(items: T[]): T[] {
  return items.toSorted((left, right) =>
    left.caseKey.localeCompare(right.caseKey),
  );
}

/** 套件查询参数：空值不下发 */
export function buildSuiteQuery(input: {
  applicationId?: number;
  pageNo: number;
  pageSize: number;
  status?: string;
}): AiEvalApi.SuitePageParams {
  const params: AiEvalApi.SuitePageParams = {
    pageNo: input.pageNo,
    pageSize: input.pageSize,
  };
  if (input.applicationId !== undefined) {
    params.applicationId = input.applicationId;
  }
  if (input.status) {
    params.status = input.status;
  }
  return params;
}

/** 结果查询参数：空值不下发 */
export function buildResultQuery(input: {
  pageNo: number;
  pageSize: number;
  runId: number;
  status?: string;
}): AiEvalApi.ResultPageParams {
  const params: AiEvalApi.ResultPageParams = {
    pageNo: input.pageNo,
    pageSize: input.pageSize,
    runId: input.runId,
  };
  if (input.status) {
    params.status = input.status;
  }
  return params;
}

/**
 * 失败分类：**全部结果**参与统计（不挑样例）。
 * - bySeverity：每个级别的用例数、通过、失败（含待复核）、待复核、未执行、未通过规则数；
 * - byKind：每条规则类型的核验条数与未通过条数（来自 verdictJson）；
 * - byFailureCode：未能执行的稳定错误码条数；
 * - unparsedVerdicts：判定 JSON 无法解析的结果数（这些结果不会被算成通过）。
 */
export function classifyFailures(
  results: AiEvalApi.Result[],
): FailureClassification {
  const severityMap = new Map<string, SeverityAggregate>();
  const kindMap = new Map<string, KindAggregate>();
  const codeMap = new Map<string, FailureCodeAggregate>();
  const statusMap = new Map<string, StatusAggregate>();
  let pendingReview = 0;
  let unparsedVerdicts = 0;

  for (const result of results) {
    const severity = result.severity ?? 'UNKNOWN';
    const severityRow = severityMap.get(severity) ?? {
      error: 0,
      failed: 0,
      failedRules: 0,
      label: describeSeverity(severity),
      passed: 0,
      pendingReview: 0,
      severity,
      total: 0,
    };
    severityRow.total += 1;
    if (result.status === 'PASSED') {
      severityRow.passed += 1;
    }
    if (result.status === 'FAILED' || result.status === 'REVIEW_REQUIRED') {
      severityRow.failed += 1;
    }
    if (result.status === 'ERROR') {
      severityRow.error += 1;
    }
    if (result.reviewStatus === 'PENDING') {
      severityRow.pendingReview += 1;
      pendingReview += 1;
    }
    severityMap.set(severity, severityRow);

    const status = result.status ?? 'UNKNOWN';
    const statusRow = statusMap.get(status) ?? {
      count: 0,
      label: describeResultStatus(status),
      status,
    };
    statusRow.count += 1;
    statusMap.set(status, statusRow);

    const parsed = parseVerdicts(result.verdictJson);
    if (parsed.error !== undefined) {
      unparsedVerdicts += 1;
    }
    for (const verdict of parsed.verdicts) {
      const kindRow = kindMap.get(verdict.kind) ?? {
        checked: 0,
        failed: 0,
        kind: verdict.kind,
        label: describeRuleKind(verdict.kind),
      };
      kindRow.checked += 1;
      if (!verdict.passed) {
        kindRow.failed += 1;
        severityRow.failedRules += 1;
      }
      kindMap.set(verdict.kind, kindRow);
    }

    const code = result.failureCode ?? undefined;
    if (code) {
      const codeRow = codeMap.get(code) ?? {
        code,
        count: 0,
        label: describeFailureCode(code),
      };
      codeRow.count += 1;
      codeMap.set(code, codeRow);
    }
  }

  return {
    byFailureCode: [...codeMap.values()].toSorted((left, right) => {
      if (right.count !== left.count) {
        return right.count - left.count;
      }
      return left.code.localeCompare(right.code);
    }),
    byKind: [...kindMap.values()].toSorted((left, right) => {
      if (right.failed !== left.failed) {
        return right.failed - left.failed;
      }
      if (right.checked !== left.checked) {
        return right.checked - left.checked;
      }
      return left.kind.localeCompare(right.kind);
    }),
    bySeverity: [...severityMap.values()].toSorted((left, right) => {
      const rank =
        rankOf(SEVERITY_ORDER, left.severity) -
        rankOf(SEVERITY_ORDER, right.severity);
      return rank === 0 ? left.severity.localeCompare(right.severity) : rank;
    }),
    byStatus: [...statusMap.values()].toSorted((left, right) => {
      const rank =
        rankOf(RESULT_STATUS_ORDER, left.status) -
        rankOf(RESULT_STATUS_ORDER, right.status);
      return rank === 0 ? left.status.localeCompare(right.status) : rank;
    }),
    pendingReview,
    total: results.length,
    unparsedVerdicts,
  };
}

/** 判定签名：只在两侧都能解析时才有可比性（同 index + kind + path + passed 视为一致） */
function verdictSignature(raw?: null | string): string | undefined {
  const parsed = parseVerdicts(raw);
  if (parsed.error !== undefined) {
    return undefined;
  }
  return parsed.verdicts
    .toSorted((left, right) => left.index - right.index)
    .map(
      (verdict) =>
        `${verdict.index}:${verdict.kind}:${verdict.path ?? ''}:${
          verdict.passed ? 'P' : 'F'
        }`,
    )
    .join('|');
}

/** 判定变化的具体条目（kind#index），条数不同时直接说明 */
function describeVerdictChange(
  base: AiEvalApi.Result,
  target: AiEvalApi.Result,
): string {
  const left = parseVerdicts(base.verdictJson).verdicts;
  const right = parseVerdicts(target.verdictJson).verdicts;
  if (left.length !== right.length) {
    return `判定条数不同（${left.length} → ${right.length}）`;
  }
  const changed = left.filter((verdict, index) => {
    const other = right[index];
    return (
      other === undefined ||
      other.kind !== verdict.kind ||
      other.passed !== verdict.passed
    );
  });
  if (changed.length === 0) {
    return `判定一致（${left.length} 条规则）`;
  }
  return changed
    .map((verdict) => `${verdict.kind}#${verdict.index}`)
    .join('、');
}

/**
 * 用例级运行差异（按 caseKey 对齐）：
 * 状态变化、caseDigest 是否变化、失败码变化、判定变化；只有一侧存在的样例标 onlyIn。
 */
export function compareRuns(
  baseResults: AiEvalApi.Result[],
  targetResults: AiEvalApi.Result[],
): RunDiffRow[] {
  const baseMap = new Map(
    baseResults.map((result) => [result.caseKey, result]),
  );
  const targetMap = new Map(
    targetResults.map((result) => [result.caseKey, result]),
  );
  const keys = [...new Set([...baseMap.keys(), ...targetMap.keys()])].toSorted(
    (left, right) => left.localeCompare(right),
  );
  return keys.map((caseKey) => {
    const base = baseMap.get(caseKey);
    const target = targetMap.get(caseKey);
    if (!base || !target) {
      const onlyIn: 'BASE' | 'TARGET' = base ? 'BASE' : 'TARGET';
      return {
        baseCaseDigest: base?.caseDigest,
        baseFailureCode: base?.failureCode,
        baseStatus: base?.status,
        caseDigestChanged: null,
        caseKey,
        failureCodeChanged: null,
        note:
          onlyIn === 'BASE'
            ? '仅基准运行有此样例（两次冻结内容不同）'
            : '仅对比运行有此样例（两次冻结内容不同）',
        onlyIn,
        severity: base?.severity ?? target?.severity ?? 'UNKNOWN',
        statusChanged: null,
        targetCaseDigest: target?.caseDigest,
        targetFailureCode: target?.failureCode,
        targetStatus: target?.status,
        verdictChanged: null,
      };
    }
    const statusChanged = base.status !== target.status;
    const caseDigestChanged = base.caseDigest !== target.caseDigest;
    const failureCodeChanged =
      (base.failureCode ?? null) !== (target.failureCode ?? null);
    const baseSignature = verdictSignature(base.verdictJson);
    const targetSignature = verdictSignature(target.verdictJson);
    const comparable =
      baseSignature !== undefined && targetSignature !== undefined;
    const verdictChanged = comparable
      ? baseSignature !== targetSignature
      : null;
    const notes: string[] = [];
    if (statusChanged) {
      notes.push(
        `状态 ${describeResultStatus(base.status)} → ${describeResultStatus(
          target.status,
        )}`,
      );
    }
    if (caseDigestChanged) {
      notes.push('样例摘要（caseDigest）不同：冻结的期望内容变了');
    }
    if (failureCodeChanged) {
      notes.push(
        `失败码 ${describeFailureCode(
          base.failureCode,
        )} → ${describeFailureCode(target.failureCode)}`,
      );
    }
    if (verdictChanged === true) {
      notes.push(`判定变化：${describeVerdictChange(base, target)}`);
    }
    if (verdictChanged === null) {
      notes.push('一侧判定 JSON 无法解析，判定变化无法比较');
    }
    return {
      baseCaseDigest: base.caseDigest,
      baseFailureCode: base.failureCode,
      baseStatus: base.status,
      caseDigestChanged,
      caseKey,
      failureCodeChanged,
      note: notes.length === 0 ? '无变化' : notes.join('；'),
      onlyIn: 'BOTH',
      severity: base.severity ?? target.severity ?? 'UNKNOWN',
      statusChanged,
      targetCaseDigest: target.caseDigest,
      targetFailureCode: target.failureCode,
      targetStatus: target.status,
      verdictChanged,
    };
  });
}

/** 差异概览（页面上先给一句结论） */
export function diffHeadline(rows: RunDiffRow[]): string {
  const changed = rows.filter(
    (row) =>
      row.statusChanged === true ||
      row.caseDigestChanged === true ||
      row.failureCodeChanged === true ||
      row.verdictChanged === true,
  ).length;
  const baseOnly = rows.filter((row) => row.onlyIn === 'BASE').length;
  const targetOnly = rows.filter((row) => row.onlyIn === 'TARGET').length;
  return `共比较 ${rows.length} 例：${changed} 例有变化；仅基准运行 ${baseOnly} 例、仅对比运行 ${targetOnly} 例`;
}

/** 报告展示：能解析就格式化缩进，不能解析就原样展示并标注 */
export function reportDisplay(raw?: null | string): ReportDisplay {
  if (raw === undefined || raw === null || raw.trim() === '') {
    return { parseFailed: false, text: '（报告为空）' };
  }
  try {
    const parsed: unknown = JSON.parse(raw);
    const pretty = JSON.stringify(parsed, null, 2);
    return { parseFailed: false, text: pretty ?? raw };
  } catch {
    return { parseFailed: true, text: raw };
  }
}
