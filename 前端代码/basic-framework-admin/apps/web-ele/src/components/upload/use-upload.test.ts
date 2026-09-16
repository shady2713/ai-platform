import { ref } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  createFile: vi.fn(),
  getFilePresignedUrl: vi.fn(),
  put: vi.fn(),
  uploadFile: vi.fn(),
}));

vi.mock('@vben/hooks', () => ({
  useAppConfig: () => ({ apiURL: '/api' }),
}));
vi.mock('@vben/locales', () => ({
  $t: (key: string, values: unknown[]) => `${key}:${values.join(',')}`,
}));
vi.mock('#/api/infra/file', () => ({
  createFile: mocks.createFile,
  getFilePresignedUrl: mocks.getFilePresignedUrl,
  uploadFile: mocks.uploadFile,
}));
vi.mock('#/api/request', () => ({
  baseRequestClient: { put: mocks.put },
}));

async function loadUpload(uploadType: 'client' | 'server' = 'server') {
  vi.stubEnv('VITE_UPLOAD_TYPE', uploadType);
  vi.resetModules();
  return import('./use-upload');
}

describe('useUploadType', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.unstubAllEnvs();
  });

  it('normalizes extensions and builds default help text', async () => {
    const { useUploadType } = await loadUpload();
    const type = useUploadType({
      acceptRef: ref(['jpg', '.png', 'image/webp']),
      helpTextRef: ref(''),
      maxNumberRef: ref(3),
      maxSizeRef: ref(5),
    });

    expect(type.getAccept.value).toEqual(['jpg', '.png', 'image/webp']);
    expect(type.getStringAccept.value).toBe('.jpg,.png,image/webp');
    expect(type.getHelpText.value).toBe(
      'ui.upload.accept:jpg,.png,image/webp，ui.upload.maxSize:5，ui.upload.maxNumber:3',
    );
  });

  it('prefers custom help text and omits unlimited count', async () => {
    const { useUploadType } = await loadUpload();
    const custom = useUploadType({
      acceptRef: ref([]),
      helpTextRef: ref('仅限内部材料'),
      maxNumberRef: ref(Infinity),
      maxSizeRef: ref(0),
    });

    expect(custom.getAccept.value).toEqual([]);
    expect(custom.getStringAccept.value).toBe('');
    expect(custom.getHelpText.value).toBe('仅限内部材料');
  });
});

describe('useUpload', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.unstubAllEnvs();
  });

  it('delegates server uploads with progress and error mode', async () => {
    const progress = vi.fn();
    const file = new File(['server'], 'server.txt', { type: 'text/plain' });
    const response = { url: '/server.txt' };
    mocks.uploadFile.mockResolvedValue(response);
    const { useUpload } = await loadUpload();

    const upload = useUpload('documents', 'inline');

    expect(upload.uploadUrl).toBe('/api/infra/file/upload');
    await expect(upload.httpRequest(file, progress)).resolves.toBe(response);
    expect(mocks.uploadFile).toHaveBeenCalledWith(
      { directory: 'documents', file, publicRead: false },
      progress,
      'inline',
    );
  });

  it('propagates an explicit public-read choice to the upload contract', async () => {
    const file = new File(['avatar'], 'avatar.png', { type: 'image/png' });
    mocks.uploadFile.mockResolvedValue({ url: '/avatar.png' });
    const { useUpload } = await loadUpload();

    await useUpload('avatars', 'inline', true).httpRequest(file);

    expect(mocks.uploadFile).toHaveBeenCalledWith(
      { directory: 'avatars', file, publicRead: true },
      undefined,
      'inline',
    );
  });

  it('reports client upload success only after metadata is persisted', async () => {
    const file = new File(['client'], 'client.txt', { type: 'text/plain' });
    const presigned = {
      uploadToken: 'upload-token',
      uploadUrl: 'https://storage.example/upload',
    };
    const validatedUrl = 'https://cdn.example/client.txt';
    let finishMetadata: ((url: string) => void) | undefined;
    mocks.getFilePresignedUrl.mockResolvedValue(presigned);
    mocks.put.mockResolvedValue(undefined);
    mocks.createFile.mockImplementation(
      () =>
        new Promise<string>((resolve) => {
          finishMetadata = resolve;
        }),
    );
    const { useUpload } = await loadUpload('client');

    const resultPromise = useUpload('documents').httpRequest(file);
    await vi.waitFor(() => expect(mocks.createFile).toHaveBeenCalledOnce());
    let settled = false;
    void resultPromise.then(() => {
      settled = true;
    });
    await Promise.resolve();

    expect(settled).toBe(false);
    expect(mocks.getFilePresignedUrl).toHaveBeenCalledWith(
      'client.txt',
      file.size,
      file.type,
      'documents',
      false,
    );
    expect(mocks.put).toHaveBeenCalledWith(presigned.uploadUrl, file, {
      headers: { 'Content-Type': 'text/plain' },
    });
    expect(mocks.createFile).toHaveBeenCalledWith({
      uploadToken: 'upload-token',
    });

    finishMetadata?.(validatedUrl);
    await expect(resultPromise).resolves.toEqual({ url: validatedUrl });
  });

  it('propagates metadata persistence failure instead of false success', async () => {
    const file = new File(['client'], 'client.txt');
    mocks.getFilePresignedUrl.mockResolvedValue({
      uploadToken: 'upload-token',
      uploadUrl: 'https://storage.example/upload',
    });
    mocks.put.mockResolvedValue(undefined);
    mocks.createFile.mockRejectedValue(new Error('metadata unavailable'));
    const { useUpload } = await loadUpload('client');

    await expect(useUpload().httpRequest(file)).rejects.toThrow(
      'metadata unavailable',
    );
    expect(mocks.getFilePresignedUrl).toHaveBeenCalledWith(
      'client.txt',
      file.size,
      undefined,
      undefined,
      false,
    );
  });
});
