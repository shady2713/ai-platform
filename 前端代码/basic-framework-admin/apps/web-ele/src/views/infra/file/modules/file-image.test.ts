import type { InfraFileApi } from '#/api/infra/file';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { fetchFileObjectUrl } from '../file-access';
import FileImage from './file-image.vue';

const mocks = vi.hoisted(() => ({
  revokeObjectURL: vi.fn(),
}));

vi.mock('../file-access', () => ({
  fetchFileObjectUrl: vi.fn(),
  isPrivateFile: (row: InfraFileApi.File) => row.accessType === 2,
}));

vi.stubGlobal('URL', {
  createObjectURL: vi.fn(() => 'blob:unused'),
  revokeObjectURL: mocks.revokeObjectURL,
});

function row(overrides: Partial<InfraFileApi.File> = {}): InfraFileApi.File {
  return {
    accessType: 2,
    id: 1,
    path: 'p/a.png',
    type: 'image/png',
    url: '/admin-api/infra/file/1/get/p/a.png',
    ...overrides,
  };
}

describe('file image cell', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('公开图片直显原 URL，不发起认证请求', async () => {
    const wrapper = mount(FileImage, {
      props: { row: row({ accessType: 1, url: 'https://cdn.example/a.png' }) },
    });
    await flushPromises();

    expect(fetchFileObjectUrl).not.toHaveBeenCalled();
    expect(wrapper.find('img').attributes('src')).toBe(
      'https://cdn.example/a.png',
    );
  });

  it('私有图片经 object URL 显示，卸载时回收', async () => {
    vi.mocked(fetchFileObjectUrl).mockResolvedValue('blob:one');
    const wrapper = mount(FileImage, { props: { row: row() } });
    await flushPromises();

    expect(wrapper.find('img').attributes('src')).toBe('blob:one');

    wrapper.unmount();
    expect(mocks.revokeObjectURL).toHaveBeenCalledWith('blob:one');
  });

  it('读取失败保持空占位且不抛出', async () => {
    vi.mocked(fetchFileObjectUrl).mockRejectedValue(new Error('401'));
    const wrapper = mount(FileImage, { props: { row: row() } });
    await flushPromises();

    expect(wrapper.find('img').exists()).toBe(false);
  });

  it('卸载后才返回的 blob 立即回收，不写入视图', async () => {
    let finish!: (url: string) => void;
    vi.mocked(fetchFileObjectUrl).mockReturnValue(
      new Promise<string>((resolve) => {
        finish = resolve;
      }),
    );
    const wrapper = mount(FileImage, { props: { row: row() } });
    wrapper.unmount();

    finish('blob:late');
    await flushPromises();

    expect(mocks.revokeObjectURL).toHaveBeenCalledWith('blob:late');
  });

  it('行切换时立即释放已加载完成的旧 blob', async () => {
    vi.mocked(fetchFileObjectUrl).mockResolvedValueOnce('blob:first');
    const wrapper = mount(FileImage, { props: { row: row() } });
    await flushPromises();
    expect(wrapper.find('img').attributes('src')).toBe('blob:first');

    vi.mocked(fetchFileObjectUrl).mockResolvedValueOnce('blob:second');
    await wrapper.setProps({ row: row({ id: 2, path: 'p/b.png' }) });
    await flushPromises();

    expect(mocks.revokeObjectURL).toHaveBeenCalledWith('blob:first');
    expect(wrapper.find('img').attributes('src')).toBe('blob:second');
  });

  it('行切换时重新加载并释放旧 blob，过期返回不覆盖新图', async () => {
    let finishFirst!: (url: string) => void;
    vi.mocked(fetchFileObjectUrl)
      .mockReturnValueOnce(
        new Promise<string>((resolve) => {
          finishFirst = resolve;
        }),
      )
      .mockResolvedValueOnce('blob:second');
    const wrapper = mount(FileImage, { props: { row: row() } });

    await wrapper.setProps({ row: row({ id: 2, path: 'p/b.png' }) });
    await flushPromises();
    expect(wrapper.find('img').attributes('src')).toBe('blob:second');

    finishFirst('blob:stale');
    await flushPromises();
    expect(wrapper.find('img').attributes('src')).toBe('blob:second');
    expect(mocks.revokeObjectURL).toHaveBeenCalledWith('blob:stale');

    wrapper.unmount();
    expect(mocks.revokeObjectURL).toHaveBeenCalledWith('blob:second');
  });
});
