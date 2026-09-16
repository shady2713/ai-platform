import type { SystemRoleApi } from '#/api/system/role';

import { mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { createRole, getRole, updateRole } from '#/api/system/role';
import { showSuccessMessage } from '#/utils/feedback';

import RoleForm from './form.vue';

interface ModalConfig {
  onConfirm: () => Promise<void>;
  onOpenChange: (isOpen: boolean) => Promise<void>;
}

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn<() => Promise<SystemRoleApi.Role>>(),
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

vi.mock('#/api/system/role', () => ({
  createRole: vi.fn(),
  getRole: vi.fn(),
  updateRole: vi.fn(),
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
  return mount(RoleForm);
}

function role(overrides: Partial<SystemRoleApi.Role> = {}): SystemRoleApi.Role {
  return {
    code: 'auditor',
    dataScope: 1,
    dataScopeDeptIds: [],
    id: 9,
    name: '审计员',
    sort: 1,
    status: 0,
    type: 2,
    ...overrides,
  };
}

describe('system role form modal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.formApi.getValues.mockResolvedValue(role());
    state.modalApi.getData.mockReturnValue({ id: 9 });
    vi.mocked(getRole).mockResolvedValue(role({ name: '审计主管' }));
  });

  it('validates before submitting and creates a role', async () => {
    state.modalApi.getData.mockReturnValue(null);
    const wrapper = mountForm();

    await modalConfig().onOpenChange(true);
    expect(state.modalApi.setState).toHaveBeenCalledWith({
      title: 'ui.actionTitle.create',
    });
    expect(getRole).not.toHaveBeenCalled();

    await modalConfig().onConfirm();
    expect(createRole).toHaveBeenCalledWith(role());
    expect(updateRole).not.toHaveBeenCalled();
    expect(state.modalApi.close).toHaveBeenCalledOnce();
    expect(wrapper.emitted('success')).toHaveLength(1);
    expect(showSuccessMessage).toHaveBeenCalledWith(
      'ui.actionMessage.operationSuccess',
    );
  });

  it('loads the role for editing and submits via update', async () => {
    const wrapper = mountForm();

    await modalConfig().onOpenChange(true);
    expect(state.modalApi.setState).toHaveBeenCalledWith({
      title: 'ui.actionTitle.edit',
    });
    expect(getRole).toHaveBeenCalledWith(9);
    expect(state.formApi.setValues).toHaveBeenCalledWith(
      role({ name: '审计主管' }),
    );
    expect(state.modalApi.lock).toHaveBeenCalledOnce();
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();

    await modalConfig().onConfirm();
    expect(updateRole).toHaveBeenCalledWith(role());
    expect(createRole).not.toHaveBeenCalled();
    expect(wrapper.emitted('success')).toHaveLength(1);
  });

  it('clears the form data on close', async () => {
    mountForm();

    await modalConfig().onOpenChange(false);

    expect(getRole).not.toHaveBeenCalled();
    expect(state.modalApi.setState).not.toHaveBeenCalled();
  });

  it('skips submission when validation fails', async () => {
    state.formApi.validate.mockResolvedValue({ valid: false });
    mountForm();

    await modalConfig().onConfirm();

    expect(createRole).not.toHaveBeenCalled();
    expect(state.modalApi.lock).not.toHaveBeenCalled();
  });

  it('unlocks the modal when the update fails', async () => {
    vi.mocked(updateRole).mockRejectedValue(new Error('request failed'));
    mountForm();

    await modalConfig().onOpenChange(true);
    await expect(modalConfig().onConfirm()).rejects.toThrow('request failed');

    expect(state.modalApi.close).not.toHaveBeenCalled();
    expect(showSuccessMessage).not.toHaveBeenCalled();
    expect(state.modalApi.unlock).toHaveBeenCalledTimes(2);
  });
});
