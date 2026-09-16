import { useSimpleLocale } from '@vben-core/composables';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  i18n,
  loadLocaleMessages,
  loadLocalesMap,
  loadLocalesMapFromDir,
  setupI18n,
} from './i18n';

beforeEach(() => {
  i18n.global.locale.value = '';
  document.documentElement.removeAttribute('lang');
  vi.restoreAllMocks();
});

describe('locale module maps', () => {
  it('loads flat locale modules by file name', async () => {
    const loadEnglish = vi.fn().mockResolvedValue({ default: { ok: 'OK' } });
    const locales = loadLocalesMap({ './en-US.json': loadEnglish });

    await expect(locales['en-US']?.()).resolves.toEqual({
      default: { ok: 'OK' },
    });
    expect(loadEnglish).toHaveBeenCalledOnce();
  });

  it('groups directory modules and ignores unmatched paths', async () => {
    const locales = loadLocalesMapFromDir(/\.\/langs\/([^/]+)\/(.*)\.json$/, {
      './ignored.txt': vi.fn(),
      './langs/zh-CN/common.json': vi
        .fn()
        .mockResolvedValue({ default: { confirm: '确认' } }),
      './langs/zh-CN/system.json': vi
        .fn()
        .mockResolvedValue({ default: { user: '用户' } }),
    });

    await expect(locales['zh-CN']?.()).resolves.toEqual({
      default: {
        common: { confirm: '确认' },
        system: { user: '用户' },
      },
    });
  });

  it('initializes the app, loads both message layers and synchronizes locale state', async () => {
    const app = { use: vi.fn() };
    const loadMessages = vi.fn().mockResolvedValue({
      application: { title: 'Admin Console' },
    });

    await setupI18n(app as never, {
      defaultLocale: 'en-US',
      loadMessages,
    });

    expect(app.use).toHaveBeenCalledWith(i18n);
    expect(loadMessages).toHaveBeenCalledWith('en-US');
    expect(i18n.global.getLocaleMessage('en-US')).toMatchObject({
      application: { title: 'Admin Console' },
    });
    expect(i18n.global.locale.value).toBe('en-US');
    expect(document.documentElement.lang).toBe('en-US');
    expect(useSimpleLocale().currentLocale.value).toBe('en-US');
  });

  it('supports direct loading before setup and an empty application message layer', async () => {
    await expect(loadLocaleMessages('zh-CN')).resolves.toBeUndefined();

    const app = { use: vi.fn() };
    await expect(
      setupI18n(app as never, {
        defaultLocale: 'en-US',
        loadMessages: async () => undefined,
      }),
    ).resolves.toBeUndefined();
    expect(i18n.global.locale.value).toBe('en-US');
  });

  it('does not reload messages when the requested language is already active', async () => {
    const loadMessages = vi.fn().mockResolvedValue({});
    await setupI18n({ use: vi.fn() } as never, {
      defaultLocale: 'en-US',
      loadMessages,
    });
    loadMessages.mockClear();
    document.documentElement.removeAttribute('lang');

    await loadLocaleMessages('en-US');

    expect(loadMessages).not.toHaveBeenCalled();
    expect(document.documentElement.lang).toBe('en-US');
  });

  it('warns only for qualified missing translation keys when enabled', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    await setupI18n({ use: vi.fn() } as never, { missingWarn: true });
    const missingHandler = i18n.global.getMissingHandler();

    expect(missingHandler).toBeTypeOf('function');
    missingHandler?.('zh-CN', 'missing.key', {} as never, 'translate');
    missingHandler?.('zh-CN', 'plain-key', {} as never, 'translate');
    missingHandler?.('zh-CN', 'OAuth 2.0', {} as never, 'translate');

    expect(warn).toHaveBeenCalledOnce();
    expect(warn).toHaveBeenCalledWith(
      "[intlify] Not found 'missing.key' key in 'zh-CN' locale messages.",
    );
  });
});
