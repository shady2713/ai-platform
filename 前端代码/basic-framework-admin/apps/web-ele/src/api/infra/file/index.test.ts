import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createFile,
  deleteFile,
  deleteFileList,
  fetchFileContent,
  getFilePage,
  getFilePresignedUrl,
  uploadFile,
} from './index';

const requestClient = vi.hoisted(() => ({
  delete: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
  upload: vi.fn(),
}));

vi.mock('#/api/request', () => ({ requestClient }));

describe('infra file api', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('maps query and delete operations to their backend contracts', () => {
    const page = { pageNo: 2, pageSize: 20 };

    getFilePage(page);
    deleteFile(7);
    deleteFileList([7, 9]);
    getFilePresignedUrl('report.pdf', 128, 'application/pdf', 'reports');

    expect(requestClient.get).toHaveBeenNthCalledWith(1, '/infra/file/page', {
      params: page,
    });
    expect(requestClient.delete).toHaveBeenNthCalledWith(
      1,
      '/infra/file/delete?id=7',
    );
    expect(requestClient.delete).toHaveBeenNthCalledWith(
      2,
      '/infra/file/delete-list?ids=7,9',
    );
    expect(requestClient.get).toHaveBeenNthCalledWith(
      2,
      '/infra/file/presigned-url',
      {
        params: {
          directory: 'reports',
          name: 'report.pdf',
          size: 128,
          type: 'application/pdf',
        },
      },
    );
  });

  it('posts only the one-time upload token', () => {
    const file = { uploadToken: 'upload-token' };

    createFile(file);

    expect(requestClient.post).toHaveBeenCalledWith('/infra/file/create', file);
  });

  it('fetchFileContent 走 raw + blob 契约并放宽大文件超时', async () => {
    const blob = new Blob(['content'], { type: 'application/pdf' });
    requestClient.get.mockResolvedValue({ data: blob });

    const result = await fetchFileContent('/admin-api/infra/file/4/get/a.pdf');

    expect(requestClient.get).toHaveBeenCalledWith(
      '/admin-api/infra/file/4/get/a.pdf',
      {
        responseReturn: 'raw',
        responseType: 'blob',
        timeout: 60_000,
      },
    );
    expect(result).toBe(blob);
  });

  it('omits an empty directory without mutating caller input', () => {
    const file = new File(['content'], 'report.pdf');
    const input = { directory: undefined, file };
    const progress = vi.fn();

    uploadFile(input, progress, 'inline');

    expect(input).toEqual({ directory: undefined, file });
    expect(requestClient.upload).toHaveBeenCalledWith(
      '/infra/file/upload',
      { file },
      { errorMode: 'inline', onUploadProgress: progress },
    );
  });

  it('preserves a configured upload directory', () => {
    const file = new File(['content'], 'report.pdf');
    const input = { directory: 'reports', file };

    uploadFile(input);

    expect(requestClient.upload).toHaveBeenCalledWith(
      '/infra/file/upload',
      input,
      { errorMode: 'global', onUploadProgress: undefined },
    );
  });

  it('only sends public-read intent when callers explicitly request it', () => {
    const file = new File(['content'], 'avatar.png', { type: 'image/png' });

    getFilePresignedUrl('avatar.png', 128, 'image/png', undefined, true);
    uploadFile({ file, publicRead: true });

    expect(requestClient.get).toHaveBeenCalledWith(
      '/infra/file/presigned-url',
      {
        params: {
          directory: undefined,
          name: 'avatar.png',
          publicRead: true,
          size: 128,
          type: 'image/png',
        },
      },
    );
    expect(requestClient.upload).toHaveBeenCalledWith(
      '/infra/file/upload',
      { file, publicRead: true },
      { errorMode: 'global', onUploadProgress: undefined },
    );
  });
});
