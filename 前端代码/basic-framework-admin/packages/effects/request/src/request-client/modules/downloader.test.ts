import type { AxiosRequestConfig } from 'axios';

import type { RequestClient } from '../request-client';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { FileDownloader } from './downloader';

describe('fileDownloader', () => {
  let fileDownloader: FileDownloader;
  const request = vi.fn();
  const mockClient = {
    request: request as unknown as RequestClient['request'],
  };

  beforeEach(() => {
    request.mockReset();
    fileDownloader = new FileDownloader(mockClient);
  });

  it('should create an instance of FileDownloader', () => {
    expect(fileDownloader).toBeInstanceOf(FileDownloader);
  });

  it('should download a file and return a Blob', async () => {
    const url = 'https://example.com/file';
    const mockBlob = new Blob(['file content'], { type: 'text/plain' });
    const mockResponse: Blob = mockBlob;

    request.mockResolvedValueOnce(mockResponse);

    const result = await fileDownloader.download(url);

    expect(result).toBeInstanceOf(Blob);
    expect(result).toEqual(mockBlob);
    expect(request).toHaveBeenCalledWith(url, {
      method: 'GET',
      responseType: 'blob',
      responseReturn: 'body',
    });
  });

  it('should merge provided config with default config', async () => {
    const url = 'https://example.com/file';
    const mockBlob = new Blob(['file content'], { type: 'text/plain' });
    const mockResponse: Blob = mockBlob;

    request.mockResolvedValueOnce(mockResponse);

    const customConfig: AxiosRequestConfig = {
      headers: { 'Custom-Header': 'value' },
    };

    const result = await fileDownloader.download(url, customConfig);
    expect(result).toBeInstanceOf(Blob);
    expect(result).toEqual(mockBlob);
    expect(request).toHaveBeenCalledWith(url, {
      ...customConfig,
      method: 'GET',
      responseType: 'blob',
      responseReturn: 'body',
    });
  });

  it('should handle errors gracefully', async () => {
    const url = 'https://example.com/file';
    request.mockRejectedValueOnce(new Error('Network Error'));
    await expect(fileDownloader.download(url)).rejects.toThrow('Network Error');
  });

  it('should handle empty URL gracefully', async () => {
    const url = '';
    request.mockRejectedValueOnce(
      new Error('Request failed with status code 404'),
    );

    await expect(fileDownloader.download(url)).rejects.toThrow(
      'Request failed with status code 404',
    );
  });

  it('should handle null URL gracefully', async () => {
    const url = null as unknown as string;
    request.mockRejectedValueOnce(
      new Error('Request failed with status code 404'),
    );

    await expect(fileDownloader.download(url)).rejects.toThrow(
      'Request failed with status code 404',
    );
  });
  it('should preserve an explicit request method and body', async () => {
    const url = 'https://example.com/file';
    const customConfig: AxiosRequestConfig = {
      method: 'POST',
      data: { name: 'aa' },
    };

    await fileDownloader.download(url, customConfig);

    expect(request).toHaveBeenCalledWith(url, {
      data: { name: 'aa' },
      method: 'POST',
      responseType: 'blob',
      responseReturn: 'body',
    });
  });
});
