import type { CitationBlock } from '../../message/blocks';

import { describe, expect, it, vi } from 'vitest';

import {
  canOpenOriginal,
  citationFailureMessage,
  citationLabel,
  citationSnippetText,
  openCitationOriginal,
  openCitationSnippet,
} from '../citation';

const CITATION: CitationBlock = {
  chunkIndex: 2,
  citationId: 'kb_1:12:3',
  documentId: 9,
  kind: 'citation',
  locationRef: '第 3 页',
  snippet: '  片段正文  ',
  title: '销售手册',
  versionNo: 2,
};

function apiStub(
  overrides: Partial<{
    readOriginal: () => Promise<{ content: string; mimeType: string }>;
    readSnippet: () => Promise<string>;
  }> = {},
) {
  return {
    readOriginal: vi.fn(
      overrides.readOriginal ??
        (async () => ({ content: '原文正文', mimeType: 'text/plain' })),
    ),
    readSnippet: vi.fn(overrides.readSnippet ?? (async () => '片段正文')),
  };
}

describe('引用展示与受控打开', () => {
  it('标签带版本与位置，片段只取服务端内容', () => {
    expect(citationLabel(CITATION)).toBe('销售手册（v2 · 第 3 页）');
    expect(citationLabel({ ...CITATION, locationRef: '  ' })).toBe(
      '销售手册（v2）',
    );
    expect(citationSnippetText(CITATION)).toBe('片段正文');
    expect(citationSnippetText({ ...CITATION, snippet: undefined })).toBe('');
  });

  it('只有服务端给过文档编号时才提供"打开原文"入口', () => {
    expect(canOpenOriginal(CITATION)).toBe(true);
    expect(canOpenOriginal({ ...CITATION, documentId: undefined })).toBe(false);
    expect(canOpenOriginal({ ...CITATION, documentId: 0 })).toBe(false);
  });

  it('受控打开原文：成功返回本次响应内容', async () => {
    const api = apiStub({
      readOriginal: async () => ({
        content: '原文',
        mimeType: 'text/markdown',
      }),
    });
    const outcome = await openCitationOriginal(api, 9);
    expect(outcome).toStrictEqual({
      content: '原文',
      mimeType: 'text/markdown',
      ok: true,
    });
    expect(api.readOriginal).toHaveBeenCalledWith(9);
  });

  it('失权/撤回时不给内容，且不回显宿主异常文本', async () => {
    const api = apiStub({
      readOriginal: async () => {
        throw new Error(
          'GET https://api.example.com/ai/knowledge/document/content?documentId=9&token=secret-value failed',
        );
      },
    });
    const outcome = await openCitationOriginal(api, 9);
    expect(outcome.ok).toBe(false);
    expect(outcome.ok === false && outcome.message).toBe(
      '原文不可打开：无权限、已撤回或不存在',
    );
    expect(JSON.stringify(outcome)).not.toContain('secret-value');
  });

  it('片段读取失败同样收敛为固定提示', async () => {
    const outcome = await openCitationSnippet(
      apiStub({
        readSnippet: async () => {
          throw new Error('403');
        },
      }),
      CITATION.citationId,
    );
    expect(outcome).toStrictEqual({
      message: '引用片段不可读取：无权限、已撤回或不存在',
      ok: false,
    });
  });

  it('片段读取成功返回纯文本内容', async () => {
    const outcome = await openCitationSnippet(
      apiStub({ readSnippet: async () => '重读片段' }),
      CITATION.citationId,
    );
    expect(outcome).toStrictEqual({
      content: '重读片段',
      mimeType: 'text/plain',
      ok: true,
    });
  });

  it('空响应按空内容处理，不抛异常', async () => {
    const api = {
      readOriginal: vi.fn(async () => undefined as never),
      readSnippet: vi.fn(async () => undefined as never),
    };
    expect(await openCitationOriginal(api, 1)).toStrictEqual({
      content: '',
      mimeType: 'text/plain',
      ok: true,
    });
    expect(await openCitationSnippet(api, 'kb_1:1:1')).toStrictEqual({
      content: '',
      mimeType: 'text/plain',
      ok: true,
    });
  });

  it('提示文案按动作区分且为固定文本', () => {
    expect(citationFailureMessage('original')).toBe(
      '原文不可打开：无权限、已撤回或不存在',
    );
    expect(citationFailureMessage('snippet')).toBe(
      '引用片段不可读取：无权限、已撤回或不存在',
    );
  });
});
