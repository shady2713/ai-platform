import { beforeEach, describe, expect, it, vi } from 'vitest';

const request = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}));

vi.mock('#/api/request', () => ({
  requestClient: request,
}));

const {
  createTheme,
  getEffectiveTheme,
  getTheme,
  getThemeFonts,
  getThemePage,
  publishTheme,
} = await import('./index');

describe('主题管理 API 客户端（C09）', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    request.get.mockResolvedValue(null);
    request.post.mockResolvedValue(true);
  });

  it('每个操作打到与 V79 控制器一致的路径与参数', async () => {
    await getThemePage({ applicationId: 1, pageNo: 1, pageSize: 50 });
    expect(request.get).toHaveBeenCalledWith('/ai/theme/page', {
      params: { applicationId: 1, pageNo: 1, pageSize: 50 },
    });

    await getTheme(11);
    expect(request.get).toHaveBeenCalledWith('/ai/theme/get', {
      params: { id: 11 },
    });

    await getEffectiveTheme(1);
    expect(request.get).toHaveBeenCalledWith('/ai/theme/effective', {
      params: { applicationId: 1 },
    });

    await getThemeFonts();
    expect(request.get).toHaveBeenCalledWith('/ai/theme/fonts');

    await createTheme({ applicationId: 1, tokensJson: '{}' });
    expect(request.post).toHaveBeenCalledWith('/ai/theme/create', {
      applicationId: 1,
      tokensJson: '{}',
    });

    await publishTheme({ id: 11, version: 0 });
    expect(request.post).toHaveBeenCalledWith('/ai/theme/publish', {
      id: 11,
      version: 0,
    });
  });

  it('返回值原样透传（不在客户端层做二次解释）', async () => {
    request.get.mockResolvedValue([{ id: 1 }]);
    await expect(getThemePage({})).resolves.toStrictEqual([{ id: 1 }]);
    request.post.mockResolvedValue(14);
    await expect(
      createTheme({ applicationId: 1, tokensJson: '{}' }),
    ).resolves.toBe(14);
  });
});
