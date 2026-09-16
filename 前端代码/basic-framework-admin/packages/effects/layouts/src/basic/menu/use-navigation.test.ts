import type { RouteRecordNormalized } from 'vue-router';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useNavigation } from './use-navigation';

const mocks = vi.hoisted(() => ({
  afterEach: vi.fn(),
  getRoutes: vi.fn(),
  openRouteInNewWindow: vi.fn(),
  openWindow: vi.fn(),
  push: vi.fn(),
  resolve: vi.fn((path: string) => ({ href: `/#${path}` })),
}));

vi.mock('vue-router', () => ({
  useRouter: () => ({
    afterEach: mocks.afterEach,
    getRoutes: mocks.getRoutes,
    push: mocks.push,
    resolve: mocks.resolve,
  }),
}));

vi.mock('@vben/utils', () => ({
  openRouteInNewWindow: mocks.openRouteInNewWindow,
  openWindow: mocks.openWindow,
}));

function route(
  path: string,
  meta: Partial<RouteRecordNormalized['meta']> = {},
): RouteRecordNormalized {
  return { meta: { title: path, ...meta }, path } as RouteRecordNormalized;
}

describe('menu navigation', () => {
  beforeEach(() => {
    mocks.afterEach.mockReset();
    mocks.getRoutes.mockReset();
    mocks.openRouteInNewWindow.mockReset();
    mocks.openWindow.mockReset();
    mocks.push.mockReset();
    mocks.resolve.mockClear();
    mocks.getRoutes.mockReturnValue([
      route('/internal', { query: { from: 'menu' } }),
      route('/new-window', { openInNewWindow: true }),
      route('/linked', { link: 'https://example.com/docs' }),
    ]);
  });

  it('routes internal destinations and preserves registered query data', async () => {
    const { navigation } = useNavigation();
    await navigation('/internal');

    expect(mocks.push).toHaveBeenCalledWith({
      path: '/internal',
      query: { from: 'menu' },
    });
  });

  it('opens allowlisted external and new-window destinations safely', async () => {
    const { navigation, willOpenedByWindow } = useNavigation();

    expect(willOpenedByWindow('https://example.com/help')).toBe(true);
    expect(willOpenedByWindow('/new-window')).toBe(true);
    await navigation('https://example.com/help');
    await navigation('/linked');
    await navigation('/new-window');

    expect(mocks.openWindow).toHaveBeenNthCalledWith(
      1,
      'https://example.com/help',
      { target: '_blank' },
    );
    expect(mocks.openWindow).toHaveBeenNthCalledWith(
      2,
      'https://example.com/docs',
      { target: '_blank' },
    );
    expect(mocks.openRouteInNewWindow).toHaveBeenCalledWith('/#/new-window');
  });

  it.each(['http://example.com', '//example.com', 'javascript:alert(1)'])(
    'rejects unsafe navigation destinations: %s',
    async (destination) => {
      const { navigation } = useNavigation();

      await expect(navigation(destination)).rejects.toThrow(
        'Unsupported navigation destination.',
      );
      expect(mocks.openWindow).not.toHaveBeenCalled();
      expect(mocks.push).not.toHaveBeenCalled();
    },
  );

  it('reads the current routes immediately after registration changes', () => {
    const { willOpenedByWindow } = useNavigation();
    mocks.getRoutes.mockReturnValue([route('/replacement')]);

    expect(willOpenedByWindow('/new-window')).toBe(false);
  });
});
