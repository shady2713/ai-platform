import type { UploadRawFile, UploadRequestOptions } from 'element-plus';

import type { FileUploadProps } from './typing';

import { nextTick, reactive } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useUploadListState } from './use-upload-list-state';

const mocks = vi.hoisted(() => ({
  logError: vi.fn(),
  showError: vi.fn(),
  showErrorMessage: vi.fn(),
  showSuccessMessage: vi.fn(),
}));

vi.mock('@vben/locales', async () => {
  const actual =
    await vi.importActual<typeof import('@vben/locales')>('@vben/locales');
  return { ...actual, $t: (key: string) => key };
});
vi.mock('@vben/utils', async () => {
  const actual =
    await vi.importActual<typeof import('@vben/utils')>('@vben/utils');
  return { ...actual, logError: mocks.logError };
});
vi.mock('#/utils/feedback', () => ({
  showError: mocks.showError,
  showErrorMessage: mocks.showErrorMessage,
  showSuccessMessage: mocks.showSuccessMessage,
}));
vi.mock('./use-upload', () => ({ useUpload: vi.fn() }));

function rawFile(
  name: string,
  uid: number,
  type = 'text/plain',
): UploadRawFile {
  return Object.assign(new File(['content'], name, { type }), { uid });
}

function requestOptions(file: UploadRawFile): UploadRequestOptions {
  return {
    action: '',
    data: {},
    file,
    filename: 'file',
    headers: {},
    method: 'post',
    onError: vi.fn(),
    onProgress: vi.fn(),
    onSuccess: vi.fn(),
    withCredentials: false,
  };
}

function createState(overrides: Partial<FileUploadProps> = {}) {
  const props = reactive<FileUploadProps>({
    accept: ['txt'],
    maxNumber: 2,
    maxSize: 1,
    value: [],
    ...overrides,
  });
  const onDelete = vi.fn();
  const onModelChange = vi.fn();
  const state = useUploadListState({
    imageOnly: false,
    logScope: 'upload:file',
    onDelete,
    onModelChange,
    props,
  });
  return { onDelete, onModelChange, props, state };
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('upload list state', () => {
  it('synchronizes comma-separated external values without losing later updates', async () => {
    const { props, state } = createState({ value: '/a.txt,/b.txt' });
    expect(state.fileList.value).toHaveLength(2);

    props.value = '/c.txt';
    await nextTick();
    expect(state.fileList.value).toMatchObject([{ url: '/c.txt' }]);
  });

  it('rejects excess, unsupported, disguised image, and oversized files', () => {
    const existing = createState({ value: ['/a.txt', '/b.txt'] });
    expect(existing.state.beforeUpload(rawFile('c.txt', 3))).toBe(false);

    const unsupported = createState();
    expect(unsupported.state.beforeUpload(rawFile('c.pdf', 4))).toBe(false);

    const imageState = useUploadListState({
      imageOnly: true,
      logScope: 'upload:image',
      onDelete: vi.fn(),
      onModelChange: vi.fn(),
      props: reactive<FileUploadProps>({
        accept: ['png'],
        maxNumber: 1,
        maxSize: 1,
      }),
    });
    expect(
      imageState.beforeUpload(rawFile('avatar.png', 5, 'application/pdf')),
    ).toBe(false);

    const oversized = createState({ maxSize: 0.000_001 });
    expect(oversized.state.beforeUpload(rawFile('large.txt', 6))).toBe(false);
    expect(mocks.showErrorMessage).toHaveBeenCalledTimes(4);
  });

  it('commits each successful upload even when a concurrent upload fails', async () => {
    const api = vi.fn(async (file: File) => {
      if (file.name === 'bad.txt') throw new Error('network failed');
      return { url: `/${file.name}` };
    });
    const { onModelChange, state } = createState({ api });
    const good = rawFile('good.txt', 10);
    const bad = rawFile('bad.txt', 11);
    const goodRequest = requestOptions(good);
    const badRequest = requestOptions(bad);

    expect(state.beforeUpload(good)).toBe(true);
    expect(state.beforeUpload(bad)).toBe(true);
    await Promise.all([
      state.customRequest(goodRequest),
      state.customRequest(badRequest),
    ]);

    expect(onModelChange).toHaveBeenCalledWith(['/good.txt']);
    expect(goodRequest.onSuccess).toHaveBeenCalledOnce();
    expect(badRequest.onError).toHaveBeenCalledOnce();
    expect(state.fileList.value).toMatchObject([
      { status: 'success', url: '/good.txt' },
    ]);
    expect(mocks.showSuccessMessage).toHaveBeenCalledOnce();
    expect(mocks.showError).toHaveBeenCalledOnce();
    expect(mocks.logError).toHaveBeenCalledOnce();

    expect(state.beforeUpload(rawFile('next.txt', 12))).toBe(true);
  });

  it('removes files, emits the new model, and reports element-plus excess', () => {
    const { onDelete, onModelChange, state } = createState({
      value: ['/a.txt'],
    });
    const file = state.fileList.value[0];
    if (!file) throw new Error('expected normalized file');

    state.handleRemove(file);
    expect(onModelChange).toHaveBeenCalledWith([]);
    expect(onDelete).toHaveBeenCalledWith(file);

    state.handleExceed();
    expect(mocks.showErrorMessage).toHaveBeenCalledWith('ui.upload.maxNumber');
  });
});
