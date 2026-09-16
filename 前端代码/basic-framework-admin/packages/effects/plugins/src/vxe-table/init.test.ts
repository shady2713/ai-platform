import type { useVbenForm } from '@vben-core/form-ui';

import { nextTick, ref } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  component: vi.fn(),
  config: vi.fn(),
  formatter: vi.fn(),
  locale: undefined as unknown as { value: 'en-US' | 'zh-CN' },
  setI18n: vi.fn(),
  setLanguage: vi.fn(),
  setTheme: vi.fn(),
  isDark: undefined as unknown as { value: boolean },
}));

vi.mock('@vben/preferences', () => ({
  usePreferences: () => ({ isDark: mocks.isDark, locale: mocks.locale }),
}));

vi.mock('@vben-core/form-ui', () => ({ useVbenForm: vi.fn() }));

vi.mock('./extends', () => ({
  extendsDefaultFormatter: mocks.formatter,
}));

vi.mock('vxe-pc-ui/lib/language/en-US', () => ({
  default: { language: 'en-US' },
}));
vi.mock('vxe-pc-ui/lib/language/zh-CN', () => ({
  default: { language: 'zh-CN' },
}));

vi.mock('vxe-pc-ui', () => ({
  VxeButton: { name: 'VxeButton' },
  VxeCheckbox: { name: 'VxeCheckbox' },
  VxeIcon: { name: 'VxeIcon' },
  VxeInput: { name: 'VxeInput' },
  VxeLoading: { name: 'VxeLoading' },
  VxeModal: { name: 'VxeModal' },
  VxeNumberInput: { name: 'VxeNumberInput' },
  VxePager: { name: 'VxePager' },
  VxeRadio: { name: 'VxeRadio' },
  VxeRadioButton: { name: 'VxeRadioButton' },
  VxeRadioGroup: { name: 'VxeRadioGroup' },
  VxeSelect: { name: 'VxeSelect' },
  VxeTooltip: { name: 'VxeTooltip' },
  VxeUpload: { name: 'VxeUpload' },
  VxeUI: {
    component: mocks.component,
    setI18n: mocks.setI18n,
    setLanguage: mocks.setLanguage,
    setTheme: mocks.setTheme,
  },
}));

vi.mock('vxe-table', () => ({
  VxeColgroup: { name: 'VxeColgroup' },
  VxeColumn: { name: 'VxeColumn' },
  VxeGrid: { name: 'VxeGrid' },
  VxeTable: { name: 'VxeTable' },
  VxeToolbar: { name: 'VxeToolbar' },
}));

describe('vXE table initialization', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.resetModules();
    mocks.isDark = ref(false);
    mocks.locale = ref<'en-US' | 'zh-CN'>('zh-CN');
  });

  it('fails loud when a grid form is created before setup', async () => {
    const { useTableForm } = await import('./init');

    expect(() => useTableForm({})).toThrow(
      'call setupVbenVxeTable before rendering grids',
    );
  });

  it('registers the required VXE components exactly once', async () => {
    const { initVxeTable } = await import('./init');

    initVxeTable();
    initVxeTable();

    expect(mocks.component).toHaveBeenCalledTimes(20);
    expect(mocks.component).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'VxeForm' }),
    );
  });

  it('configures appearance, formatters and the form factory once', async () => {
    const { setupVbenVxeTable, useTableForm } = await import('./init');
    const formResult = [{ name: 'Form' }, { name: 'api' }] as const;
    const formFactory = vi.fn(
      () => formResult,
    ) as unknown as typeof useVbenForm;
    const configVxeTable = vi.fn();

    setupVbenVxeTable({ configVxeTable, useVbenForm: formFactory });

    expect(mocks.setTheme).toHaveBeenLastCalledWith('light');
    expect(mocks.setI18n).toHaveBeenLastCalledWith('zh-CN', {
      language: 'zh-CN',
    });
    expect(mocks.setLanguage).toHaveBeenLastCalledWith('zh-CN');
    expect(mocks.formatter).toHaveBeenCalledOnce();
    expect(configVxeTable).toHaveBeenCalledOnce();
    expect(useTableForm({})).toBe(formResult);

    mocks.isDark.value = true;
    mocks.locale.value = 'en-US';
    await nextTick();

    expect(mocks.setTheme).toHaveBeenLastCalledWith('dark');
    expect(mocks.setI18n).toHaveBeenLastCalledWith('en-US', {
      language: 'en-US',
    });
    expect(mocks.setLanguage).toHaveBeenLastCalledWith('en-US');
    expect(() =>
      setupVbenVxeTable({ configVxeTable, useVbenForm: formFactory }),
    ).toThrow('already been configured');
  });
});
