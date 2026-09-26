import type { AiEvalApi } from '#/api/ai/evaluation';

import { describe, expect, it } from 'vitest';

import {
  buildResultQuery,
  buildSuiteQuery,
  classifyFailures,
  compareRuns,
  countingCheckText,
  countingSummary,
  diffHeadline,
  frozenCaseCount,
  parseChecks,
  parseVerdicts,
  pendingReviewText,
  reportDisplay,
  sortByCaseKey,
  verdictRows,
  verdictSummary,
} from './analysis';

function run(overrides: Partial<AiEvalApi.Run> = {}): AiEvalApi.Run {
  return {
    applicationId: 1,
    caseTotal: 4,
    errorCount: 1,
    failedCount: 2,
    id: 31,
    passedCount: 1,
    serviceId: 4,
    status: 'COMPLETED',
    suiteDigest: 'a'.repeat(64),
    suiteId: 11,
    suiteRevision: 2,
    summaryJson: '{"cases":[{"caseKey":"a"},{"caseKey":"b"}]}',
    ...overrides,
  };
}

function result(overrides: Partial<AiEvalApi.Result> = {}): AiEvalApi.Result {
  return {
    caseDigest: 'c'.repeat(64),
    caseId: 21,
    caseKey: 'money-round',
    id: 41,
    resultDigest: 'd'.repeat(64),
    reviewStatus: 'NOT_REQUIRED',
    runId: 31,
    severity: 'BLOCKER',
    status: 'PASSED',
    verdictJson: JSON.stringify([
      {
        expected: '100.00',
        index: 0,
        kind: 'MONEY',
        path: 'total',
        passed: true,
      },
    ]),
    ...overrides,
  };
}

describe('评测控制面分析口径（Q05）', () => {
  it('期望规则提交前必须是非空 JSON 数组对象，错误可读', () => {
    expect(parseChecks('')).toStrictEqual({
      message: '期望规则不能为空：至少写 1 条 JSON 规则',
      ok: false,
    });
    expect(parseChecks('{oops')).toMatchObject({ ok: false });
    expect(parseChecks('{oops')).toHaveProperty(
      'message',
      '不是合法 JSON：请检查括号、引号与逗号（期望规则是数组）',
    );
    expect(parseChecks('{"kind":"VALUE"}')).toHaveProperty(
      'message',
      '期望规则必须是 JSON 数组，例如 [{"kind":"VALUE","path":"answer","expect":"42"}]',
    );
    expect(parseChecks('[]')).toHaveProperty(
      'message',
      '期望规则不能为空数组：至少 1 条规则',
    );
    const tooMany = JSON.stringify(
      Array.from({ length: 33 }, () => ({ kind: 'VALUE' })),
    );
    expect(parseChecks(tooMany).ok).toBe(false);
    expect(parseChecks('[1]')).toHaveProperty(
      'message',
      '第 1 条规则必须是 JSON 对象（不能是数组或标量）',
    );
    const parsed = parseChecks(' [ { "kind": "VALUE" } ] ');
    expect(parsed.ok).toBe(true);
    expect(parsed.ok ? parsed.value : '').toBe('[{"kind":"VALUE"}]');
  });

  it('verdictJson 的解析与摘要：无法解析不臆造，未产生判定不等于通过', () => {
    expect(parseVerdicts(undefined)).toStrictEqual({ verdicts: [] });
    expect(parseVerdicts('')).toStrictEqual({ verdicts: [] });
    expect(parseVerdicts('not-json')).toHaveProperty(
      'error',
      '判定 JSON 无法解析（页面按原文展示，计数不臆造）',
    );
    expect(parseVerdicts('{"index":0}')).toHaveProperty(
      'error',
      '判定 JSON 不是数组（契约要求逐条判定数组）',
    );
    expect(parseVerdicts('[{"kind":"VALUE"}]')).toHaveProperty(
      'error',
      '判定 JSON 有 1 条不符合契约（缺少 index/kind/passed），未计入统计',
    );

    const rows = verdictRows(
      JSON.stringify([
        {
          expected: '2',
          index: 1,
          kind: 'STRUCTURE',
          message: '缺字段',
          observed: '1',
          passed: false,
          path: 'items',
        },
        { index: 0, kind: 'VALUE', passed: true },
      ]),
    );
    expect(rows.map((row) => row.index)).toStrictEqual([0, 1]);
    expect(rows[0]).toMatchObject({
      expected: '（未给期望值）',
      kindLabel: '取值',
      message: '（无说明）',
      observed: '（未给实际值）',
      path: '（缺省路径）',
    });
    expect(rows[1]).toMatchObject({ kindLabel: '结构', passed: false });

    expect(verdictSummary(result())).toBe('全部 1 条规则通过');
    expect(
      verdictSummary(
        result({
          verdictJson: JSON.stringify([
            { index: 0, kind: 'MONEY', passed: false },
            { index: 1, kind: 'MONEY', passed: true },
            { index: 2, kind: 'NO_SECRET', passed: false },
          ]),
        }),
      ),
    ).toBe('2/3 条规则未通过（金额、无秘密）');
    expect(
      verdictSummary(result({ status: 'ERROR', verdictJson: undefined })),
    ).toBe('未产生判定（结果：错误（未能执行，计入 errorCount））');
    expect(verdictSummary(result({ verdictJson: '{bad' }))).toContain(
      '判定不可用',
    );
  });

  it('计数口径可解释：通过只计 PASSED，失败含待复核，三项之和要核对', () => {
    expect(countingSummary(undefined)).toBe('尚未选择运行，无法解释计数口径');
    expect(countingSummary(run())).toContain('通过 1（只计 PASSED）');
    expect(countingSummary(run())).toContain('不能只用通过样例做分母');
    expect(countingCheckText(run())).toBe(
      '计数核对：1 + 2 + 1 = 4，与样例总数一致',
    );
    expect(countingCheckText(run({ caseTotal: 5 }))).toBe(
      '计数核对：1 + 2 + 1 = 4，与样例总数 5 不一致，请以逐例结果为准',
    );
    expect(countingCheckText(undefined)).toBe('尚未选择运行，无法核对计数');
    expect(pendingReviewText(0)).toBe('没有等待人工复核的结果');
    expect(pendingReviewText(2)).toContain('复核通过前不计入通过');
  });

  it('运行冻结快照条数与样例排序：解析不了显示未知', () => {
    expect(frozenCaseCount('{"cases":[{"caseKey":"a"}]}')).toBe(1);
    expect(frozenCaseCount('{bad')).toBeUndefined();
    expect(frozenCaseCount('{"cases":{}}')).toBeUndefined();
    expect(frozenCaseCount(undefined)).toBeUndefined();
    expect(frozenCaseCount('[]')).toBeUndefined();
    expect(
      sortByCaseKey([{ caseKey: 'b' }, { caseKey: 'a' }]).map(
        (item) => item.caseKey,
      ),
    ).toStrictEqual(['a', 'b']);
  });

  it('查询参数只下发已填写的筛选项', () => {
    expect(buildSuiteQuery({ pageNo: 1, pageSize: 20 })).toStrictEqual({
      pageNo: 1,
      pageSize: 20,
    });
    expect(
      buildSuiteQuery({
        applicationId: 3,
        pageNo: 2,
        pageSize: 50,
        status: 'FROZEN',
      }),
    ).toStrictEqual({
      applicationId: 3,
      pageNo: 2,
      pageSize: 50,
      status: 'FROZEN',
    });
    expect(
      buildResultQuery({ pageNo: 1, pageSize: 20, runId: 31 }),
    ).toStrictEqual({ pageNo: 1, pageSize: 20, runId: 31 });
    expect(
      buildResultQuery({
        pageNo: 3,
        pageSize: 10,
        runId: 31,
        status: 'REVIEW_REQUIRED',
      }),
    ).toStrictEqual({
      pageNo: 3,
      pageSize: 10,
      runId: 31,
      status: 'REVIEW_REQUIRED',
    });
  });

  it('失败分类覆盖全部结果：级别、规则类型、错误码与判定分布', () => {
    const empty = classifyFailures([]);
    expect(empty).toStrictEqual({
      byFailureCode: [],
      byKind: [],
      bySeverity: [],
      byStatus: [],
      pendingReview: 0,
      total: 0,
      unparsedVerdicts: 0,
    });

    const classification = classifyFailures([
      result({ id: 41 }),
      result({
        caseKey: 'date-tz',
        id: 42,
        reviewStatus: 'PENDING',
        severity: 'MAJOR',
        status: 'REVIEW_REQUIRED',
        verdictJson: JSON.stringify([
          { index: 0, kind: 'DATE', passed: false },
          { index: 1, kind: 'MONEY', passed: true },
        ]),
      }),
      result({
        caseKey: 'structure-out',
        failureCode: 'AI_EVAL_EXECUTION_FAILED',
        id: 43,
        reviewStatus: 'PENDING',
        severity: 'MAJOR',
        status: 'ERROR',
        verdictJson: undefined,
      }),
      result({
        caseKey: 'broken-verdict',
        id: 44,
        severity: 'MINOR',
        status: 'FAILED',
        verdictJson: '{bad',
      }),
    ]);

    expect(classification.total).toBe(4);
    expect(classification.pendingReview).toBe(2);
    expect(classification.unparsedVerdicts).toBe(1);

    const bySeverity = new Map(
      classification.bySeverity.map((row) => [row.severity, row]),
    );
    expect(bySeverity.get('BLOCKER')).toMatchObject({
      error: 0,
      failed: 0,
      failedRules: 0,
      passed: 1,
      pendingReview: 0,
      total: 1,
    });
    expect(bySeverity.get('MAJOR')).toMatchObject({
      error: 1,
      failed: 1,
      failedRules: 1,
      passed: 0,
      pendingReview: 2,
      total: 2,
    });
    expect(bySeverity.get('MINOR')).toMatchObject({
      failed: 1,
      passed: 0,
      total: 1,
    });
    // 阻断级排最前，未登记的级别排在已知级别之后
    expect(classification.bySeverity.map((row) => row.severity)).toStrictEqual([
      'BLOCKER',
      'MAJOR',
      'MINOR',
    ]);

    const byKind = new Map(classification.byKind.map((row) => [row.kind, row]));
    expect(byKind.get('MONEY')).toMatchObject({ checked: 2, failed: 0 });
    expect(byKind.get('DATE')).toMatchObject({ checked: 1, failed: 1 });
    expect(classification.byKind[0]).toMatchObject({ failed: 1, kind: 'DATE' });

    expect(classification.byFailureCode).toStrictEqual([
      {
        code: 'AI_EVAL_EXECUTION_FAILED',
        count: 1,
        label: '执行未完成（未产生判定，不能按通过计）',
      },
    ]);
    expect(
      classification.byStatus.map((row) => [row.status, row.count]),
    ).toStrictEqual([
      ['REVIEW_REQUIRED', 1],
      ['FAILED', 1],
      ['ERROR', 1],
      ['PASSED', 1],
    ]);
  });

  it('用例级 diff：状态、摘要、失败码、判定变化与单侧样例都标出来', () => {
    const base = [
      result({ caseDigest: 'a'.repeat(64), id: 41 }),
      result({
        caseKey: 'date-tz',
        id: 42,
        status: 'FAILED',
        verdictJson: JSON.stringify([
          { index: 0, kind: 'DATE', passed: false },
          { index: 1, kind: 'VALUE', passed: true },
        ]),
      }),
      result({ caseKey: 'only-base', id: 43, status: 'FAILED' }),
    ];
    const target = [
      result({ caseDigest: 'a'.repeat(64), id: 51 }),
      result({
        caseKey: 'date-tz',
        id: 52,
        status: 'REVIEW_REQUIRED',
        verdictJson: JSON.stringify([
          { index: 0, kind: 'DATE', passed: true },
          { index: 1, kind: 'VALUE', passed: true },
        ]),
      }),
      result({
        caseKey: 'only-target',
        failureCode: 'AI_EVAL_EXECUTION_FAILED',
        id: 53,
        status: 'ERROR',
        verdictJson: undefined,
      }),
      result({
        caseDigest: 'e'.repeat(64),
        caseKey: 'money-round',
        id: 54,
        status: 'FAILED',
        failureCode: '1003009013',
      }),
    ];

    const rows = compareRuns(base, target);
    const byKey = new Map(rows.map((row) => [row.caseKey, row]));

    expect(rows.map((row) => row.caseKey)).toStrictEqual([
      'date-tz',
      'money-round',
      'only-base',
      'only-target',
    ]);
    expect(byKey.get('date-tz')).toMatchObject({
      caseDigestChanged: false,
      failureCodeChanged: false,
      onlyIn: 'BOTH',
      statusChanged: true,
      verdictChanged: true,
    });
    expect(byKey.get('date-tz')?.note).toContain('判定变化：DATE#0');
    expect(byKey.get('money-round')).toMatchObject({
      caseDigestChanged: true,
      failureCodeChanged: true,
      statusChanged: true,
      verdictChanged: false,
    });
    expect(byKey.get('money-round')?.note).toContain('失败码');
    expect(byKey.get('money-round')?.note).toContain('冻结的期望内容变了');
    expect(byKey.get('only-base')).toMatchObject({
      caseDigestChanged: null,
      onlyIn: 'BASE',
      statusChanged: null,
      verdictChanged: null,
    });
    expect(byKey.get('only-base')?.note).toContain('仅基准运行');
    expect(byKey.get('only-target')).toMatchObject({ onlyIn: 'TARGET' });
    expect(byKey.get('only-target')?.severity).toBe('BLOCKER');

    // 判定 JSON 无法解析：不假装"无变化"，标成无法比较
    const unparsable = compareRuns(
      [result({ verdictJson: '{bad' })],
      [result({ id: 61, status: 'FAILED' })],
    );
    expect(unparsable[0]).toMatchObject({
      note: expect.stringContaining('无法比较'),
      verdictChanged: null,
    });

    // 判定条数不同也直接说明
    expect(
      compareRuns(
        [result({ verdictJson: '[{"index":0,"kind":"VALUE","passed":true}]' })],
        [
          result({
            verdictJson:
              '[{"index":0,"kind":"VALUE","passed":true},{"index":1,"kind":"VALUE","passed":true}]',
          }),
        ],
      )[0]?.note,
    ).toContain('判定条数不同（1 → 2）');

    // 完全一致时给"无变化"
    expect(compareRuns([result()], [result({ id: 71 })])[0]).toMatchObject({
      note: '无变化',
      statusChanged: false,
      verdictChanged: false,
    });
    expect(diffHeadline(rows)).toBe(
      '共比较 4 例：2 例有变化；仅基准运行 1 例、仅对比运行 1 例',
    );
  });

  it('报告展示：合法 JSON 格式化缩进，非法 JSON 原样展示并标注', () => {
    const pretty = reportDisplay('{"run":{"runId":31},"cases":[]}');
    expect(pretty.parseFailed).toBe(false);
    expect(pretty.text).toContain('\n  "run": {');
    expect(pretty.text).toContain('"cases": []');

    const raw = reportDisplay('<img src=x onerror=alert(1)>');
    expect(raw).toStrictEqual({
      parseFailed: true,
      text: '<img src=x onerror=alert(1)>',
    });

    expect(reportDisplay(undefined)).toStrictEqual({
      parseFailed: false,
      text: '（报告为空）',
    });
    expect(reportDisplay('   ')).toStrictEqual({
      parseFailed: false,
      text: '（报告为空）',
    });
  });
});
