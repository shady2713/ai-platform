import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({
  createTheme: vi.fn(),
  getApplicationPage: vi.fn(),
  getEffectiveTheme: vi.fn(),
  getThemeFonts: vi.fn(),
  getThemePage: vi.fn(),
  publishTheme: vi.fn(),
}));

vi.mock('#/api/ai/chat', () => ({
  createTheme: api.createTheme,
  getEffectiveTheme: api.getEffectiveTheme,
  getThemeFonts: api.getThemeFonts,
  getThemePage: api.getThemePage,
  publishTheme: api.publishTheme,
}));

vi.mock('#/api/ai/application', () => ({
  getApplicationPage: api.getApplicationPage,
}));

vi.mock('@vben/common-ui', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    Page: defineComponent({
      name: 'PageStub',
      setup(_props, { slots }) {
        return () => h('main', { 'data-testid': 'page' }, slots.default?.());
      },
    }),
  };
});

const { default: ThemeIndex } = await import('./index.vue');

const FONT = 'system-ui, sans-serif';

function themeRow(overrides: Record<string, unknown> = {}) {
  return {
    applicationId: 1,
    id: 11,
    layoutJson:
      '{"fontScale":"normal","density":"normal","narrowBreakpoint":768}',
    publicId: 'thm_a',
    publicationState: 'DRAFT',
    revision: 2,
    tokensFingerprint: 'f'.repeat(64),
    tokensJson: `{"primaryColor":"#1677ff","radius":6,"fontFamily":"${FONT}"}`,
    version: 0,
    ...overrides,
  };
}

async function mountPage() {
  const wrapper = mount(ThemeIndex);
  await flushPromises();
  return wrapper;
}

describe('主题管理页（C09）', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.getApplicationPage.mockResolvedValue({
      list: [{ appCode: 'crm-portal', id: 1, name: 'CRM 门户' }],
      total: 1,
    });
    api.getThemeFonts.mockResolvedValue([FONT]);
    api.getThemePage.mockResolvedValue({
      list: [
        themeRow(),
        themeRow({ id: 12, publicationState: 'PUBLISHED', revision: 3 }),
        themeRow({ id: 13, publicationState: 'SUPERSEDED', revision: 1 }),
      ],
      total: 3,
    });
    api.getEffectiveTheme.mockResolvedValue({
      applicationId: 1,
      fingerprint: 'f'.repeat(64),
      layoutJson: '{"narrowBreakpoint":768}',
      revision: 3,
      source: 'APPLICATION_PUBLISHED',
      tokensJson: `{"primaryColor":"#1677ff","radius":6,"fontFamily":"${FONT}"}`,
    });
    api.createTheme.mockResolvedValue(14);
    api.publishTheme.mockResolvedValue(true);
  });

  it('挂载即读取应用、字体与修订，并展示有效主题来源', async () => {
    const wrapper = await mountPage();

    expect(api.getApplicationPage).toHaveBeenCalled();
    expect(wrapper.text()).toContain('APPLICATION_PUBLISHED');
    expect(wrapper.text()).toContain('主色 #1677ff');
    expect(wrapper.text()).toContain('字号 normal');
  });

  it('当前生效的修订不给发布按钮，草稿与已被取代的可以', async () => {
    const wrapper = await mountPage();
    const buttons = wrapper.findAll('button').map((button) => button.text());
    const publishButtons = buttons.filter(
      (text) => text === '发布' || text === '回退到此版本',
    );

    expect(publishButtons).toHaveLength(2);
    expect(buttons).toContain('回退到此版本');
  });

  it('创建修订提交的是结构化 token（键序固定）而不是 CSS 文本', async () => {
    const wrapper = await mountPage();

    const create = wrapper
      .findAll('button')
      .find((button) => button.text() === '创建修订');
    await create?.trigger('click');
    await flushPromises();

    expect(api.createTheme).toHaveBeenCalledWith({
      applicationId: 1,
      tokensJson: expect.stringContaining('"primaryColor"'),
    });
    const firstCall = api.createTheme.mock.calls[0] as
      | [{ tokensJson: string }]
      | undefined;
    const payload = firstCall?.[0];
    expect(payload).toBeDefined();
    expect(
      Object.keys(JSON.parse(payload?.tokensJson ?? '{}') as object),
    ).toStrictEqual(['primaryColor', 'radius', 'fontFamily', 'colorScheme']);
    expect(wrapper.text()).toContain('已创建新修订');
  });

  it('发布失败给出明确提示（不伪装成功）', async () => {
    api.publishTheme.mockRejectedValue(new Error('conflict'));
    const wrapper = await mountPage();

    const publish = wrapper
      .findAll('button')
      .find((button) => button.text() === '发布');
    await publish?.trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('发布失败');
  });
});
