/**
 * `#/api/ai/report` 与 `#/api/ai/open-platform` 的探针桥接：只换 HTTP 传输。
 *
 * <p>报表页的安全语义集中在"票据"与"失权"两处，所以桥接要能把**失败**也造出来：
 *  - `setReportFailures({ ticket })`：让 `issueDebugTicket` 以稳定文案拒绝 →
 *    页面必须把该文案显示在 `report-ticket-failure` 上，而不是吞掉或显示"成功"
 *    （Q11 §4：失败路径钉稳定文案，不得只断言"抛了异常"）；
 *  - `setReportFailures({ refresh })`：让 `refreshReport` 以稳定文案拒绝 →
 *    页面必须如实显示失败原因，不得假装刷新成功（生产 `index.vue:173` 的 catch 分支）。
 *
 * <p>规格/数据正文直接复用 AT-065 的**冻结样例**（`fixtures/at-065/render-samples.ts`，
 * 与既有浏览器用例同一份），不在这里另抄一份：报表预览走的是生产解析器
 * `safeParseReportSpec` / `safeParseReportData`，夹具若与冻结样例漂移，断言就失去意义。
 */
import {
  REPORT_DATA_SAMPLE,
  REPORT_SPEC_SAMPLE,
} from '../../at-065/render-samples';
import { recordApiCall } from './state';

const FROZEN_SPEC_JSON = JSON.stringify(REPORT_SPEC_SAMPLE);
const FROZEN_DATA_JSON = JSON.stringify(REPORT_DATA_SAMPLE);

/** 失败注入开关：Playwright 在挂载前写入。 */
let ticketFailure: string | undefined;
let refreshFailure: string | undefined;

export function setReportFailures(options: {
  refresh?: string;
  ticket?: string;
}): void {
  refreshFailure = options.refresh;
  ticketFailure = options.ticket;
}

/** 两条报表：一可刷新、一快照。`canRefresh` 只对 `REFRESHABLE` 为真。 */
function reportRows(): Array<Record<string, unknown>> {
  return [
    {
      code: 'rpt_sales',
      id: 1,
      latestVersionNo: 2,
      mode: 'REFRESHABLE',
      name: '华东客户净销售额',
      publishedVersionNo: 2,
      schemaVersion: '1.0',
      version: 3,
    },
    {
      code: 'rpt_snapshot',
      id: 2,
      latestVersionNo: 1,
      mode: 'SNAPSHOT',
      name: '月末应收快照',
      publishedVersionNo: 1,
      schemaVersion: '1.0',
      version: 1,
    },
  ];
}

function versionPayload(versionNo: number) {
  return {
    asOf: '2026-08-31T23:59:59',
    completeness: 'COMPLETE',
    dataJson: FROZEN_DATA_JSON,
    id: versionNo,
    mode: 'REFRESHABLE',
    specJson: FROZEN_SPEC_JSON,
    versionNo,
  };
}

/* --- `#/api/ai/open-platform` --- */

export function issueDebugTicket(
  appCode: string,
  appSecret: string,
  subjectType: string,
  externalUserId?: string,
) {
  recordApiCall('issueDebugTicket', {
    appCode,
    // 密钥只记"是否提交"，不回显内容：探针产物与断言输出里都不该出现明文
    appSecretProvided: appSecret.length > 0,
    externalUserId,
    subjectType,
  });
  if (ticketFailure) {
    return Promise.reject(new Error(ticketFailure));
  }
  return Promise.resolve({ expiresInSeconds: 300, token: 'probe-ticket' });
}

/* --- `#/api/ai/report` --- */

export function getReportPage(ticket: string, params: unknown) {
  recordApiCall('getReportPage', { hasTicket: ticket.length > 0, params });
  return Promise.resolve({ list: reportRows(), total: reportRows().length });
}

export function listReportVersions(ticket: string, reportId: unknown) {
  recordApiCall('listReportVersions', {
    hasTicket: ticket.length > 0,
    reportId,
  });
  return Promise.resolve([
    { completeness: 'COMPLETE', id: 1, mode: 'REFRESHABLE', versionNo: 1 },
    { completeness: 'COMPLETE', id: 2, mode: 'REFRESHABLE', versionNo: 2 },
  ]);
}

export function readCurrentReportVersion(ticket: string, reportId: unknown) {
  recordApiCall('readCurrentReportVersion', {
    hasTicket: ticket.length > 0,
    reportId,
  });
  return Promise.resolve(versionPayload(2));
}

export function readReportVersion(
  ticket: string,
  reportId: unknown,
  versionNo: unknown,
) {
  recordApiCall('readReportVersion', {
    hasTicket: ticket.length > 0,
    reportId,
    versionNo,
  });
  return Promise.resolve(versionPayload(Number(versionNo) || 1));
}

export function readRefreshState(ticket: string, reportId: unknown) {
  recordApiCall('readRefreshState', { hasTicket: ticket.length > 0, reportId });
  return Promise.resolve({
    asOf: '2026-08-31T23:59:59',
    completeness: 'COMPLETE',
    dataJson: FROZEN_DATA_JSON,
    reportId,
    status: 'OK',
  });
}

export function refreshReport(ticket: string, data: unknown) {
  recordApiCall('refreshReport', { data, hasTicket: ticket.length > 0 });
  if (refreshFailure) {
    return Promise.reject(new Error(refreshFailure));
  }
  return Promise.resolve({
    asOf: '2026-09-30T23:59:59',
    status: 'OK',
    versionNo: 3,
  });
}

export function reviseReport(ticket: string, data: unknown) {
  recordApiCall('reviseReport', { data, hasTicket: ticket.length > 0 });
  return Promise.resolve({ status: 'OK', versionNo: 3 });
}
