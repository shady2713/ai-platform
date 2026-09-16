import type { SystemMenuApi } from '#/api/system/menu';

import { mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { createMenu, getMenu, updateMenu } from '#/api/system/menu';
import { showSuccessMessage } from '#/utils/feedback';

import MenuForm from './form.vue';

interface ModalConfig {
  onConfirm: () => Promise<void>;
  onOpenChange: (isOpen: boolean) => Promise<void>;
}

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn<() => Promise<SystemMenuApi.Menu>>(),
    setValues: vi.fn(() => Promise.resolve()),
    validate: vi.fn<() => Promise<{ valid: boolean }>>(),
  },
  modalApi: {
    close: vi.fn(() => Promise.resolve()),
    getData: vi.fn(),
    lock: vi.fn(),
    setState: vi.fn(),
    unlock: vi.fn(),
  },
  modalConfig: undefined as ModalConfig | undefined,
}));

vi.mock('@vben/common-ui', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    useVbenModal: vi.fn((config: ModalConfig) => {
      state.modalConfig = config;
      return [
        defineComponent({
          name: 'ModalStub',
          setup(_props, { slots }) {
            return () => h('section', slots.default?.());
          },
        }),
        state.modalApi,
      ];
    }),
  };
});

vi.mock('#/adapter/form', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    useVbenForm: vi.fn(() => [
      defineComponent({
        name: 'FormStub',
        setup(_props, { slots }) {
          return () => h('form', slots.default?.());
        },
      }),
      state.formApi,
    ]),
  };
});

vi.mock('#/api/system/menu', () => ({
  createMenu: vi.fn(),
  getMenu: vi.fn(),
  updateMenu: vi.fn(),
}));

vi.mock('#/locales', () => ({
  $t: (key: string) => key,
}));

vi.mock('#/utils/feedback', () => ({
  showSuccessMessage: vi.fn(),
}));

vi.mock('../data', () => ({
  useFormSchema: vi.fn(() => []),
}));

function modalConfig() {
  if (!state.modalConfig) {
    throw new Error('弹窗配置未初始化');
  }
  return state.modalConfig;
}

function mountForm() {
  return mount(MenuForm);
}

function menuData(
  overrides: Partial<SystemMenuApi.Menu> = {},
): SystemMenuApi.Menu {
  return {
    component: 'system/user/index',
    createTime: new Date('2026-09-01T00:00:00Z'),
    icon: 'lucide:user',
    id: 7,
    keepAlive: false,
    name: '用户管理',
    parentId: 0,
    path: '/system/user',
    permission: '',
    sort: 0,
    status: 0,
    type: 2,
    visible: true,
    ...overrides,
  };
}

describe('system menu form modal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.formApi.getValues.mockResolvedValue(menuData());
    state.modalApi.getData.mockReturnValue({ id: 7 });
    vi.mocked(getMenu).mockResolvedValue(menuData({ name: '角色管理' }));
  });

  it('validates before submitting and creates a menu', async () => {
    const wrapper = mountForm();

    await modalConfig().onConfirm();

    expect(createMenu).toHaveBeenCalledWith(menuData());
    expect(updateMenu).not.toHaveBeenCalled();
    expect(state.modalApi.close).toHaveBeenCalledOnce();
    expect(wrapper.emitted('success')).toHaveLength(1);
    expect(showSuccessMessage).toHaveBeenCalledWith(
      'ui.actionMessage.operationSuccess',
    );
  });

  it('presets the parent id for new menus and updates on edit', async () => {
    state.modalApi.getData.mockReturnValue({ parentId: 3 });
    const wrapper = mountForm();

    await modalConfig().onOpenChange(true);
    expect(getMenu).not.toHaveBeenCalled();
    expect(state.formApi.setValues).toHaveBeenCalledWith({ parentId: 3 });

    state.modalApi.getData.mockReturnValue({ id: 7 });
    await modalConfig().onOpenChange(false);
    await modalConfig().onOpenChange(true);
    expect(getMenu).toHaveBeenCalledWith(7);
    expect(state.formApi.setValues).toHaveBeenCalledWith(
      menuData({ name: '角色管理' }),
    );

    await modalConfig().onConfirm();
    expect(updateMenu).toHaveBeenCalledWith(menuData());
    expect(wrapper.emitted('success')).toHaveLength(1);
  });

  it('skips submission when validation fails', async () => {
    state.formApi.validate.mockResolvedValue({ valid: false });
    mountForm();

    await modalConfig().onConfirm();

    expect(createMenu).not.toHaveBeenCalled();
    expect(state.modalApi.lock).not.toHaveBeenCalled();
  });

  it('unlocks the modal when the update fails', async () => {
    vi.mocked(updateMenu).mockRejectedValue(new Error('request failed'));
    mountForm();

    await modalConfig().onOpenChange(true);
    await expect(modalConfig().onConfirm()).rejects.toThrow('request failed');

    expect(state.modalApi.close).not.toHaveBeenCalled();
    expect(state.modalApi.unlock).toHaveBeenCalledTimes(2);
  });
});
