import { describe, expect, it, vi } from 'vitest';

import { AuthPageLayout, BasicLayout, IFrameView } from './index';

vi.mock('./auth.vue', () => ({ default: { name: 'AuthPageLayout' } }));
vi.mock('./basic.vue', () => ({ default: { name: 'BasicLayout' } }));
vi.mock('@vben/layouts', () => ({ IFrameView: { name: 'IFrameView' } }));

describe('layout loaders', () => {
  it('resolves every exported layout component', async () => {
    const [authModule, basicModule, iframeView] = await Promise.all([
      AuthPageLayout(),
      BasicLayout(),
      IFrameView(),
    ]);

    expect(authModule.default).toEqual({ name: 'AuthPageLayout' });
    expect(basicModule.default).toEqual({ name: 'BasicLayout' });
    expect(iframeView).toEqual({ name: 'IFrameView' });
  });
});
