import type { FileBlock } from '../../message/blocks';

import { describe, expect, it, vi } from 'vitest';

import {
  attachmentFailureMessage,
  attachmentSummary,
  downloadAttachment,
  formatSize,
  isPreviewable,
  readAttachment,
  sanitizeFileName,
} from '../attachment';

const FILE: FileBlock = {
  businessKey: 'ai_chat_session:1',
  businessType: 'ai_chat_session',
  fileId: 7,
  kind: 'file',
  mime: 'text/markdown',
  name: '说明.md',
  size: 2048,
};

function apiStub(
  overrides: Partial<{
    download: () => Promise<void>;
    read: () => Promise<{ content: string; mimeType: string }>;
  }> = {},
) {
  return {
    download: vi.fn(overrides.download ?? (async () => undefined)),
    read: vi.fn(
      overrides.read ??
        (async () => ({ content: '文件内容', mimeType: 'text/markdown' })),
    ),
  };
}

describe('附件展示与受控读取', () => {
  it('大小按二进制单位显示，非法输入不伪装成 0', () => {
    expect(formatSize(0)).toBe('0 B');
    expect(formatSize(1023)).toBe('1023 B');
    expect(formatSize(1024)).toBe('1 KB');
    expect(formatSize(1536)).toBe('1.5 KB');
    expect(formatSize(5 * 1024 * 1024)).toBe('5 MB');
    expect(formatSize(Number.NaN)).toBe('未知大小');
    expect(formatSize(-1)).toBe('未知大小');
  });

  it('文件名剥离目录成分与控制字符（只用于显示与建议下载名）', () => {
    expect(sanitizeFileName('../../etc/passwd')).toBe('passwd');
    expect(sanitizeFileName(String.raw`C:\temp\报表.xlsx`)).toBe('报表.xlsx');
    expect(sanitizeFileName('a\u0000b.txt')).toBe('ab.txt');
    expect(sanitizeFileName('...hidden')).toBe('hidden');
    expect(sanitizeFileName('   ')).toBe('attachment');
    expect(sanitizeFileName('')).toBe('attachment');
  });

  it('只有文本类媒体类型才就地预览', () => {
    expect(isPreviewable('text/plain')).toBe(true);
    expect(isPreviewable('application/json')).toBe(true);
    expect(isPreviewable('application/octet-stream')).toBe(false);
    expect(isPreviewable(undefined)).toBe(false);
  });

  it('摘要包含名称、大小与媒体类型', () => {
    expect(attachmentSummary(FILE)).toBe('说明.md · 2 KB · text/markdown');
    expect(attachmentSummary({ ...FILE, mime: undefined })).toBe(
      '说明.md · 2 KB',
    );
  });

  it('受控预览成功返回内容，失败给固定提示且不泄露异常文本', async () => {
    const ok = await readAttachment(apiStub(), FILE.fileId);
    expect(ok).toStrictEqual({
      content: '文件内容',
      mimeType: 'text/markdown',
      ok: true,
    });

    const failed = await readAttachment(
      apiStub({
        read: async () => {
          throw new Error('GET /ai/file/7 403 token=secret-value');
        },
      }),
      FILE.fileId,
    );
    expect(failed).toStrictEqual({
      message: '文件不可预览：无权限、已撤回或不存在',
      ok: false,
    });
    expect(JSON.stringify(failed)).not.toContain('secret-value');
  });

  it('受控下载用清洗后的文件名，失败给固定提示', async () => {
    const api = apiStub();
    const ok = await downloadAttachment(api, {
      ...FILE,
      name: '../说明.md',
    });
    expect(ok.ok).toBe(true);
    expect(api.download).toHaveBeenCalledWith(7, '说明.md');

    const failed = await downloadAttachment(
      apiStub({
        download: async () => {
          throw new Error('boom');
        },
      }),
      FILE,
    );
    expect(failed).toStrictEqual({
      message: '文件不可下载：无权限、已撤回或不存在',
      ok: false,
    });
  });

  it('空响应按空内容处理，不抛异常', async () => {
    const api = {
      download: vi.fn(async () => undefined),
      read: vi.fn(async () => undefined as never),
    };
    expect(await readAttachment(api, 7)).toStrictEqual({
      content: '',
      mimeType: 'text/plain',
      ok: true,
    });
  });

  it('提示文案按动作区分且为固定文本', () => {
    expect(attachmentFailureMessage('read')).toBe(
      '文件不可预览：无权限、已撤回或不存在',
    );
    expect(attachmentFailureMessage('download')).toBe(
      '文件不可下载：无权限、已撤回或不存在',
    );
  });
});
