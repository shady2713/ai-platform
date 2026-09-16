import type { MockInstance } from 'vitest';

import type { InfraFileApi } from '#/api/infra/file';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { fetchFileContent } from '#/api/infra/file';

import {
  downloadObjectUrl,
  fetchFileObjectUrl,
  isPrivateFile,
  openFile,
} from './file-access';

// 不 mock @vben/utils：公开分支必须穿过真实 openWindow 的协议白名单，
// 预览分支一旦误用 openWindow 处理 blob: 会被白名单抛出 TypeError，回归即测试红
const mocks = vi.hoisted(() => ({
  createObjectURL: vi.fn(() => 'blob:mock-object-url'),
  revokeObjectURL: vi.fn(),
}));

// 与生产常量保持一致；request 模块依赖构建期 env，不加载真实实现
vi.mock('#/api/infra/file', () => ({
  FILE_ACCESS_TYPE: { PRIVATE: 2, PUBLIC: 1 },
  fetchFileContent: vi.fn(),
}));

vi.stubGlobal(
  'URL',
  class extends URL {
    static override createObjectURL = mocks.createObjectURL;
    static override revokeObjectURL = mocks.revokeObjectURL;
  },
);

function fileRow(overrides: Partial<InfraFileApi.File>): InfraFileApi.File {
  return {
    accessType: 2,
    id: 1,
    name: '合同.pdf',
    path: 'contract/a.pdf',
    type: 'application/pdf',
    url: '/admin-api/infra/file/4/get/contract/a.pdf',
    ...overrides,
  };
}

describe('file access helpers', () => {
  let openSpy: MockInstance<
    (url?: string | URL, target?: string, features?: string) => null | Window
  >;

  beforeEach(() => {
    vi.clearAllMocks();
    openSpy = vi
      .spyOn(window, 'open')
      .mockImplementation(() => null as unknown as Window);
  });

  it('按 accessType 识别私有文件', () => {
    expect(isPrivateFile(fileRow({ accessType: 2 }))).toBe(true);
    expect(isPrivateFile(fileRow({ accessType: 1 }))).toBe(false);
    expect(isPrivateFile(fileRow({ accessType: undefined }))).toBe(false);
  });

  it('经认证请求获取内容并转为 object URL', async () => {
    const blob = new Blob(['content'], { type: 'application/pdf' });
    vi.mocked(fetchFileContent).mockResolvedValue(blob);

    const url = await fetchFileObjectUrl(fileRow({}));

    expect(fetchFileContent).toHaveBeenCalledWith(
      '/admin-api/infra/file/4/get/contract/a.pdf',
    );
    expect(mocks.createObjectURL).toHaveBeenCalledWith(blob);
    expect(url).toBe('blob:mock-object-url');
  });

  it('公开文件经真实 openWindow 直开原 URL，不发起认证请求', async () => {
    await openFile(
      fileRow({ accessType: 1, url: 'https://cdn.example.com/a.pdf' }),
    );

    expect(fetchFileContent).not.toHaveBeenCalled();
    expect(openSpy).toHaveBeenCalledWith(
      'https://cdn.example.com/a.pdf',
      '_blank',
      'noopener=yes,noreferrer=yes',
    );
  });

  it('私有 PDF 在新标签页预览，object URL 延迟回收', async () => {
    vi.useFakeTimers();
    try {
      vi.mocked(fetchFileContent).mockResolvedValue(new Blob(['pdf']));
      // noopener 语义下 window.open 规范上恒返回 null，不能据此判断弹窗拦截
      openSpy.mockImplementation(() => null);

      await openFile(fileRow({}));

      expect(openSpy).toHaveBeenCalledWith(
        'blob:mock-object-url',
        '_blank',
        'noopener',
      );
      expect(mocks.revokeObjectURL).not.toHaveBeenCalled();
      vi.advanceTimersByTime(60_000);
      expect(mocks.revokeObjectURL).toHaveBeenCalledWith(
        'blob:mock-object-url',
      );
    } finally {
      vi.useRealTimers();
    }
  });

  it('image/svg+xml 不预览而走下载分支，避免主动内容在新标签页渲染', async () => {
    vi.mocked(fetchFileContent).mockResolvedValue(new Blob(['svg']));
    const click = vi.fn();
    const link = { click, download: '', href: '' };
    const createElement = vi
      .spyOn(document, 'createElement')
      .mockReturnValue(link as unknown as HTMLElement);

    await openFile(fileRow({ name: 'icon.svg', type: 'image/svg+xml' }));

    expect(openSpy).not.toHaveBeenCalled();
    expect(link.href).toBe('blob:mock-object-url');
    expect(link.download).toBe('icon.svg');
    expect(click).toHaveBeenCalledOnce();
    createElement.mockRestore();
  });

  it('私有二进制文件触发下载并延迟回收 object URL', async () => {
    vi.useFakeTimers();
    try {
      vi.mocked(fetchFileContent).mockResolvedValue(new Blob(['bin']));
      const click = vi.fn();
      const link = { click, download: '', href: '' };
      const createElement = vi
        .spyOn(document, 'createElement')
        .mockReturnValue(link as unknown as HTMLElement);

      await openFile(fileRow({ name: 'data.zip', type: 'application/zip' }));

      expect(createElement).toHaveBeenCalledWith('a');
      expect(link.href).toBe('blob:mock-object-url');
      expect(link.download).toBe('data.zip');
      expect(click).toHaveBeenCalledOnce();
      expect(openSpy).not.toHaveBeenCalled();
      expect(mocks.revokeObjectURL).not.toHaveBeenCalled();
      vi.advanceTimersByTime(1000);
      expect(mocks.revokeObjectURL).toHaveBeenCalledWith(
        'blob:mock-object-url',
      );
      createElement.mockRestore();
    } finally {
      vi.useRealTimers();
    }
  });

  it('downloadObjectUrl 回填文件名并点击', () => {
    const click = vi.fn();
    const link = { click, download: '', href: '' };
    const createElement = vi
      .spyOn(document, 'createElement')
      .mockReturnValue(link as unknown as HTMLElement);

    downloadObjectUrl('blob:x', '报表.xlsx');

    expect(link.href).toBe('blob:x');
    expect(link.download).toBe('报表.xlsx');
    expect(click).toHaveBeenCalledOnce();
    createElement.mockRestore();
  });
});
