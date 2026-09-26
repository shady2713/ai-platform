import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

/**
 * AI 评测控制面 API 客户端（Q05）：管理端通道（`/admin-api`），
 * 权限码与 V83 菜单种子一一对应：查看 `ai:eval:query`、套件/样例维护 `ai:eval:manage`、
 * 执行 `ai:eval:run`、人工复核 `ai:eval:review`。
 *
 * 契约要点：响应只给配置、判定与摘要（不含提示词/响应正文与任何凭据）；
 * 套件冻结后只读，调整必须走 `new-revision`；样例期望规则是 JSON **数组**字符串；
 * 结果 `verdictJson` 是逐条判定数组，计数口径由服务端给出（见 Result 注释）。
 */
export namespace AiEvalApi {
  /** 套件状态：只有 DRAFT 可编辑 */
  export type SuiteStatus = 'DRAFT' | 'FROZEN';

  /** 样例严重级别（闭集） */
  export type Severity = 'BLOCKER' | 'MAJOR' | 'MINOR';

  /** 运行状态 */
  export type RunStatus = 'COMPLETED' | 'FAILED' | 'RUNNING';

  /** 判定：PASSED 计入通过；FAILED 与 REVIEW_REQUIRED 计入失败；ERROR 计入错误 */
  export type ResultStatus = 'ERROR' | 'FAILED' | 'PASSED' | 'REVIEW_REQUIRED';

  /** 复核状态：只有 PENDING 可以复核 */
  export type ReviewStatus =
    | 'APPROVED'
    | 'NOT_REQUIRED'
    | 'PENDING'
    | 'REJECTED';

  /** 期望规则类型（确定性核验，不含模型评分） */
  export type RuleKind =
    | 'CITATION'
    | 'DATE'
    | 'MONEY'
    | 'NO_SECRET'
    | 'STRUCTURE'
    | 'VALUE'
    | 'VERSION';

  /** 评测套件（冻结后 caseCount/contentDigest 是冻结时的事实值） */
  export interface Suite {
    applicationId: number;
    caseCount?: number;
    code: string;
    contentDigest?: string;
    createTime?: Date;
    dataLevel?: string;
    description?: string;
    externalUserId?: string;
    frozenTime?: Date;
    id: number;
    name: string;
    revision?: number;
    serviceId: number;
    status: string;
    subjectType: string;
    version: number;
  }

  /** 创建套件（code 创建后不可修改；样例只允许 L1/L2 分级） */
  export interface SuiteCreateReq {
    applicationId: number;
    code: string;
    dataLevel?: string;
    description?: string;
    externalUserId?: string;
    name: string;
    serviceId: number;
    subjectType: string;
  }

  /** 更新套件（仅草稿；标识与服务不可修改） */
  export interface SuiteUpdateReq {
    dataLevel?: string;
    description?: string;
    id: number;
    name: string;
    version: number;
  }

  /** 带乐观锁版本的套件动作（冻结 / 新建修订） */
  export interface SuiteVersionActionReq {
    id: number;
    version: number;
  }

  /** 套件分页参数（筛选由服务端执行） */
  export interface SuitePageParams extends PageParam {
    applicationId?: number;
    status?: string;
  }

  /** 评测样例（冻结时整体快照进运行） */
  export interface Case {
    caseKey: string;
    checksJson: string;
    expectVersion?: null | string;
    id: number;
    needsReview?: boolean;
    question: string;
    severity: string;
    suiteId: number;
    title: string;
    version: number;
  }

  /** 保存样例（新建不带 id/version，更新必带两者） */
  export interface CaseSaveReq {
    caseKey: string;
    checksJson: string;
    expectVersion?: string;
    id?: number;
    needsReview?: boolean;
    question: string;
    severity: string;
    suiteId: number;
    title: string;
    version?: number;
  }

  /** 带乐观锁版本的样例动作（删除） */
  export interface CaseVersionActionReq {
    id: number;
    version: number;
  }

  /** 评测运行（caseTotal = passedCount + failedCount + errorCount） */
  export interface Run {
    applicationId: number;
    caseTotal: number;
    errorCount: number;
    failedCount: number;
    finishedTime?: Date;
    id: number;
    passedCount: number;
    serviceId: number;
    startedTime?: Date;
    status: string;
    suiteDigest: string;
    suiteId: number;
    suiteRevision?: number;
    summaryJson?: null | string;
  }

  /** 运行分页参数（不传 suiteId 表示全部） */
  export interface RunPageParams extends PageParam {
    suiteId?: number;
  }

  /** 逐条核对结论（verdictJson 的元素） */
  export interface Verdict {
    expected?: null | string;
    index: number;
    kind: string;
    message?: null | string;
    observed?: null | string;
    passed: boolean;
    path?: null | string;
  }

  /** 逐例结果（判定、摘要与复核状态都是事实，不做二次解释） */
  export interface Result {
    caseDigest: string;
    caseId: number;
    caseKey: string;
    expectVersion?: null | string;
    failureCode?: null | string;
    id: number;
    observedVersion?: null | string;
    resultDigest: string;
    reviewNote?: null | string;
    reviewStatus: string;
    reviewedBy?: null | string;
    reviewedTime?: Date;
    runId: number;
    runRef?: null | number;
    severity: string;
    status: string;
    verdictJson?: null | string;
  }

  /** 结果分页参数（status 是判定过滤） */
  export interface ResultPageParams extends PageParam {
    runId: number;
    status?: string;
  }

  /** 人工复核请求（只对 PENDING 结果有效；复核会改写判定与结果摘要） */
  export interface ReviewReq {
    approve: boolean;
    note?: string;
    resultId: number;
  }
}

/** 创建评测套件（返回套件编号） */
export function createSuite(data: AiEvalApi.SuiteCreateReq): Promise<number> {
  return requestClient.post<number>('/ai/eval/suite/create', data);
}

/** 更新评测套件（仅草稿） */
export function updateSuite(data: AiEvalApi.SuiteUpdateReq): Promise<boolean> {
  return requestClient.put<boolean>('/ai/eval/suite/update', data);
}

/** 冻结套件（校验期望规则并记录内容摘要；带乐观锁版本） */
export function freezeSuite(
  data: AiEvalApi.SuiteVersionActionReq,
): Promise<boolean> {
  return requestClient.post<boolean>('/ai/eval/suite/freeze', data);
}

/** 冻结后创建新修订（回到草稿；历史运行摘要不变） */
export function newSuiteRevision(
  data: AiEvalApi.SuiteVersionActionReq,
): Promise<boolean> {
  return requestClient.post<boolean>('/ai/eval/suite/new-revision', data);
}

/** 读取评测套件 */
export function getSuite(id: number): Promise<AiEvalApi.Suite> {
  return requestClient.get<AiEvalApi.Suite>('/ai/eval/suite/get', {
    params: { id },
  });
}

/** 评测套件分页（可按应用与状态过滤） */
export function getSuitePage(
  params: AiEvalApi.SuitePageParams,
): Promise<PageResult<AiEvalApi.Suite>> {
  return requestClient.get<PageResult<AiEvalApi.Suite>>('/ai/eval/suite/page', {
    params,
  });
}

/** 新增评测样例（返回样例编号） */
export function createCase(data: AiEvalApi.CaseSaveReq): Promise<number> {
  return requestClient.post<number>('/ai/eval/case/create', data);
}

/** 更新评测样例（仅草稿套件） */
export function updateCase(data: AiEvalApi.CaseSaveReq): Promise<boolean> {
  return requestClient.put<boolean>('/ai/eval/case/update', data);
}

/** 删除评测样例（请求体带乐观锁版本；软删除，历史运行保留快照） */
export function deleteCase(
  data: AiEvalApi.CaseVersionActionReq,
): Promise<boolean> {
  return requestClient.delete<boolean>('/ai/eval/case/delete', { data });
}

/** 套件内样例（按标识升序） */
export function listCases(suiteId: number): Promise<AiEvalApi.Case[]> {
  return requestClient.get<AiEvalApi.Case[]>('/ai/eval/case/list', {
    params: { suiteId },
  });
}

/** 开始评测（返回评测运行编号） */
export function startRun(suiteId: number): Promise<number> {
  return requestClient.post<number>('/ai/eval/run/start', { suiteId });
}

/** 读取评测运行（含冻结的套件摘要与计数） */
export function getRun(id: number): Promise<AiEvalApi.Run> {
  return requestClient.get<AiEvalApi.Run>('/ai/eval/run/get', {
    params: { id },
  });
}

/** 评测运行分页（可按套件过滤） */
export function getRunPage(
  params: AiEvalApi.RunPageParams,
): Promise<PageResult<AiEvalApi.Run>> {
  return requestClient.get<PageResult<AiEvalApi.Run>>('/ai/eval/run/page', {
    params,
  });
}

/** 运行内逐例结果（按冻结顺序，覆盖全部样例） */
export function listResults(runId: number): Promise<AiEvalApi.Result[]> {
  return requestClient.get<AiEvalApi.Result[]>('/ai/eval/result/list', {
    params: { runId },
  });
}

/** 评测结果分页（可按判定过滤） */
export function getResultPage(
  params: AiEvalApi.ResultPageParams,
): Promise<PageResult<AiEvalApi.Result>> {
  return requestClient.get<PageResult<AiEvalApi.Result>>(
    '/ai/eval/result/page',
    { params },
  );
}

/** 可复现报告（JSON 字符串；不含提示词与响应正文） */
export function getReport(runId: number): Promise<string> {
  return requestClient.get<string>('/ai/eval/report', { params: { runId } });
}

/** 人工复核（true 通过 / false 否决；改变最终判定与结果摘要） */
export function reviewResult(data: AiEvalApi.ReviewReq): Promise<boolean> {
  return requestClient.post<boolean>('/ai/eval/result/review', data);
}
