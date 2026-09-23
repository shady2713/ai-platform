import type {
  ReportData,
  ReportSpec,
} from '../../../../../../packages/ai-chat-ui/src/report/reportSpec';

import type { AiReportApi } from '#/api/ai/report';

import {
  parseJsonText,
  safeParseReportData,
  safeParseReportSpec,
} from '../../../../../../packages/ai-chat-ui/src/report/reportSpec';

/** 个人报表页面权限码（与 V78 迁移的 system_menu 种子一致；仅用于菜单可见性） */
export const AI_REPORT_PERMISSIONS = {
  preview: 'ai:report:preview',
} as const;

/** 报表模式展示（模式是报表级语义，创建后不可修改） */
export const REPORT_MODE_TEXT: Record<string, string> = {
  REFRESHABLE: '可刷新（按当前权限重新执行固定查询版本）',
  SNAPSHOT: '快照（数据是保存时的样子）',
};

/** 刷新结果展示（OK/UNCHANGED/FAILED 都是正常结果，失败要能读懂） */
export const REFRESH_STATUS_TEXT: Record<string, string> = {
  FAILED: '刷新失败（已保留上一次结果）',
  OK: '刷新成功（已切换生效版本）',
  UNCHANGED: '数据未变化（未产生新版本）',
};

/** 修订结果展示 */
export const REVISION_OUTCOME_TEXT: Record<string, string> = {
  APPLIED: '已应用（产生新版本，原版本保留）',
  CLARIFICATION: '需要澄清（未修改报表）',
};

/** 预览输入：规格与数据都由前端再解析一次（不合法即拒绝渲染）。 */
export interface ReportPreview {
  /** 失败原因（刷新失败时展示；来自刷新状态的稳定原因码） */
  failureReason: string;
  /** 版本数据（可刷新版本为空，此时按"无数据"处理） */
  data: null | ReportData;
  /** 报表规格（不合法时为 null，界面给出拒绝提示） */
  spec: null | ReportSpec;
  /** 数据截至时间文本（服务端给出的时间，界面只格式化展示） */
  asOfText: string;
  /** 完整性结论（COMPLETE/PARTIAL/FAILED） */
  completeness: string;
}

function formatTime(value: Date | string | undefined): string {
  if (!value) {
    return '';
  }
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const pad = (part: number) => String(part).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/**
 * 组装预览输入：规格来自版本、数据来自"最近一次成功刷新"或快照版本。
 *
 * <p>可刷新版本的版本行不带数据（R04/R06 口径）：数据来自刷新尝试记录；
 * 快照版本的数据在版本行上。两者都由服务端按当前权限复核后返回。
 */
export function previewFrom(
  version: AiReportApi.Version | undefined,
  refreshState: AiReportApi.RefreshState | undefined,
): ReportPreview {
  const spec = version
    ? safeParseReportSpec(parseJsonText(version.specJson))
    : null;
  const dataText = version?.dataJson ?? refreshState?.dataJson ?? null;
  const data = dataText ? safeParseReportData(parseJsonText(dataText)) : null;
  const completeness =
    refreshState?.completeness ?? version?.completeness ?? 'COMPLETE';
  const failureReason =
    refreshState?.status === 'FAILED'
      ? `刷新失败：${refreshState.reason ?? '未知原因'}（时间 ${formatTime(refreshState.asOf) || '未知'}）`
      : '';
  return {
    spec,
    data,
    completeness,
    failureReason,
    asOfText: formatTime(refreshState?.asOf ?? version?.asOf),
  };
}

/** 刷新结果 → 界面文案（含失败原因与时间） */
export function refreshResultText(result: AiReportApi.RefreshResult): string {
  const label = REFRESH_STATUS_TEXT[result.status] ?? result.status;
  const reason = result.reason ? `（原因 ${result.reason}）` : '';
  const time = formatTime(result.asOf);
  return `${label}${reason}${time ? ` · ${time}` : ''}`;
}

/** 修订结果 → 界面文案（澄清时带上追问与候选） */
export function revisionResultText(result: AiReportApi.RevisionResult): string {
  const label = REVISION_OUTCOME_TEXT[result.outcome] ?? result.outcome;
  if (result.outcome === 'CLARIFICATION') {
    const candidates = (result.clarificationCandidates ?? [])
      .map((candidate) => candidate.label || candidate.code)
      .join(' / ');
    return `${label}：${result.clarificationQuestion ?? ''}${candidates ? `（候选：${candidates}）` : ''}`;
  }
  const changed = result.diff
    ? [
        ...(result.diff.modifiedBlocks ?? []).map((block) => `改 ${block}`),
        ...(result.diff.addedBlocks ?? []).map((block) => `增 ${block}`),
        ...(result.diff.removedBlocks ?? []).map((block) => `删 ${block}`),
      ].join('、')
    : '';
  return `${label}${changed ? `：${changed}` : ''}`;
}

/** 版本选项（版本号倒序展示，默认选中当前生效版本） */
export function versionOptions(versions: AiReportApi.VersionBrief[]) {
  return versions.map((version) => ({
    label: `v${version.versionNo}${version.completeness ? ` · ${version.completeness}` : ''}`,
    value: version.versionNo,
  }));
}

/** 刷新按钮是否可用：只有可刷新报表能刷新（快照的数据是保存时的样子） */
export function canRefresh(report: AiReportApi.Report | undefined): boolean {
  return report?.mode === 'REFRESHABLE';
}
