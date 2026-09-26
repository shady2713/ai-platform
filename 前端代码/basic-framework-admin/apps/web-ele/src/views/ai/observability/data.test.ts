import { describe, expect, it } from 'vitest';

import {
  buildRunQuery,
  canRetry,
  describeFailureReason,
  describeRunStatus,
  describeTaskStatus,
  formatDurationMs,
  retryBlockedText,
  timingRows,
} from './data';

describe('运行监控数据口径（Q03）', () => {
  it('状态与原因码有可读文案，未知值原样展示', () => {
    expect(describeRunStatus('FAILED')).toBe('失败');
    expect(describeRunStatus('FUTURE')).toBe('FUTURE');
    expect(describeRunStatus(undefined)).toBe('-');
    expect(describeTaskStatus('UNKNOWN')).toContain('结果未知');
    expect(describeFailureReason('STEP_BUDGET_EXCEEDED')).toContain('步数');
    expect(describeFailureReason('502')).toBe('502');
    expect(describeFailureReason(undefined)).toBe('-');
  });

  it('耗时未知显示「未记录」，真实 0 照实显示', () => {
    expect(formatDurationMs(undefined)).toBe('未记录');
    expect(formatDurationMs(null)).toBe('未记录');
    expect(formatDurationMs(0)).toBe('0 ms');
    expect(formatDurationMs(1234)).toBe('1234 ms');
  });

  it('重试入口只对服务端判定可重试的行显示，并给出阻塞原因', () => {
    expect(canRetry({ retryable: true })).toBe(true);
    expect(canRetry({ retryable: false })).toBe(false);
    expect(canRetry(undefined)).toBe(false);
    expect(
      retryBlockedText({
        retryBlockedReason: '结果未知（UNKNOWN），请核对后新建运行',
      }),
    ).toContain('结果未知');
    expect(retryBlockedText(undefined)).toBe('当前状态不可重试');
  });

  it('耗时分解把实测与未计量分开（不把未知填成 0）', () => {
    const rows = timingRows({
      modelDurationMs: 900,
      modelInvocationCount: 2,
      totalDurationMs: 5000,
      unknownUsageCount: 1,
      unmeasuredStages: ['RETRIEVAL', 'BUSINESS_API'],
    });
    const byStage = new Map(rows.map((row) => [row.stage, row]));

    expect(byStage.get('MODEL')?.value).toBe('900 ms');
    expect(byStage.get('MODEL')?.note).toBe('2 次调用，来源未知 1 次');
    expect(byStage.get('RETRIEVAL')?.value).toBe('未单独计量');
    expect(byStage.get('BUSINESS_API')?.value).toBe('未单独计量');
    expect(byStage.get('TOTAL')?.value).toBe('5000 ms');
    // 服务端没有给 unmeasuredStages 时，界面不替它宣称"未计量"（未知一律显示未记录）
    expect(timingRows(undefined).map((row) => row.value)).toStrictEqual([
      '未记录',
      '未记录',
      '未记录',
      '未记录',
    ]);
    expect(
      timingRows({ retrievalDurationMs: 120, totalDurationMs: 900 }).map(
        (row) => row.value,
      ),
    ).toStrictEqual(['未记录', '120 ms', '未记录', '900 ms']);
  });

  it('查询参数只下发已填写的筛选项与时间窗', () => {
    expect(buildRunQuery({ pageNo: 1, pageSize: 20 })).toStrictEqual({
      pageNo: 1,
      pageSize: 20,
    });
    expect(
      buildRunQuery({
        applicationId: 3,
        pageNo: 2,
        pageSize: 50,
        range: ['2026-09-01T00:00:00', '2026-09-26T00:00:00'],
        serviceId: 9,
        status: 'FAILED',
        subjectType: 'USER',
      }),
    ).toStrictEqual({
      applicationId: 3,
      from: '2026-09-01T00:00:00',
      pageNo: 2,
      pageSize: 50,
      serviceId: 9,
      status: 'FAILED',
      subjectType: 'USER',
      to: '2026-09-26T00:00:00',
    });
  });
});
