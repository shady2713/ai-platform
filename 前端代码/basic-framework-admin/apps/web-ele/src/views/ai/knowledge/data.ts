import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { AiKnowledgeApi } from '#/api/ai/knowledge';

import { z } from '@vben/common-ui';

/** 知识库权限码（与 V72/V75 迁移的 system_menu 种子一一对应） */
export const AI_KNOWLEDGE_PERMISSIONS = {
  create: 'ai:knowledge:create',
  debug: 'ai:knowledge:debug',
  delete: 'ai:knowledge:delete',
  ingest: 'ai:knowledge:ingest',
  query: 'ai:knowledge:query',
  update: 'ai:knowledge:update',
  version: 'ai:knowledge:version',
} as const;

/** 可见性（共享库靠显式授权放开，应用专用库必须绑定应用） */
export const VISIBILITY_OPTIONS = [
  { label: 'SHARED（共享，跨应用需显式授权）', value: 'SHARED' },
  { label: 'APPLICATION（应用专用）', value: 'APPLICATION' },
];

/** 启停状态 */
export const BASE_STATUS_OPTIONS = [
  { label: '启用', value: 'ENABLED' },
  { label: '停用（不接受新入库）', value: 'DISABLED' },
];

/**
 * 文档状态展示（K09 验收项：失败/删除中/需 OCR 状态可理解）。
 *
 * 状态与原因码都来自后端：界面只做**可读映射**，不改写也不猜测；
 * 失败原因直接展示后端给的稳定原因码（脱敏），OCR 提示来自解析提示字段。
 */
export const DOCUMENT_STATUS_TEXT: Record<string, string> = {
  DELETING: '删除中（检索已不可见，后台回收中）',
  FAILED: '失败（可重试）',
  INDEXING: '切分与向量化中',
  PARSING: '解析中',
  PENDING: '排队中',
  READY: '可用',
};

/** 入库任务状态展示 */
export const TASK_STATUS_TEXT: Record<string, string> = {
  FAILED: '失败（可人工重试）',
  QUEUED: '待处理',
  RUNNING: '执行中',
  SUCCEEDED: '成功',
  UNKNOWN: '结果未知（需人工确认后重试）',
};

/** 失败原因码的可读说明（只解释稳定原因码，不暴露上游报文） */
export const FAILURE_REASON_TEXT: Record<string, string> = {
  'embed-dimension_mismatch':
    '嵌入维度与知识库声明不一致（需新建知识库或换代）',
  'embed-endpoint_unavailable': '知识库声明的嵌入模型没有可用端点',
  'embed-upstream_failed': '嵌入调用失败（可重试）',
  'index-service-unavailable': '向量索引服务未配置',
  'index-write-failed': '索引写入失败（可重试）',
  'parse-corrupt': '文件损坏或结构非法',
  'parse-encrypted': '文件已加密，需要解密后重新上传',
  'parse-ocr_required': '扫描件没有文本层，需要 OCR（首期不支持）',
  'parse-too_large': '超出解析上限（字符/段落/页数）',
  'parse-unsupported_format': '文件类型不支持',
  'source-not_accessible': '原文不可读（引用已解除或无权限）',
  'source-subject_missing': '后台读取缺少主体上下文',
};

/** 文档状态文本（未知状态原样返回，便于发现后端新增状态） */
export function describeDocumentStatus(status?: string): string {
  if (!status) {
    return '-';
  }
  return DOCUMENT_STATUS_TEXT[status] ?? status;
}

/** 失败原因文本（未知原因码原样返回，不吞掉信息） */
export function describeFailureReason(reason?: string): string {
  if (!reason) {
    return '-';
  }
  return FAILURE_REASON_TEXT[reason] ?? reason;
}

/** 需要人工关注的状态（失败/删除中/需 OCR） */
export function needsAttention(row: AiKnowledgeApi.Document): boolean {
  return (
    row.status === 'FAILED' ||
    row.status === 'DELETING' ||
    Boolean(row.parseNote)
  );
}

/** 知识库表格列 */
export function useGridColumns(): VxeTableGridOptions<AiKnowledgeApi.KnowledgeBase>['columns'] {
  return [
    { field: 'code', title: '标识', minWidth: 140 },
    { field: 'name', title: '名称', minWidth: 140 },
    {
      field: 'visibility',
      title: '可见性',
      width: 120,
      formatter: ({ cellValue }) =>
        cellValue === 'SHARED' ? '共享' : '应用专用',
    },
    { field: 'embeddingModel', title: '嵌入模型', minWidth: 160 },
    { field: 'embeddingDimension', title: '维度', width: 80 },
    {
      field: 'activeGenerationNo',
      title: '生效索引代',
      width: 110,
      formatter: ({ cellValue }) => (cellValue ? `g${cellValue}` : '尚无'),
    },
    {
      field: 'status',
      title: '状态',
      width: 90,
      formatter: ({ cellValue }) => (cellValue === 'ENABLED' ? '启用' : '停用'),
    },
    { field: 'version', title: '版本', width: 80 },
    {
      title: '操作',
      width: 320,
      fixed: 'right',
      slots: { default: 'actions' },
    },
  ];
}

/** 知识库搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      componentProps: { placeholder: '请输入知识库标识或名称' },
      fieldName: 'name',
      label: '关键字',
    },
    {
      component: 'Select',
      componentProps: { options: VISIBILITY_OPTIONS, placeholder: '全部' },
      fieldName: 'visibility',
      label: '可见性',
    },
  ];
}

/** 知识库表单（嵌入模型与维度创建后不可修改：编辑时禁用） */
export function useFormSchema(isEdit = false): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      componentProps: {
        disabled: isEdit,
        placeholder: '小写标识，创建后不可修改',
      },
      fieldName: 'code',
      label: '标识',
      rules: z
        .string()
        .min(3, '标识至少 3 个字符')
        .regex(
          /^[a-z][a-z0-9_-]{2,63}$/,
          '小写字母开头，仅小写字母/数字/下划线/连字符',
        ),
    },
    {
      component: 'Input',
      componentProps: { placeholder: '例如：员工手册' },
      fieldName: 'name',
      label: '名称',
      rules: z.string().min(1, '请输入名称').max(128),
    },
    {
      component: 'Textarea',
      componentProps: { placeholder: '说明（可选）', rows: 3 },
      fieldName: 'description',
      label: '说明',
    },
    {
      component: 'Select',
      componentProps: { disabled: isEdit, options: VISIBILITY_OPTIONS },
      defaultValue: 'SHARED',
      fieldName: 'visibility',
      label: '可见性',
    },
    {
      component: 'InputNumber',
      componentProps: {
        disabled: isEdit,
        min: 1,
        placeholder: '应用专用库必填',
      },
      fieldName: 'ownerApplicationId',
      label: '所属应用',
    },
    {
      component: 'Input',
      componentProps: {
        disabled: isEdit,
        placeholder: '例如：text-embedding-3-small',
      },
      fieldName: 'embeddingModel',
      label: '嵌入模型',
      rules: z.string().min(1, '请输入嵌入模型标识').max(64),
    },
    {
      component: 'InputNumber',
      componentProps: { disabled: isEdit, max: 8192, min: 1 },
      defaultValue: 1536,
      fieldName: 'embeddingDimension',
      label: '嵌入维度',
      rules: z.number().int().min(1).max(8192),
    },
    {
      component: 'InputNumber',
      componentProps: { max: 3650, min: 1 },
      defaultValue: 365,
      fieldName: 'retentionDays',
      label: '保留天数',
    },
  ];
}

/** 文档表格列（状态与原因都做可读映射） */
export function useDocumentGridColumns(): VxeTableGridOptions<AiKnowledgeApi.Document>['columns'] {
  return [
    { field: 'sourceKey', title: '来源幂等键', minWidth: 160 },
    { field: 'title', title: '标题', minWidth: 140 },
    {
      field: 'status',
      title: '状态',
      minWidth: 200,
      formatter: ({ row }) => describeDocumentStatus(row.status),
    },
    {
      field: 'parseNote',
      title: '提示',
      minWidth: 140,
      formatter: ({ cellValue }) => (cellValue ? String(cellValue) : '-'),
    },
    {
      field: 'failureReason',
      title: '失败原因',
      minWidth: 220,
      formatter: ({ cellValue }) => describeFailureReason(cellValue),
    },
    { field: 'activeVersionNo', title: '可用版本', width: 100 },
    { field: 'latestVersionNo', title: '最新版本', width: 100 },
    {
      title: '操作',
      width: 260,
      fixed: 'right',
      slots: { default: 'actions' },
    },
  ];
}

/** 文档搜索表单 */
export function useDocumentGridFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      componentProps: { placeholder: '请输入来源幂等键或标题' },
      fieldName: 'title',
      label: '关键字',
    },
    {
      component: 'Select',
      componentProps: {
        options: Object.entries(DOCUMENT_STATUS_TEXT).map(([value, label]) => ({
          label,
          value,
        })),
        placeholder: '全部',
      },
      fieldName: 'status',
      label: '状态',
    },
  ];
}
