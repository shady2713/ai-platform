import { describe, expect, it } from 'vitest';

import {
  AI_QUERY_PERMISSIONS,
  buildPlanRequest,
  buildSummaryParams,
  CLARIFICATION_REASON_LABELS,
  describeClarificationReason,
  describePlanResult,
  formatJsonForRead,
  MAX_REPAIRS_LIMIT,
  parseFieldCodes,
  usePlanFormSchema,
  useSummaryFormSchema,
} from './data';

/** D05 查询计划：字段必须与后端 VO 一一对应，页面不得出现 SQL 输入面。 */
describe('ai query data', () => {
  it('权限码与 V69 菜单种子一致', () => {
    expect(AI_QUERY_PERMISSIONS).toEqual({
      plan: 'ai:query:plan',
      summary: 'ai:query:summary',
    });
  });

  it('计划表单字段与后端 AiQueryPlanReqVO 一致（无 SQL 输入面）', () => {
    const schema = usePlanFormSchema();
    expect(schema.map((item) => item.fieldName)).toEqual([
      'datasetId',
      'datasetVersionId',
      'endpointId',
      'question',
      'allowedFieldCodes',
      'maxRepairs',
    ]);
    expect(
      schema.some((item) =>
        /(?:^|[^a-z])(?:sql|statement|script)(?:[^a-z]|$)/iu.test(
          String(item.fieldName),
        ),
      ),
    ).toBe(false);
  });

  it('问题必填且不超过 2000 字符（对齐后端 @NotEmpty @Size）', () => {
    const question = usePlanFormSchema().find(
      (item) => item.fieldName === 'question',
    );
    const datasetId = usePlanFormSchema().find(
      (item) => item.fieldName === 'datasetId',
    );
    const rules = question?.rules as {
      safeParse: (value: unknown) => { success: boolean };
    };
    const idRules = datasetId?.rules as {
      safeParse: (value: unknown) => { success: boolean };
    };

    expect(rules.safeParse('').success).toBe(false);
    expect(rules.safeParse('   ').success).toBe(false); // 纯空白也会被后端 @NotEmpty 拒绝
    expect(rules.safeParse('a'.repeat(2000)).success).toBe(true);
    expect(rules.safeParse('a'.repeat(2001)).success).toBe(false);
    expect(idRules.safeParse(0).success).toBe(false);
    expect(idRules.safeParse(-1).success).toBe(false);
    expect(idRules.safeParse(1.5).success).toBe(false);
    expect(idRules.safeParse(81).success).toBe(true);
  });

  it('摘要表单只保留 summary 端点接受的三个参数', () => {
    expect(useSummaryFormSchema().map((item) => item.fieldName)).toEqual([
      'datasetId',
      'datasetVersionId',
      'allowedFieldCodes',
    ]);
  });

  it('字段码去重去空，空输入落到"全部字段"缺省语义', () => {
    expect(parseFieldCodes(' amount , channel ,, amount ')).toEqual([
      'amount',
      'channel',
    ]);
    expect(parseFieldCodes('gmv\tregion')).toEqual(['gmv', 'region']);
    expect(parseFieldCodes('')).toBeUndefined();
    expect(parseFieldCodes('  ,  ')).toBeUndefined();
    expect(parseFieldCodes()).toBeUndefined();
  });

  it('计划请求省略可选项，且不把空修复次数当成 0 下发', () => {
    expect(
      buildPlanRequest({
        datasetId: 81,
        endpointId: 7,
        question: ' 近 30 天成交额 ',
      }),
    ).toEqual({ datasetId: 81, endpointId: 7, question: '近 30 天成交额' });

    expect(
      buildPlanRequest({
        allowedFieldCodes: 'amount, amount',
        datasetId: 81,
        datasetVersionId: 3,
        endpointId: 7,
        maxRepairs: null,
        question: '各渠道成交额',
      }),
    ).toEqual({
      allowedFieldCodes: ['amount'],
      datasetId: 81,
      datasetVersionId: 3,
      endpointId: 7,
      question: '各渠道成交额',
    });

    expect(
      buildPlanRequest({
        datasetId: 81,
        endpointId: 7,
        maxRepairs: 0,
        question: '各渠道成交额',
      }).maxRepairs,
    ).toBe(0);
  });

  it('摘要请求绝不携带 question/endpointId/maxRepairs', () => {
    // 变量（非字面量）刻意混入计划端点字段：摘要请求只应认数据集维度参数
    const polluted = {
      allowedFieldCodes: 'amount',
      datasetId: 81,
      datasetVersionId: 3,
      endpointId: 7,
      maxRepairs: 1,
      question: '各渠道成交额',
    };
    const params = buildSummaryParams(polluted);

    expect(params).toEqual({
      allowedFieldCodes: ['amount'],
      datasetId: 81,
      datasetVersionId: 3,
    });
    expect(Object.keys(params)).not.toContain('question');
    expect(Object.keys(params)).not.toContain('endpointId');
  });

  it('jSON 展示能缩进，非法内容原样回显而不抛错', () => {
    expect(formatJsonForRead('{"metrics":[],"datasetId":"dset_1"}')).toBe(
      '{\n  "metrics": [],\n  "datasetId": "dset_1"\n}',
    );
    expect(formatJsonForRead('not-json')).toBe('not-json');
    expect(formatJsonForRead()).toBe('');
  });

  it('澄清原因码翻译成中文，未知码原样回显', () => {
    expect(Object.keys(CLARIFICATION_REASON_LABELS).toSorted()).toEqual([
      'AMBIGUOUS',
      'OUT_OF_SCOPE',
      'UNSUPPORTED',
    ]);
    expect(describeClarificationReason('AMBIGUOUS')).toContain('歧义');
    expect(describeClarificationReason('UNSUPPORTED')).toContain('SQL');
    expect(describeClarificationReason('BRAND_NEW_REASON')).toBe(
      '未识别原因码：BRAND_NEW_REASON',
    );
    expect(describeClarificationReason()).toBe('未返回原因码');
  });

  it('计划结论摊平版本锚点与哈希；澄清结论只报原因与候选数', () => {
    expect(
      describePlanResult({
        datasetCode: 'crm-orders',
        datasetVersionNo: 3,
        kind: 'PLAN',
        planDatasetId: 'dset_9',
        planHash: 'ph-1',
        schemaHash: 'sh-1',
        attempts: 1,
      }),
    ).toBe(
      '数据集 crm-orders · 计划标识 dset_9 · 语义版本 v3｜计划哈希 ph-1｜定义哈希 sh-1｜模型输出 1 次',
    );

    expect(
      describePlanResult({
        candidates: [{ code: 'amount', label: '成交金额' }],
        kind: 'CLARIFICATION',
        reason: 'OUT_OF_SCOPE',
      }),
    ).toBe('超出当前数据集或服务范围｜候选 1 项');

    // 带版本锚点的澄清：锚点在前，且不与原因之间出现空分隔符
    expect(
      describePlanResult({
        candidates: [],
        datasetCode: 'crm-orders',
        datasetVersionNo: 2,
        kind: 'CLARIFICATION',
        reason: 'AMBIGUOUS',
      }),
    ).toBe(
      '数据集 crm-orders · 语义版本 v2｜口径或字段有歧义，请在候选中选择｜候选 0 项',
    );

    // 缺字段时如实标注"未返回"，不静默显示空白
    expect(describePlanResult({ kind: 'PLAN' })).toBe(
      '计划哈希 （未返回）｜定义哈希 （未返回）｜模型输出 0 次',
    );
  });

  it('修复次数上限与后端 MAX_REPAIRS 一致', () => {
    expect(MAX_REPAIRS_LIMIT).toBe(2);
    const maxRepairs = usePlanFormSchema().find(
      (item) => item.fieldName === 'maxRepairs',
    );
    const rules = maxRepairs?.rules as {
      safeParse: (value: unknown) => { success: boolean };
    };
    expect(rules.safeParse(3).success).toBe(false);
    expect(rules.safeParse(-1).success).toBe(false);
    expect(rules.safeParse(2).success).toBe(true);
    expect(rules.safeParse(undefined).success).toBe(true);
  });
});
