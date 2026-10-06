import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { AiWorkflowApi, AiWorkflowRunApi } from '#/api/ai/workflow';

import { z } from '@vben/common-ui';

/**
 * 流程编排权限码（与 V89 迁移的 system_menu 种子 4129–4132 一一对应）：
 * 定义维护/版本命令共用 manage，受控运行单独授权，后端仍会二次校验。
 */
export const AI_WORKFLOW_PERMISSIONS = {
  delete: 'ai:workflow:delete',
  manage: 'ai:workflow:manage',
  query: 'ai:workflow:query',
  run: 'ai:workflow:run',
} as const;

/**
 * 冻结的图契约上限（与后端 AiWorkflowGraph 常量同值）。
 *
 * 抄一份在前端只为**提前**给出可读的错误，而不是让用户提交后才看到 1_003_xxx；
 * 真正的形状与拓扑判定始终在服务端，前端不重复实现发布期校验（环/无出口/端口类型）。
 */
export const WORKFLOW_GRAPH_LIMITS = {
  maxEdges: 64,
  maxGraphJsonLength: 16_000,
  maxNodeConfigLength: 4000,
  maxNodes: 32,
  /** 节点键模式：字母开头，字母数字下划线，1..64 */
  nodeKeyPattern: /^[A-Z]\w{0,63}$/i,
} as const;

/** 节点类型白名单（X08 冻结七种，禁止任意脚本节点） */
export const WORKFLOW_NODE_TYPES = [
  'START',
  'MODEL',
  'KNOWLEDGE_RETRIEVAL',
  'DATA_QUERY',
  'TOOL',
  'CONDITION',
  'END',
] as const;

/** 条件节点出边的分支取值 */
export const WORKFLOW_BRANCH_VALUES = ['TRUE', 'FALSE'] as const;

/** 示例图：声明式 nodes/edges，发布期即可通过结构校验的最简骨架 */
export const WORKFLOW_GRAPH_EXAMPLE = JSON.stringify({
  edges: [
    { from: 'start', to: 'summary' },
    { from: 'summary', to: 'end' },
  ],
  nodes: [
    { key: 'start', name: '开始', type: 'START' },
    {
      config: { endpointId: 1, promptTemplate: '{{input}}' },
      key: 'summary',
      name: '生成摘要',
      type: 'MODEL',
    },
    { key: 'end', name: '结束', type: 'END' },
  ],
});

/** 启停状态（停用后不受理新运行） */
export const WORKFLOW_STATUS_OPTIONS = [
  { label: '启用', value: 'ENABLED' },
  { label: '停用', value: 'DISABLED' },
];

/** 版本状态（发布后不可修改，废弃是终态） */
export const WORKFLOW_VERSION_STATUS_OPTIONS = [
  { label: '草稿', value: 'DRAFT' },
  { label: '已发布', value: 'PUBLISHED' },
  { label: '已废弃', value: 'DISCARDED' },
];

/** 运行状态 */
export const WORKFLOW_RUN_STATUS_OPTIONS = [
  { label: '运行中', value: 'RUNNING' },
  { label: '成功', value: 'SUCCEEDED' },
  { label: '失败', value: 'FAILED' },
];

/** 节点留痕状态（服务端只产出成功/失败两种终态） */
export const WORKFLOW_NODE_STATUS_OPTIONS = [
  { label: '成功', value: 'SUCCEEDED' },
  { label: '失败', value: 'FAILED' },
];

/** 数据等级（模型节点外发等级，流程运行只允许公开与内部两级） */
export const WORKFLOW_DATA_LEVEL_OPTIONS = [
  { label: 'L1 公开', value: 'L1_PUBLIC' },
  { label: 'L2 内部', value: 'L2_INTERNAL' },
];

function labelOf(
  options: { label: string; value: string }[],
  value?: string,
  fallback = '—',
) {
  return options.find((option) => option.value === value)?.label ?? fallback;
}

/** 流程定义状态文案 */
export function formatWorkflowStatus(status?: string) {
  return labelOf(WORKFLOW_STATUS_OPTIONS, status);
}

/** 版本状态文案 */
export function formatVersionStatus(status?: string) {
  return labelOf(WORKFLOW_VERSION_STATUS_OPTIONS, status);
}

/** 运行状态文案 */
export function formatRunStatus(status?: string) {
  return labelOf(WORKFLOW_RUN_STATUS_OPTIONS, status);
}

/** 节点留痕状态文案（服务端只产出成功/失败两种终态） */
export function formatNodeStatus(status?: string) {
  return labelOf(WORKFLOW_NODE_STATUS_OPTIONS, status);
}

/** 节点类型中文名：未在白名单内就原样回显，避免把未知类型静默成"其它" */
export function formatNodeType(nodeType?: string) {
  const labels: Record<string, string> = {
    CONDITION: '条件',
    DATA_QUERY: '数据查询',
    END: '结束',
    KNOWLEDGE_RETRIEVAL: '知识检索',
    MODEL: '模型',
    START: '开始',
    TOOL: '工具',
  };
  return nodeType ? (labels[nodeType] ?? nodeType) : '—';
}

/** 节点进度（已执行/总数，缺值时不编造 0） */
export function formatNodeProgress(run?: AiWorkflowRunApi.Run) {
  if (run?.nodeTotal === undefined) {
    return '—';
  }
  return `${run.nodeExecuted ?? 0}/${run.nodeTotal}`;
}

/** 耗时文案（毫秒转可读） */
export function formatDuration(durationMs?: number) {
  if (durationMs === undefined) {
    return '—';
  }
  return durationMs >= 1000
    ? `${(durationMs / 1000).toFixed(2)} 秒`
    : `${durationMs} 毫秒`;
}

/**
 * 运行结论：状态 + 稳定错误码。
 *
 * 失败一定显示原因码（不静默成"稍后重试"）。需要人工确认的工具节点会以
 * AI_TOOL_CONFIRMATION_REQUIRED 受控结束——那不是执行失败，文案必须说清楚。
 */
export function describeRun(run?: AiWorkflowRunApi.Run) {
  if (!run) {
    return '—';
  }
  const status = formatRunStatus(run.status);
  if (run.status !== 'FAILED') {
    return status;
  }
  if (run.errorCode === 'AI_TOOL_CONFIRMATION_REQUIRED') {
    return `${status}：工具节点需人工确认，流程在受控确认链路外结束`;
  }
  return run.errorCode ? `${status}：${run.errorCode}` : status;
}

interface GraphCheck {
  message: string;
  ok: boolean;
}

/**
 * 图 JSON 的**解析层**形状预检（镜像后端 AiWorkflowGraph.parse 的判定）。
 *
 * 只覆盖服务端解析层就会拒绝的形状/规模问题，目的是提前给出可读原因。
 * 环、无出口、端口类型、引用核对属于发布期服务端校验，这里**不猜**也不代替——
 * 预检通过不代表一定能发布，发布结果以服务端返回为准。
 */
export function checkGraphJson(graphJson?: string): GraphCheck {
  const text = (graphJson ?? '').trim();
  if (!text) {
    return { message: '请填写流程图 JSON', ok: false };
  }
  if (text.length > WORKFLOW_GRAPH_LIMITS.maxGraphJsonLength) {
    return {
      message: `流程图 JSON 最长 ${WORKFLOW_GRAPH_LIMITS.maxGraphJsonLength} 字符（当前 ${text.length}）`,
      ok: false,
    };
  }

  let root: unknown;
  try {
    root = JSON.parse(text);
  } catch {
    return { message: '流程图 JSON 无法解析，请检查引号与逗号', ok: false };
  }
  if (typeof root !== 'object' || root === null || Array.isArray(root)) {
    return { message: '流程图 JSON 根节点必须是对象', ok: false };
  }

  const { edges, nodes } = root as { edges?: unknown; nodes?: unknown };
  if (!Array.isArray(nodes) || nodes.length === 0) {
    return { message: 'nodes 必须是非空数组', ok: false };
  }
  if (nodes.length > WORKFLOW_GRAPH_LIMITS.maxNodes) {
    return {
      message: `节点数最多 ${WORKFLOW_GRAPH_LIMITS.maxNodes} 个（当前 ${nodes.length}）`,
      ok: false,
    };
  }
  if (!Array.isArray(edges)) {
    return { message: 'edges 必须是数组（可以为空数组）', ok: false };
  }
  if (edges.length > WORKFLOW_GRAPH_LIMITS.maxEdges) {
    return {
      message: `边数最多 ${WORKFLOW_GRAPH_LIMITS.maxEdges} 条（当前 ${edges.length}）`,
      ok: false,
    };
  }

  const keys = new Set<string>();
  for (const raw of nodes) {
    const node = raw as { config?: unknown; key?: unknown; type?: unknown };
    const key = typeof node?.key === 'string' ? node.key.trim() : '';
    if (!WORKFLOW_GRAPH_LIMITS.nodeKeyPattern.test(key)) {
      return {
        message: `节点键 "${key || '(空)'}" 不合法：字母开头，仅字母数字下划线，1–64 位`,
        ok: false,
      };
    }
    if (keys.has(key)) {
      return { message: `节点键重复：${key}`, ok: false };
    }
    keys.add(key);
    if (!WORKFLOW_NODE_TYPES.includes(node?.type as never)) {
      return {
        message: `节点 ${key} 的类型不在冻结白名单内（${WORKFLOW_NODE_TYPES.join('/')}）`,
        ok: false,
      };
    }
    if (node?.config !== undefined) {
      if (
        typeof node.config !== 'object' ||
        node.config === null ||
        Array.isArray(node.config)
      ) {
        return { message: `节点 ${key} 的 config 必须是 JSON 对象`, ok: false };
      }
      const configLength = JSON.stringify(node.config).length;
      if (configLength > WORKFLOW_GRAPH_LIMITS.maxNodeConfigLength) {
        return {
          message: `节点 ${key} 的 config 最长 ${WORKFLOW_GRAPH_LIMITS.maxNodeConfigLength} 字符（当前 ${configLength}）`,
          ok: false,
        };
      }
    }
  }

  for (const raw of edges) {
    const edge = raw as { branch?: unknown; from?: unknown; to?: unknown };
    const from = typeof edge?.from === 'string' ? edge.from.trim() : '';
    const to = typeof edge?.to === 'string' ? edge.to.trim() : '';
    if (!keys.has(from) || !keys.has(to)) {
      return {
        message: `边 ${from || '(空)'} → ${to || '(空)'} 引用了未声明的节点`,
        ok: false,
      };
    }
    if (
      edge?.branch !== undefined &&
      !WORKFLOW_BRANCH_VALUES.includes(
        String(edge.branch).toUpperCase() as never,
      )
    ) {
      return {
        message: `边 ${from} → ${to} 的分支只能是 ${WORKFLOW_BRANCH_VALUES.join('/')}`,
        ok: false,
      };
    }
  }

  return {
    message: '形状与规模校验通过（环/无出口/类型由发布期服务端校验）',
    ok: true,
  };
}

/** 主列表表格列 */
export function useGridColumns(): VxeTableGridOptions<AiWorkflowApi.Workflow>['columns'] {
  return [
    { field: 'code', title: '标识', minWidth: 150 },
    { field: 'name', title: '名称', minWidth: 140 },
    { field: 'applicationId', title: '应用编号', width: 100 },
    {
      field: 'status',
      title: '状态',
      width: 90,
      formatter: ({ cellValue }) => formatWorkflowStatus(cellValue as string),
    },
    { field: 'latestVersionNo', title: '最新版本', width: 100 },
    { field: 'version', title: '乐观锁', width: 90 },
    {
      title: '操作',
      width: 400,
      fixed: 'right',
      slots: { default: 'actions' },
    },
  ];
}

/** 主列表搜索表单（标识/名称模糊，状态精确，应用编号精确） */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      componentProps: { placeholder: '标识或名称关键词' },
      fieldName: 'code',
      label: '关键词',
    },
    {
      component: 'InputNumber',
      componentProps: { min: 1 },
      fieldName: 'applicationId',
      label: '应用编号',
    },
    {
      component: 'Select',
      componentProps: {
        options: WORKFLOW_STATUS_OPTIONS,
        placeholder: '请选择状态',
      },
      fieldName: 'status',
      label: '状态',
    },
  ];
}

/** 流程定义新增/修改表单：标识与所属应用创建后不可修改 */
export function useFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      componentProps: { disabled: true },
      dependencies: { show: () => false, triggerFields: ['id'] },
      fieldName: 'id',
      label: 'id',
    },
    {
      component: 'Input',
      componentProps: { placeholder: '如 order-summary-flow' },
      dependencies: {
        disabled: (values) => Boolean(values?.id),
        triggerFields: ['id'],
      },
      fieldName: 'code',
      label: '标识',
      rules: z
        .string()
        .min(3, '标识至少 3 个字符')
        .max(64, '标识最多 64 个字符')
        .regex(/^[A-Z][\w-]{2,63}$/i, '字母开头，仅字母数字与 -_'),
    },
    {
      component: 'InputNumber',
      componentProps: { min: 1, placeholder: '应用编号' },
      dependencies: {
        disabled: (values) => Boolean(values?.id),
        triggerFields: ['id'],
      },
      fieldName: 'applicationId',
      label: '应用编号',
      rules: z.coerce.number().int().positive('请输入应用编号'),
    },
    {
      component: 'Input',
      componentProps: { placeholder: '如 订单摘要生成流程' },
      fieldName: 'name',
      label: '名称',
      rules: z.string().min(1, '请输入名称').max(128, '名称最多 128 个字符'),
    },
    {
      component: 'Input',
      componentProps: { rows: 2, type: 'textarea' },
      fieldName: 'description',
      label: '说明',
    },
  ];
}

/** 受控运行受理表单：预算可空（空即用平台默认），幂等键必填 */
export function useRunAcceptFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      componentProps: { placeholder: '16–128 位，同一流程内唯一' },
      fieldName: 'idempotencyKey',
      label: '受理幂等键',
      rules: z
        .string()
        .min(16, '幂等键至少 16 位')
        .max(128, '幂等键最多 128 位'),
    },
    {
      component: 'Select',
      componentProps: {
        options: WORKFLOW_DATA_LEVEL_OPTIONS,
        placeholder: '请选择数据等级',
      },
      defaultValue: 'L2_INTERNAL',
      fieldName: 'dataLevel',
      label: '数据等级',
      rules: z.string().min(1, '请选择数据等级'),
    },
    {
      component: 'Input',
      componentProps: {
        placeholder: '开始节点透传的文本（最多 4000 字）',
        rows: 3,
        type: 'textarea',
      },
      fieldName: 'inputText',
      label: '运行输入',
    },
    {
      component: 'InputNumber',
      componentProps: { min: 0, placeholder: '留空用平台默认（只能更紧）' },
      fieldName: 'maxSteps',
      label: '步数预算',
    },
    {
      component: 'InputNumber',
      componentProps: { min: 0, placeholder: '留空用平台默认（封顶 120000）' },
      fieldName: 'maxDurationMillis',
      label: '耗时预算(毫秒)',
    },
  ];
}
