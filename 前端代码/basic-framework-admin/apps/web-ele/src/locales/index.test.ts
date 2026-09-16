import { beforeEach, describe, expect, it, vi } from 'vitest';

import { elementLocale, setupI18n } from './index';

const mocks = vi.hoisted(() => ({
  coreSetup: vi.fn(),
  dayjsLocale: vi.fn(),
  loadAppLocale: vi.fn(),
}));

vi.mock('@vben/locales', () => ({
  $t: vi.fn(),
  loadLocalesMapFromDir: vi.fn(() => ({
    'en-US': mocks.loadAppLocale,
    'zh-CN': mocks.loadAppLocale,
  })),
  setupI18n: mocks.coreSetup,
}));
vi.mock('@vben/preferences', () => ({
  preferences: { app: { locale: 'zh-CN' } },
}));
vi.mock('dayjs', () => ({ default: { locale: mocks.dayjsLocale } }));
vi.mock('dayjs/locale/en', () => ({ default: { name: 'en' } }));
vi.mock('dayjs/locale/zh-cn', () => ({ default: { name: 'zh-cn' } }));
vi.mock('element-plus/es/locale/lang/en', () => ({
  default: { name: 'en-US' },
}));
vi.mock('element-plus/es/locale/lang/zh-cn', () => ({
  default: { name: 'zh-CN' },
}));

describe('application locale setup', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.loadAppLocale.mockResolvedValue({
      default: { common: { confirm: '确认' } },
    });
  });

  it.each([
    ['zh-CN', 'zh-CN', 'zh-cn'],
    ['en-US', 'en-US', 'en'],
  ] as const)(
    'loads application, Element Plus and dayjs messages for %s',
    async (language, elementName, dayjsName) => {
      const app = { use: vi.fn() };

      await setupI18n(app as never, { defaultLocale: language });

      expect(mocks.coreSetup).toHaveBeenCalledOnce();
      const options = mocks.coreSetup.mock.calls[0]?.[1];
      await expect(options.loadMessages(language)).resolves.toEqual({
        common: { confirm: '确认' },
      });
      expect(elementLocale.value).toEqual({ name: elementName });
      expect(mocks.dayjsLocale).toHaveBeenCalledWith({
        default: { name: dayjsName },
      });
    },
  );

  it('uses the configured application locale and development warnings by default', async () => {
    const app = { use: vi.fn() };

    await setupI18n(app as never);

    expect(mocks.coreSetup).toHaveBeenCalledWith(
      app,
      expect.objectContaining({
        defaultLocale: 'zh-CN',
        missingWarn: true,
      }),
    );
  });

  it('falls back to English third-party messages for an unknown locale', async () => {
    const app = { use: vi.fn() };

    await setupI18n(app as never, { defaultLocale: 'fr-FR' as never });

    const options = mocks.coreSetup.mock.calls[0]?.[1];
    await expect(options.loadMessages('fr-FR')).resolves.toBeUndefined();
    expect(mocks.dayjsLocale).toHaveBeenCalledWith({
      default: { name: 'en' },
    });
  });
});
