import { beforeEach, describe, expect, it, vi } from 'vitest';

const request = vi.hoisted(() => ({
  delete: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}));

vi.mock('#/api/request', () => ({
  requestClient: request,
}));

const {
  createCase,
  createSuite,
  deleteCase,
  freezeSuite,
  getReport,
  getResultPage,
  getRun,
  getRunPage,
  getSuite,
  getSuitePage,
  listCases,
  listResults,
  newSuiteRevision,
  reviewResult,
  startRun,
  updateCase,
  updateSuite,
} = await import('./index');

describe('评测控制面 API 客户端（Q05）', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    request.get.mockResolvedValue(null);
    request.post.mockResolvedValue(1);
    request.put.mockResolvedValue(true);
    request.delete.mockResolvedValue(true);
  });

  it('套件读写打到与控制器一致的路径与参数', async () => {
    await createSuite({
      applicationId: 1,
      code: 'crm-smoke',
      dataLevel: 'L2_INTERNAL',
      description: '冒烟集',
      name: 'CRM 冒烟',
      serviceId: 4,
      subjectType: 'APP',
    });
    expect(request.post).toHaveBeenCalledWith('/ai/eval/suite/create', {
      applicationId: 1,
      code: 'crm-smoke',
      dataLevel: 'L2_INTERNAL',
      description: '冒烟集',
      name: 'CRM 冒烟',
      serviceId: 4,
      subjectType: 'APP',
    });

    await updateSuite({
      dataLevel: 'L1_PUBLIC',
      description: '改说明',
      id: 11,
      name: 'CRM 冒烟 v2',
      version: 3,
    });
    expect(request.put).toHaveBeenCalledWith('/ai/eval/suite/update', {
      dataLevel: 'L1_PUBLIC',
      description: '改说明',
      id: 11,
      name: 'CRM 冒烟 v2',
      version: 3,
    });

    await freezeSuite({ id: 11, version: 3 });
    expect(request.post).toHaveBeenCalledWith('/ai/eval/suite/freeze', {
      id: 11,
      version: 3,
    });

    await newSuiteRevision({ id: 11, version: 4 });
    expect(request.post).toHaveBeenCalledWith('/ai/eval/suite/new-revision', {
      id: 11,
      version: 4,
    });

    await getSuite(11);
    expect(request.get).toHaveBeenCalledWith('/ai/eval/suite/get', {
      params: { id: 11 },
    });

    await getSuitePage({ applicationId: 1, pageNo: 2, pageSize: 20 });
    expect(request.get).toHaveBeenCalledWith('/ai/eval/suite/page', {
      params: { applicationId: 1, pageNo: 2, pageSize: 20 },
    });
  });

  it('样例读写删（删除走请求体带版本）打到与控制器一致的路径', async () => {
    const payload = {
      caseKey: 'money-round',
      checksJson: '[{"kind":"MONEY","path":"total","expect":"100.00"}]',
      expectVersion: 'endpoint-rev-2',
      needsReview: true,
      question: '合计是多少？',
      severity: 'BLOCKER',
      suiteId: 11,
      title: '金额四舍五入',
    };
    await createCase(payload);
    expect(request.post).toHaveBeenCalledWith('/ai/eval/case/create', payload);

    await updateCase({ ...payload, id: 21, version: 5 });
    expect(request.put).toHaveBeenCalledWith('/ai/eval/case/update', {
      ...payload,
      id: 21,
      version: 5,
    });

    await deleteCase({ id: 21, version: 5 });
    expect(request.delete).toHaveBeenCalledWith('/ai/eval/case/delete', {
      data: { id: 21, version: 5 },
    });

    await listCases(11);
    expect(request.get).toHaveBeenCalledWith('/ai/eval/case/list', {
      params: { suiteId: 11 },
    });
  });

  it('运行、结果、报告与复核打到与控制器一致的路径与参数', async () => {
    await startRun(11);
    expect(request.post).toHaveBeenCalledWith('/ai/eval/run/start', {
      suiteId: 11,
    });

    await getRun(31);
    expect(request.get).toHaveBeenCalledWith('/ai/eval/run/get', {
      params: { id: 31 },
    });

    await getRunPage({ pageNo: 1, pageSize: 20, suiteId: 11 });
    expect(request.get).toHaveBeenCalledWith('/ai/eval/run/page', {
      params: { pageNo: 1, pageSize: 20, suiteId: 11 },
    });

    await listResults(31);
    expect(request.get).toHaveBeenCalledWith('/ai/eval/result/list', {
      params: { runId: 31 },
    });

    await getResultPage({
      pageNo: 1,
      pageSize: 20,
      runId: 31,
      status: 'REVIEW_REQUIRED',
    });
    expect(request.get).toHaveBeenCalledWith('/ai/eval/result/page', {
      params: {
        pageNo: 1,
        pageSize: 20,
        runId: 31,
        status: 'REVIEW_REQUIRED',
      },
    });

    await getReport(31);
    expect(request.get).toHaveBeenCalledWith('/ai/eval/report', {
      params: { runId: 31 },
    });

    await reviewResult({ approve: true, note: '已人工核对', resultId: 41 });
    expect(request.post).toHaveBeenCalledWith('/ai/eval/result/review', {
      approve: true,
      note: '已人工核对',
      resultId: 41,
    });
  });

  it('返回值原样透传（客户端不做二次解释）', async () => {
    request.get.mockResolvedValue('{"run":{"runId":31}}');
    await expect(getReport(31)).resolves.toBe('{"run":{"runId":31}}');

    request.post.mockResolvedValue(37);
    await expect(startRun(11)).resolves.toBe(37);

    request.post.mockResolvedValue(false);
    await expect(reviewResult({ approve: false, resultId: 41 })).resolves.toBe(
      false,
    );
  });
});
