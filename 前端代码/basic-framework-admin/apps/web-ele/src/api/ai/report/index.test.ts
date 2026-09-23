import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getReport,
  getReportPage,
  listReportVersions,
  readCurrentReportVersion,
  readRefreshState,
  readReportVersion,
  refreshReport,
  reviseReport,
  saveReport,
} from './index';

const requestClient = vi.hoisted(() => ({
  get: vi.fn((..._args: unknown[]) => Promise.resolve({})),
  post: vi.fn((..._args: unknown[]) => Promise.resolve({})),
}));

vi.mock('@vben/request', () => ({
  RequestClient: class {
    public get = requestClient.get;
    public post = requestClient.post;
  },
}));

describe('ai report api（应用端通道）', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('报表与版本读取走应用端路径并带内存票据', async () => {
    await getReportPage('ticket-1', {
      mode: 'REFRESHABLE',
      pageNo: 1,
      pageSize: 10,
    });
    expect(requestClient.get).toHaveBeenCalledWith('/ai/report/page', {
      headers: { Authorization: 'Bearer ticket-1' },
      params: { mode: 'REFRESHABLE', pageNo: 1, pageSize: 10 },
    });

    await getReport('ticket-1', 71);
    expect(requestClient.get).toHaveBeenCalledWith('/ai/report/get', {
      headers: { Authorization: 'Bearer ticket-1' },
      params: { id: 71 },
    });

    await listReportVersions('ticket-1', 71);
    expect(requestClient.get).toHaveBeenCalledWith('/ai/report/versions', {
      headers: { Authorization: 'Bearer ticket-1' },
      params: { id: 71 },
    });

    await readCurrentReportVersion('ticket-1', 71);
    expect(requestClient.get).toHaveBeenCalledWith('/ai/report/current', {
      headers: { Authorization: 'Bearer ticket-1' },
      params: { id: 71 },
    });

    await readReportVersion('ticket-1', 71, 1);
    expect(requestClient.get).toHaveBeenCalledWith('/ai/report/version', {
      headers: { Authorization: 'Bearer ticket-1' },
      params: { id: 71, versionNo: 1 },
    });
  });

  it('保存、修订与刷新只提交协议允许的字段', async () => {
    await saveReport('ticket-1', {
      code: 'r07_sales',
      mode: 'SNAPSHOT',
      name: '销售总览',
      specJson: '{"schemaVersion":"1.0"}',
      version: 0,
    });
    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/report/save',
      {
        code: 'r07_sales',
        mode: 'SNAPSHOT',
        name: '销售总览',
        specJson: '{"schemaVersion":"1.0"}',
        version: 0,
      },
      { headers: { Authorization: 'Bearer ticket-1' } },
    );

    await reviseReport('ticket-1', {
      baseVersionNo: 1,
      endpointId: 5,
      id: 71,
      instruction: '把柱状图换成折线图',
      version: 1,
    });
    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/report/revise',
      {
        baseVersionNo: 1,
        endpointId: 5,
        id: 71,
        instruction: '把柱状图换成折线图',
        version: 1,
      },
      { headers: { Authorization: 'Bearer ticket-1' } },
    );

    await refreshReport('ticket-1', { id: 71 });
    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/report/refresh',
      { id: 71 },
      { headers: { Authorization: 'Bearer ticket-1' } },
    );

    await readRefreshState('ticket-1', 71);
    expect(requestClient.get).toHaveBeenCalledWith('/ai/report/refresh/last', {
      headers: { Authorization: 'Bearer ticket-1' },
      params: { id: 71 },
    });
  });

  it('请求体不携带归属、行范围或允许数据集字段（客户端无法放大范围）', async () => {
    await reviseReport('ticket-1', {
      baseVersionNo: 1,
      endpointId: 5,
      id: 71,
      instruction: '按周汇总',
      version: 1,
    });
    const revisePayload = requestClient.post.mock.calls.at(-1)?.[1] as Record<
      string,
      unknown
    >;
    // 归属来自服务端会话、行范围来自授权层：客户端既不能自报身份也不能放大数据范围
    expect(Object.keys(revisePayload).toSorted()).toEqual([
      'baseVersionNo',
      'endpointId',
      'id',
      'instruction',
      'version',
    ]);

    await refreshReport('ticket-1', { id: 71 });
    const refreshPayload = requestClient.post.mock.calls.at(-1)?.[1] as Record<
      string,
      unknown
    >;
    expect(Object.keys(refreshPayload)).toEqual(['id']);
  });
});
