import type { AxiosRequestConfig } from 'axios';

import type { RequestClient } from '../request-client';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { FileUploader } from './uploader';

describe('fileUploader', () => {
  let fileUploader: FileUploader;
  const post = vi.fn();
  const mockClient = {
    post: post as unknown as RequestClient['post'],
  };

  beforeEach(() => {
    post.mockReset();
    fileUploader = new FileUploader(mockClient);
  });

  it('should create an instance of FileUploader', () => {
    expect(fileUploader).toBeInstanceOf(FileUploader);
  });

  it('should upload a file and return the response', async () => {
    const url = 'https://example.com/upload';
    const file = new File(['file content'], 'test.txt', { type: 'text/plain' });
    const mockResponse = {
      config: {},
      data: { success: true },
      headers: {},
      status: 200,
      statusText: 'OK',
    };

    post.mockResolvedValueOnce(mockResponse);

    const result = await fileUploader.upload(url, { file });
    expect(result).toEqual(mockResponse);
    expect(post).toHaveBeenCalledWith(url, expect.any(FormData), {
      headers: {
        'Content-Type': 'multipart/form-data',
      },
    });
  });

  it('should merge provided config with default config', async () => {
    const url = 'https://example.com/upload';
    const file = new File(['file content'], 'test.txt', { type: 'text/plain' });
    const mockResponse = {
      config: {},
      data: { success: true },
      headers: {},
      status: 200,
      statusText: 'OK',
    };

    post.mockResolvedValueOnce(mockResponse);

    const customConfig: AxiosRequestConfig = {
      headers: { 'Custom-Header': 'value' },
    };

    const result = await fileUploader.upload(url, { file }, customConfig);
    expect(result).toEqual(mockResponse);
    expect(post).toHaveBeenCalledWith(url, expect.any(FormData), {
      headers: {
        'Content-Type': 'multipart/form-data',
        'Custom-Header': 'value',
      },
    });
  });

  it('serializes supported scalar, array, null and optional fields', async () => {
    const file = new File(['file content'], 'test.txt', { type: 'text/plain' });
    const attachment = new Blob(['attachment']);
    post.mockResolvedValueOnce({ success: true });

    await fileUploader.upload('/upload', {
      active: true,
      attachment,
      count: 2,
      empty: null,
      file,
      ignored: undefined,
      labels: ['one', 2, false, null, undefined],
      name: 'report',
    });

    const uploadCall = post.mock.calls.at(0);
    if (!uploadCall) {
      throw new Error('Upload request was not sent');
    }
    const formData = uploadCall[1] as FormData;
    expect(formData.get('file')).toBe(file);
    const storedAttachment = formData.get('attachment');
    expect(storedAttachment).toBeInstanceOf(Blob);
    expect(await (storedAttachment as Blob).text()).toBe('attachment');
    expect(formData.get('active')).toBe('true');
    expect(formData.get('count')).toBe('2');
    expect(formData.get('empty')).toBe('null');
    expect(formData.get('name')).toBe('report');
    expect(formData.has('ignored')).toBe(false);
    expect(
      [...formData.entries()].filter(([key]) => key === 'labels[0]'),
    ).toEqual([['labels[0]', 'one']]);
    expect(formData.get('labels[1]')).toBe('2');
    expect(formData.get('labels[2]')).toBe('false');
    expect(formData.get('labels[3]')).toBe('null');
    expect(formData.has('labels[4]')).toBe(false);
  });

  it('rejects unsupported object field values', async () => {
    const file = new File(['file content'], 'test.txt', { type: 'text/plain' });

    await expect(
      fileUploader.upload('/upload', { file, metadata: { owner: 'admin' } }),
    ).rejects.toThrow('Unsupported upload field value for "metadata"');
    expect(post).not.toHaveBeenCalled();
  });

  it('should handle errors gracefully', async () => {
    const url = 'https://example.com/upload';
    const file = new File(['file content'], 'test.txt', { type: 'text/plain' });
    post.mockRejectedValueOnce(new Error('Network Error'));

    await expect(fileUploader.upload(url, { file })).rejects.toThrow(
      'Network Error',
    );
  });

  it('should handle empty URL gracefully', async () => {
    const url = '';
    const file = new File(['file content'], 'test.txt', { type: 'text/plain' });
    post.mockRejectedValueOnce(
      new Error('Request failed with status code 404'),
    );

    await expect(fileUploader.upload(url, { file })).rejects.toThrow(
      'Request failed with status code 404',
    );
  });

  it('should handle null URL gracefully', async () => {
    const url = null as unknown as string;
    const file = new File(['file content'], 'test.txt', { type: 'text/plain' });
    post.mockRejectedValueOnce(
      new Error('Request failed with status code 404'),
    );

    await expect(fileUploader.upload(url, { file })).rejects.toThrow(
      'Request failed with status code 404',
    );
  });
});
