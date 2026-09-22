import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createKnowledgeBase,
  deleteDocument,
  deleteKnowledgeBase,
  getDocumentPage,
  getDocumentVersionPage,
  getIngestionTaskPage,
  getKnowledgeBase,
  getKnowledgeBasePage,
  readCitation,
  readDocumentContent,
  retryIngestionTask,
  searchKnowledge,
  updateKnowledgeBase,
  updateKnowledgeBaseStatus,
  uploadDocument,
} from './index';

const requestClient = vi.hoisted(() => ({
  delete: vi.fn(() => Promise.resolve(true)),
  get: vi.fn(() => Promise.resolve({})),
  post: vi.fn(() => Promise.resolve({})),
  put: vi.fn(() => Promise.resolve(true)),
}));

vi.mock('#/api/request', () => ({ requestClient }));

describe('ai knowledge api', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('知识库 CRUD 走管理端路径并带版本号', async () => {
    await getKnowledgeBasePage({ pageNo: 1, pageSize: 10 });
    expect(requestClient.get).toHaveBeenCalledWith('/ai/knowledge-base/page', {
      params: { pageNo: 1, pageSize: 10 },
    });

    await getKnowledgeBase(61);
    expect(requestClient.get).toHaveBeenCalledWith(
      '/ai/knowledge-base/get?id=61',
    );

    await createKnowledgeBase({
      code: 'handbook',
      embeddingDimension: 1536,
      embeddingModel: 'text-embedding-3-small',
      name: '员工手册',
      visibility: 'SHARED',
    });
    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/knowledge-base/create',
      expect.objectContaining({ code: 'handbook' }),
    );

    await updateKnowledgeBase({
      code: 'handbook',
      embeddingDimension: 1536,
      embeddingModel: 'text-embedding-3-small',
      name: '员工手册',
      version: 2,
      visibility: 'SHARED',
    });
    expect(requestClient.put).toHaveBeenCalledWith(
      '/ai/knowledge-base/update',
      expect.objectContaining({ version: 2 }),
    );

    await updateKnowledgeBaseStatus(61, 3, false);
    expect(requestClient.put).toHaveBeenCalledWith(
      '/ai/knowledge-base/update-status',
      undefined,
      {
        params: { enabled: false, id: 61, version: 3 },
      },
    );

    await deleteKnowledgeBase(61, 4);
    expect(requestClient.delete).toHaveBeenCalledWith(
      '/ai/knowledge-base/delete',
      {
        params: { id: 61, version: 4 },
      },
    );
  });

  it('文档上传用 multipart 组装文件 + 幂等键 + 标题', async () => {
    const file = new File(['内容'], 'handbook.txt', { type: 'text/plain' });
    await uploadDocument({
      file,
      knowledgeBaseId: 61,
      sourceKey: 'handbook/v1.txt',
      sourceRef: 'drive:handbook/v1.txt',
      title: '员工手册',
    });

    const [url, body, config] = requestClient.post.mock.calls.at(
      -1,
    ) as unknown as [string, FormData, { headers: Record<string, string> }];
    expect(url).toBe('/ai/knowledge-document/upload');
    expect(body).toBeInstanceOf(FormData);
    expect(body.get('knowledgeBaseId')).toBe('61');
    expect(body.get('sourceKey')).toBe('handbook/v1.txt');
    expect(body.get('title')).toBe('员工手册');
    expect(body.get('sourceRef')).toBe('drive:handbook/v1.txt');
    expect(body.get('file')).toBeInstanceOf(File);
    expect(config.headers['Content-Type']).toBe('multipart/form-data');
  });

  it('文档/版本/任务查询与重试走管理端路径', async () => {
    await getDocumentPage({ knowledgeBaseId: 61, pageNo: 1, pageSize: 10 });
    expect(requestClient.get).toHaveBeenCalledWith(
      '/ai/knowledge-document/page',
      {
        params: { knowledgeBaseId: 61, pageNo: 1, pageSize: 10 },
      },
    );

    await getDocumentVersionPage({ documentId: 71, pageNo: 1, pageSize: 20 });
    expect(requestClient.get).toHaveBeenCalledWith(
      '/ai/knowledge-document/version/page',
      {
        params: { documentId: 71, pageNo: 1, pageSize: 20 },
      },
    );

    await getIngestionTaskPage({
      knowledgeBaseId: 61,
      pageNo: 1,
      pageSize: 20,
    });
    expect(requestClient.get).toHaveBeenCalledWith(
      '/ai/knowledge-document/task/page',
      {
        params: { knowledgeBaseId: 61, pageNo: 1, pageSize: 20 },
      },
    );

    await retryIngestionTask(91, 2);
    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/knowledge-document/task/retry',
      undefined,
      {
        params: { id: 91, version: 2 },
      },
    );

    await deleteDocument(71, 4);
    expect(requestClient.delete).toHaveBeenCalledWith(
      '/ai/knowledge-document/delete',
      {
        params: { id: 71, version: 4 },
      },
    );
  });

  it('检索与引用读取走应用端路径（引用标识做 URL 编码）', async () => {
    await searchKnowledge({ query: '华东净额', topK: 5 });
    expect(requestClient.post).toHaveBeenCalledWith('/ai/knowledge/search', {
      query: '华东净额',
      topK: 5,
    });

    await readCitation('61:81:0');
    expect(requestClient.get).toHaveBeenCalledWith(
      '/ai/knowledge/citation?citationId=61%3A81%3A0',
    );

    await readDocumentContent(71);
    expect(requestClient.get).toHaveBeenCalledWith(
      '/ai/knowledge/document/content?documentId=71',
      {
        responseType: 'blob',
      },
    );
  });
});
