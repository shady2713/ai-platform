import type { UploadFile, UploadRequestOptions } from 'element-plus';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useUpload } from './use-upload';
import {
  requestUpload,
  resolveUploadUrl,
  resolveUploadValue,
  unwrapUploadResponse,
} from './use-upload-core';

vi.mock('./use-upload', () => ({
  useUpload: vi.fn(),
}));

function uploadFile(response: unknown, url?: string): UploadFile {
  return {
    name: 'document.txt',
    response,
    status: 'success',
    uid: 1,
    url,
  };
}

function requestOptions(onProgress = vi.fn()): UploadRequestOptions {
  const file = Object.assign(new File(['content'], 'document.txt'), { uid: 1 });
  return {
    action: '',
    data: {},
    file,
    filename: 'file',
    headers: {},
    method: 'post',
    onError: vi.fn(),
    onProgress,
    onSuccess: vi.fn(),
    withCredentials: false,
  };
}

describe('upload core', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('unwraps only an own data property', () => {
    expect(unwrapUploadResponse({ data: 'own-value' })).toBe('own-value');

    const inherited = Object.create({ data: 'inherited-value' }) as object;
    expect(unwrapUploadResponse(inherited)).toBe(inherited);
    expect(unwrapUploadResponse('direct-value')).toBe('direct-value');
  });

  it('resolves direct and wrapped upload urls', () => {
    expect(resolveUploadUrl('https://cdn.example/direct.txt')).toBe(
      'https://cdn.example/direct.txt',
    );
    expect(
      resolveUploadUrl({ data: { url: 'https://cdn.example/file.txt' } }),
    ).toBe('https://cdn.example/file.txt');
    expect(resolveUploadUrl({ data: 'https://cdn.example/data.txt' })).toBe(
      'https://cdn.example/data.txt',
    );
    expect(resolveUploadUrl('javascript:alert(1)')).toBe('');
    expect(resolveUploadUrl({ url: 'file:///etc/passwd' })).toBe('');
    expect(resolveUploadUrl({ id: 1 })).toBe('');
  });

  it('resolves configured nested result fields and fails loud when absent', () => {
    const file = uploadFile({ data: { file: { id: 42 } } });

    expect(resolveUploadValue(file, 'file.id')).toBe(42);
    expect(() => resolveUploadValue(file, 'file.missing')).toThrow(
      '上传响应缺少结果字段: file.missing',
    );
  });

  it('prefers the file url and otherwise falls back to the response value', () => {
    expect(
      resolveUploadValue(
        uploadFile(
          'https://cdn.example/response.txt',
          'https://cdn.example/file.txt',
        ),
      ),
    ).toBe('https://cdn.example/file.txt');
    expect(resolveUploadValue(uploadFile({ id: 7 }))).toEqual({ id: 7 });
  });

  it('uses a supplied api and converts progress into element-plus shape', async () => {
    const api = vi.fn().mockImplementation(async (_file, onProgress) => {
      onProgress?.({ loaded: 25, total: 100 });
      return { url: 'https://cdn.example/file.txt' };
    });
    const onProgress = vi.fn();

    await expect(
      requestUpload({ api, directory: 'docs' }, requestOptions(onProgress)),
    ).resolves.toEqual({ url: 'https://cdn.example/file.txt' });
    expect(onProgress).toHaveBeenCalledWith({
      lengthComputable: true,
      loaded: 25,
      percent: 25,
      total: 100,
    });
    expect(useUpload).not.toHaveBeenCalled();
  });

  it('uses the default uploader and rejects an undefined result', async () => {
    const httpRequest = vi.fn().mockResolvedValue('uploaded');
    vi.mocked(useUpload).mockReturnValue({
      httpRequest,
      uploadUrl: '/infra/file/upload',
    });

    await expect(
      requestUpload({ directory: 'docs' }, requestOptions()),
    ).resolves.toBe('uploaded');
    expect(useUpload).toHaveBeenCalledWith('docs', 'inline', false);

    httpRequest.mockResolvedValueOnce(undefined);
    await expect(
      requestUpload({ directory: 'docs' }, requestOptions()),
    ).rejects.toThrow('上传接口未返回结果');
  });
});
