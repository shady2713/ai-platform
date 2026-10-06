import type { VbenFormSchema } from '#/adapter/form';

import { describe, expect, it } from 'vitest';

import {
  AI_WORKFLOW_PERMISSIONS,
  checkGraphJson,
  describeRun,
  formatDuration,
  formatNodeProgress,
  formatNodeStatus,
  formatNodeType,
  formatRunStatus,
  formatVersionStatus,
  formatWorkflowStatus,
  useFormSchema,
  useGridColumns,
  useGridFormSchema,
  useRunAcceptFormSchema,
  WORKFLOW_GRAPH_EXAMPLE,
  WORKFLOW_GRAPH_LIMITS,
  WORKFLOW_NODE_TYPES,
} from './data';

function graphOf(nodes: unknown, edges: unknown = []) {
  return JSON.stringify({ edges, nodes });
}

function runOf(overrides: Record<string, unknown> = {}) {
  return { id: 5, status: 'SUCCEEDED', workflowId: 81, ...overrides } as never;
}

function disabledOf(
  schema: undefined | VbenFormSchema,
): ((values: { id?: number }) => boolean) | undefined {
  return schema?.dependencies?.disabled as
    | ((values: { id?: number }) => boolean)
    | undefined;
}

describe('ai workflow data', () => {
  it('权限码与 V89 迁移种子 4129-4132 一致', () => {
    expect(AI_WORKFLOW_PERMISSIONS).toEqual({
      delete: 'ai:workflow:delete',
      manage: 'ai:workflow:manage',
      query: 'ai:workflow:query',
      run: 'ai:workflow:run',
    });
  });

  it('图契约上限与后端 AiWorkflowGraph 常量同值', () => {
    expect(WORKFLOW_GRAPH_LIMITS.maxNodes).toBe(32);
    expect(WORKFLOW_GRAPH_LIMITS.maxEdges).toBe(64);
    expect(WORKFLOW_GRAPH_LIMITS.maxGraphJsonLength).toBe(16_000);
    expect(WORKFLOW_GRAPH_LIMITS.maxNodeConfigLength).toBe(4000);
    expect(WORKFLOW_GRAPH_LIMITS.nodeKeyPattern.test('start')).toBe(true);
    expect(WORKFLOW_GRAPH_LIMITS.nodeKeyPattern.test('1start')).toBe(false);
    expect(WORKFLOW_GRAPH_LIMITS.nodeKeyPattern.test('start-1')).toBe(false);
  });

  it('节点类型白名单是冻结的七种，不含脚本节点', () => {
    expect([...WORKFLOW_NODE_TYPES]).toEqual([
      'START',
      'MODEL',
      'KNOWLEDGE_RETRIEVAL',
      'DATA_QUERY',
      'TOOL',
      'CONDITION',
      'END',
    ]);
    expect(WORKFLOW_NODE_TYPES).not.toContain('SCRIPT');
  });

  it('示例图能通过形状预检，且只是声明式 JSON', () => {
    const check = checkGraphJson(WORKFLOW_GRAPH_EXAMPLE);
    expect(check.ok).toBe(true);
    // 预检只覆盖解析层：通过不代表能发布，拓扑判定仍在服务端
    expect(check.message).toContain('发布期');
    expect(WORKFLOW_GRAPH_EXAMPLE).not.toMatch(
      /(?:^|[^a-z])(?:sql|script|statement|function|eval)(?:[^a-z]|$)/i,
    );
  });

  it('形状预检：空值、非 JSON、非对象根都拒绝并说明原因', () => {
    expect(checkGraphJson('')).toEqual({
      message: '请填写流程图 JSON',
      ok: false,
    });
    expect(checkGraphJson('   ').message).toBe('请填写流程图 JSON');
    expect(checkGraphJson(undefined).ok).toBe(false);
    expect(checkGraphJson('{nodes:').message).toBe(
      '流程图 JSON 无法解析，请检查引号与逗号',
    );
    expect(checkGraphJson('[]').message).toBe('流程图 JSON 根节点必须是对象');
    expect(checkGraphJson('"text"').message).toBe(
      '流程图 JSON 根节点必须是对象',
    );
  });

  it('形状预检：nodes/edges 的规模与类型按后端上限拒绝', () => {
    expect(checkGraphJson(graphOf([])).message).toBe('nodes 必须是非空数组');
    expect(
      checkGraphJson(
        graphOf(
          Array.from({ length: 33 }, (_, i) => ({
            key: `n${i}`,
            type: 'MODEL',
          })),
        ),
      ).message,
    ).toContain('节点数最多 32 个');
    expect(
      checkGraphJson('{"nodes":[{"key":"a","type":"START"}]}').message,
    ).toBe('edges 必须是数组（可以为空数组）');
    expect(
      checkGraphJson(
        graphOf(
          [{ key: 'a', type: 'START' }],
          Array.from({ length: 65 }, () => ({ from: 'a', to: 'a' })),
        ),
      ).message,
    ).toContain('边数最多 64 条');
    const oversized = graphOf([
      { key: 'a', type: 'START', name: 'n'.repeat(16_000) },
    ]);
    expect(checkGraphJson(oversized).message).toContain(
      '流程图 JSON 最长 16000 字符',
    );
  });

  it('形状预检：节点键、重复键、未知类型、config 形状都拒绝', () => {
    expect(
      checkGraphJson(graphOf([{ key: '1bad', type: 'START' }])).message,
    ).toContain('不合法：字母开头');
    expect(
      checkGraphJson(
        graphOf([
          { key: 'a', type: 'START' },
          { key: 'a', type: 'END' },
        ]),
      ).message,
    ).toBe('节点键重复：a');
    expect(
      checkGraphJson(graphOf([{ key: 'a', type: 'SCRIPT' }])).message,
    ).toContain('不在冻结白名单内');
    expect(
      checkGraphJson(graphOf([{ key: 'a', type: 'START', config: 'text' }]))
        .message,
    ).toBe('节点 a 的 config 必须是 JSON 对象');
    expect(
      checkGraphJson(
        graphOf([
          { key: 'a', type: 'START', config: { big: 'x'.repeat(4001) } },
        ]),
      ).message,
    ).toContain('config 最长 4000 字符');
  });

  it('形状预检：边必须连已声明节点，分支只认 TRUE/FALSE', () => {
    expect(
      checkGraphJson(
        graphOf([{ key: 'a', type: 'START' }], [{ from: 'a', to: 'b' }]),
      ).message,
    ).toBe('边 a → b 引用了未声明的节点');
    expect(
      checkGraphJson(
        graphOf(
          [
            { key: 'a', type: 'CONDITION' },
            { key: 'b', type: 'END' },
          ],
          [{ branch: 'YES', from: 'a', to: 'b' }],
        ),
      ).message,
    ).toBe('边 a → b 的分支只能是 TRUE/FALSE');
    expect(
      checkGraphJson(
        graphOf(
          [
            { key: 'a', type: 'CONDITION' },
            { key: 'b', type: 'END' },
          ],
          [{ branch: 'true', from: 'a', to: 'b' }],
        ),
      ).ok,
    ).toBe(true);
  });

  it('状态文案覆盖三套状态机，未知值回显占位而不是静默', () => {
    expect(formatWorkflowStatus('ENABLED')).toBe('启用');
    expect(formatWorkflowStatus('DISABLED')).toBe('停用');
    expect(formatWorkflowStatus('WHAT')).toBe('—');
    expect(formatVersionStatus('DRAFT')).toBe('草稿');
    expect(formatVersionStatus('PUBLISHED')).toBe('已发布');
    expect(formatVersionStatus('DISCARDED')).toBe('已废弃');
    expect(formatRunStatus('RUNNING')).toBe('运行中');
    expect(formatRunStatus('SUCCEEDED')).toBe('成功');
    expect(formatRunStatus('FAILED')).toBe('失败');
    expect(formatNodeStatus('SUCCEEDED')).toBe('成功');
    expect(formatNodeStatus('FAILED')).toBe('失败');
    expect(formatNodeStatus('UNKNOWN')).toBe('—');
  });

  it('节点类型给中文名，未知类型原样回显', () => {
    expect(formatNodeType('KNOWLEDGE_RETRIEVAL')).toBe('知识检索');
    expect(formatNodeType('CONDITION')).toBe('条件');
    expect(formatNodeType('FUTURE_TYPE')).toBe('FUTURE_TYPE');
    expect(formatNodeType(undefined)).toBe('—');
  });

  it('节点进度缺值不编造 0，耗时单位可读', () => {
    expect(formatNodeProgress(runOf({ nodeExecuted: 3, nodeTotal: 7 }))).toBe(
      '3/7',
    );
    expect(formatNodeProgress(runOf({ nodeTotal: 7 }))).toBe('0/7');
    expect(formatNodeProgress(runOf())).toBe('—');
    expect(formatNodeProgress(undefined)).toBe('—');
    expect(formatDuration(250)).toBe('250 毫秒');
    expect(formatDuration(1500)).toBe('1.50 秒');
    expect(formatDuration(undefined)).toBe('—');
  });

  it('运行结论永远带稳定原因码，人工确认不算执行失败', () => {
    expect(describeRun(undefined)).toBe('—');
    expect(describeRun(runOf())).toBe('成功');
    expect(
      describeRun(
        runOf({ errorCode: 'AI_WORKFLOW_GRAPH_NO_EXIT', status: 'FAILED' }),
      ),
    ).toBe('失败：AI_WORKFLOW_GRAPH_NO_EXIT');
    expect(describeRun(runOf({ status: 'FAILED' }))).toBe('失败');
    expect(
      describeRun(
        runOf({
          errorCode: 'AI_TOOL_CONFIRMATION_REQUIRED',
          status: 'FAILED',
        }),
      ),
    ).toContain('工具节点需人工确认');
  });

  it('主列表列与搜索表单覆盖关键词/应用/状态', () => {
    const columns = useGridColumns() ?? [];
    expect(columns.map((column) => column.field)).toEqual(
      expect.arrayContaining(['applicationId', 'code', 'name', 'status']),
    );
    const statusColumn = columns.find((column) => column.field === 'status');
    expect(
      (statusColumn?.formatter as (params: { cellValue: string }) => string)({
        cellValue: 'DISABLED',
      }),
    ).toBe('停用');
    expect(useGridFormSchema().map((schema) => schema.fieldName)).toEqual([
      'code',
      'applicationId',
      'status',
    ]);
  });

  it('标识与所属应用创建后不可修改', () => {
    const schemas = useFormSchema();
    expect(schemas.map((schema) => schema.fieldName)).toEqual([
      'id',
      'code',
      'applicationId',
      'name',
      'description',
    ]);
    for (const field of ['code', 'applicationId']) {
      const disabled = disabledOf(
        schemas.find((schema) => schema.fieldName === field),
      );
      expect(disabled?.({ id: 81 })).toBe(true);
      expect(disabled?.({})).toBe(false);
    }
  });

  it('受理表单字段与后端 AiWorkflowRunAcceptReqVO 一致', () => {
    const schemas = useRunAcceptFormSchema();
    expect(schemas.map((schema) => schema.fieldName)).toEqual([
      'idempotencyKey',
      'dataLevel',
      'inputText',
      'maxSteps',
      'maxDurationMillis',
    ]);
    expect(
      schemas.find((schema) => schema.fieldName === 'dataLevel')?.defaultValue,
    ).toBe('L2_INTERNAL');
  });

  it('所有输入面都是声明式字段，没有 SQL/脚本入口', () => {
    const fields = [
      ...useFormSchema().map((schema) => schema.fieldName),
      ...useRunAcceptFormSchema().map((schema) => schema.fieldName),
    ];
    expect(fields).toContain('code');
    expect(fields).toContain('idempotencyKey');
    expect(
      fields.some((field) =>
        /(?:^|[^a-z])(?:sql|script|statement)(?:[^a-z]|$)/i.test(String(field)),
      ),
    ).toBe(false);
  });
});
