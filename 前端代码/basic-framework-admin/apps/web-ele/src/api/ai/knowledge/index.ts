import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

/**
 * 知识库域 API（K09）：知识库 / 文档与入库任务 / 检索调试。
 *
 * 字段与后端 VO 一一对应（K02/K03/K06 的协议层）；界面不猜字段：
 *  - 文档状态与失败原因由后端给出稳定原因码，界面只做展示映射；
 *  - 原文预览走受控 API（`/ai/knowledge/document/content`），不直连存储；
 *  - 检索调试返回的引用来自本次检索候选，界面不构造引用。
 */
export namespace AiKnowledgeApi {
  /** 知识库 */
  export interface KnowledgeBase {
    id: number;
    code: string;
    name: string;
    description?: string;
    visibility: string;
    ownerApplicationId?: number;
    managerUserId?: number;
    embeddingModel: string;
    embeddingDimension: number;
    activeGenerationNo?: number;
    retentionDays?: number;
    status: string;
    version: number;
    createTime?: Date;
  }

  /** 新增/修改知识库（标识、可见性、嵌入模型与维度创建后不可修改） */
  export interface KnowledgeBaseSaveReq {
    id?: number;
    code: string;
    name: string;
    description?: string;
    visibility: string;
    ownerApplicationId?: number;
    managerUserId?: number;
    embeddingModel: string;
    embeddingDimension: number;
    retentionDays?: number;
    version?: number;
  }

  /** 知识文档 */
  export interface Document {
    id: number;
    knowledgeBaseId: number;
    sourceKey: string;
    title: string;
    sourceType: string;
    sourceRef?: string;
    status: string;
    activeVersionNo?: number;
    latestVersionNo?: number;
    failureReason?: string;
    parseNote?: string;
    version: number;
    createTime?: Date;
  }

  /** 文档版本 */
  export interface DocumentVersion {
    id: number;
    documentId: number;
    versionNo: number;
    fileId: number;
    contentHash: string;
    sourceRef?: string;
    status: string;
    indexGeneration?: number;
    chunkCount?: number;
    failureReason?: string;
    readyAt?: Date;
    version: number;
  }

  /** 入库任务 */
  export interface IngestionTask {
    id: number;
    knowledgeBaseId: number;
    documentId: number;
    documentVersionId: number;
    taskKind: string;
    status: string;
    attemptCount: number;
    maxAttempts: number;
    nextAttemptTime?: Date;
    lastErrorCode?: string;
    version: number;
  }

  /** 检索命中引用（来自本次检索候选） */
  export interface Citation {
    citationId: string;
    title?: string;
    versionNo?: number;
    chunkIndex?: number;
    locationRef?: string;
    snippet?: string;
  }

  /** 检索结论 */
  export interface SearchResult {
    citations: Citation[];
    candidateCount?: number;
    filteredOutCount?: number;
    searchedKnowledgeBaseCount?: number;
    noEvidence?: boolean;
  }
}

/** 知识库分页 */
export function getKnowledgeBasePage(params: PageParam) {
  return requestClient.get<PageResult<AiKnowledgeApi.KnowledgeBase>>(
    '/ai/knowledge-base/page',
    { params },
  );
}

export function getKnowledgeBase(id: number) {
  return requestClient.get<AiKnowledgeApi.KnowledgeBase>(
    `/ai/knowledge-base/get?id=${id}`,
  );
}

export function createKnowledgeBase(data: AiKnowledgeApi.KnowledgeBaseSaveReq) {
  return requestClient.post<number>('/ai/knowledge-base/create', data);
}

export function updateKnowledgeBase(data: AiKnowledgeApi.KnowledgeBaseSaveReq) {
  return requestClient.put<boolean>('/ai/knowledge-base/update', data);
}

export function updateKnowledgeBaseStatus(
  id: number,
  version: number,
  enabled: boolean,
) {
  return requestClient.put<boolean>(
    '/ai/knowledge-base/update-status',
    undefined,
    {
      params: { enabled, id, version },
    },
  );
}

export function deleteKnowledgeBase(id: number, version: number) {
  return requestClient.delete<boolean>('/ai/knowledge-base/delete', {
    params: { id, version },
  });
}

/** 文档分页 */
export function getDocumentPage(params: PageParam) {
  return requestClient.get<PageResult<AiKnowledgeApi.Document>>(
    '/ai/knowledge-document/page',
    { params },
  );
}

/** 上传并入库（multipart：文件 + 幂等键 + 标题） */
export function uploadDocument(data: {
  file: File;
  knowledgeBaseId: number;
  sourceKey: string;
  sourceRef?: string;
  title: string;
}) {
  const formData = new FormData();
  formData.append('file', data.file);
  formData.append('knowledgeBaseId', String(data.knowledgeBaseId));
  formData.append('sourceKey', data.sourceKey);
  formData.append('title', data.title);
  if (data.sourceRef) {
    formData.append('sourceRef', data.sourceRef);
  }
  return requestClient.post<{
    createdVersion?: boolean;
    documentId: number;
    reused?: boolean;
    taskId: number;
    versionId: number;
    versionNo: number;
  }>('/ai/knowledge-document/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}

export function deleteDocument(id: number, version: number) {
  return requestClient.delete<boolean>('/ai/knowledge-document/delete', {
    params: { id, version },
  });
}

export function getDocumentVersionPage(params: PageParam) {
  return requestClient.get<PageResult<AiKnowledgeApi.DocumentVersion>>(
    '/ai/knowledge-document/version/page',
    {
      params,
    },
  );
}

export function getIngestionTaskPage(params: PageParam) {
  return requestClient.get<PageResult<AiKnowledgeApi.IngestionTask>>(
    '/ai/knowledge-document/task/page',
    {
      params,
    },
  );
}

/** 人工重试（仅失败/结果未知的任务） */
export function retryIngestionTask(id: number, version: number) {
  return requestClient.post<boolean>(
    '/ai/knowledge-document/task/retry',
    undefined,
    {
      params: { id, version },
    },
  );
}

/** 检索调试（过滤条件由服务端按当前授权生成） */
export function searchKnowledge(data: { query: string; topK?: number }) {
  return requestClient.post<AiKnowledgeApi.SearchResult>(
    '/ai/knowledge/search',
    data,
  );
}

/** 读取引用片段（重新鉴权后回到原文） */
export function readCitation(citationId: string) {
  return requestClient.get<string>(
    `/ai/knowledge/citation?citationId=${encodeURIComponent(citationId)}`,
  );
}

/** 读取原文（受控 API，返回二进制） */
export function readDocumentContent(documentId: number) {
  return requestClient.get<Blob>(
    `/ai/knowledge/document/content?documentId=${documentId}`,
    {
      responseType: 'blob',
    },
  );
}
