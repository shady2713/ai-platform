import type { Slots } from 'vue';

import { mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';

import { loadLocaleMessages } from '@vben/locales';
import {
  COLOR_PRESETS,
  preferences,
  resetPreferences,
} from '@vben/preferences';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import ColorToggle from '../color-toggle.vue';
import LanguageToggle from '../language-toggle.vue';
import LayoutToggle from '../layout-toggle.vue';

const dropDownValue = vi.hoisted(() => ({ value: '' }));

vi.mock('@vben/locales', () => ({
  $t: (key: string) => key,
  loadLocaleMessages: vi.fn(),
}));

/** shadcn 组件以 name/__name 匹配，双写键确保命中。 */
const iconButtonStub = {
  name: 'IconButtonStub',
  setup(_: unknown, { slots }: { slots: Slots }) {
    return () => h('button', { class: 'icon-btn' }, [slots.default?.()]);
  },
};

const dropdownStubs = {
  DropdownRadioMenu: defineComponent({
    name: 'DropdownRadioMenuStub',
    props: {
      menus: { type: Array, default: () => [] },
      modelValue: { type: String, default: '' },
    },
    emits: ['update:modelValue'],
    setup(
      _: unknown,
      {
        emit,
        slots,
      }: {
        emit: (event: 'update:modelValue', value: string) => void;
        slots: Slots;
      },
    ) {
      return () =>
        h(
          'button',
          {
            id: 'dropdown',
            onClick: () => emit('update:modelValue', dropDownValue.value),
          },
          [slots.default?.()],
        );
    },
  }),

  VbenIconButton: iconButtonStub,
  'icon-button': iconButtonStub,
};

describe('colorToggle', () => {
  beforeEach(() => resetPreferences());
  afterEach(() => resetPreferences());

  it('renders one button per color preset', () => {
    const wrapper = mount(ColorToggle, {
      global: {
        stubs: {
          VbenIconButton: dropdownStubs.VbenIconButton,
          'icon-button': dropdownStubs.VbenIconButton,
        },
      },
    });
    expect(wrapper.findAll('.icon-btn').length).toBe(COLOR_PRESETS.length + 1);
  });

  it('updates the theme when a preset color is clicked', async () => {
    const wrapper = mount(ColorToggle, {
      global: {
        stubs: {
          VbenIconButton: dropdownStubs.VbenIconButton,
          'icon-button': dropdownStubs.VbenIconButton,
        },
      },
    });
    const preset = COLOR_PRESETS[1];
    if (!preset) {
      throw new Error('fixture requires the second color preset');
    }
    await wrapper.findAll('.icon-btn')[1]?.trigger('click');
    expect(preferences.theme.colorPrimary).toBe(preset.color);
    expect(preferences.theme.builtinType).toBe(preset.type);
  });
});

describe('languageToggle', () => {
  beforeEach(() => {
    resetPreferences();
    dropDownValue.value = 'en-US';
  });
  afterEach(() => resetPreferences());

  it('updates the locale and loads its messages', async () => {
    const wrapper = mount(LanguageToggle, {
      global: { stubs: dropdownStubs },
    });
    await wrapper.get('#dropdown').trigger('click');
    expect(preferences.app.locale).toBe('en-US');
    expect(loadLocaleMessages).toHaveBeenCalledWith('en-US');
  });

  it('ignores empty menu values', async () => {
    dropDownValue.value = '';
    const wrapper = mount(LanguageToggle, {
      global: { stubs: dropdownStubs },
    });
    await wrapper.get('#dropdown').trigger('click');
    expect(preferences.app.locale).not.toBe('');
  });
});

describe('layoutToggle', () => {
  beforeEach(() => resetPreferences());
  afterEach(() => resetPreferences());

  it('renders the three layout options', () => {
    const wrapper = mount(LayoutToggle, {
      global: { stubs: dropdownStubs },
    });
    expect(wrapper.find('#dropdown').exists()).toBe(true);
  });

  it('applies the selected auth page layout', async () => {
    dropDownValue.value = 'panel-center';
    const wrapper = mount(LayoutToggle, {
      global: { stubs: dropdownStubs },
    });
    await wrapper.get('#dropdown').trigger('click');
    expect(preferences.app.authPageLayout).toBe('panel-center');
  });

  it('ignores empty values', async () => {
    dropDownValue.value = '';
    const wrapper = mount(LayoutToggle, {
      global: { stubs: dropdownStubs },
    });
    await wrapper.get('#dropdown').trigger('click');
    expect(preferences.app.authPageLayout).not.toBe('');
  });
});
