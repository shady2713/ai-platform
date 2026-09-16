import type { SystemPostApi } from '#/api/system/post';

import { mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { createPost, getPost, updatePost } from '#/api/system/post';
import { showSuccessMessage } from '#/utils/feedback';

import PostForm from './form.vue';

interface ModalConfig {
  onConfirm: () => Promise<void>;
  onOpenChange: (isOpen: boolean) => Promise<void>;
}

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn<() => Promise<SystemPostApi.Post>>(),
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

vi.mock('#/api/system/post', () => ({
  createPost: vi.fn(),
  getPost: vi.fn(),
  updatePost: vi.fn(),
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
  return mount(PostForm);
}

function post(overrides: Partial<SystemPostApi.Post> = {}): SystemPostApi.Post {
  return {
    code: 'auditor',
    id: 4,
    name: '审计专员',
    remark: '',
    sort: 1,
    status: 0,
    ...overrides,
  };
}

describe('system post form modal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.formApi.getValues.mockResolvedValue(post());
    state.modalApi.getData.mockReturnValue({ id: 4 });
    vi.mocked(getPost).mockResolvedValue(post({ name: '审计主管' }));
  });

  it('validates before submitting and creates a post', async () => {
    state.modalApi.getData.mockReturnValue(null);
    const wrapper = mountForm();

    await modalConfig().onOpenChange(true);
    expect(state.modalApi.setState).toHaveBeenCalledWith({
      title: 'ui.actionTitle.create',
    });
    expect(getPost).not.toHaveBeenCalled();

    await modalConfig().onConfirm();
    expect(createPost).toHaveBeenCalledWith(post());
    expect(updatePost).not.toHaveBeenCalled();
    expect(state.modalApi.close).toHaveBeenCalledOnce();
    expect(wrapper.emitted('success')).toHaveLength(1);
    expect(showSuccessMessage).toHaveBeenCalledWith(
      'ui.actionMessage.operationSuccess',
    );
  });

  it('loads the post for editing and submits via update', async () => {
    const wrapper = mountForm();

    await modalConfig().onOpenChange(true);
    expect(state.modalApi.setState).toHaveBeenCalledWith({
      title: 'ui.actionTitle.edit',
    });
    expect(getPost).toHaveBeenCalledWith(4);
    expect(state.formApi.setValues).toHaveBeenCalledWith(
      post({ name: '审计主管' }),
    );
    expect(state.modalApi.lock).toHaveBeenCalledOnce();
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();

    await modalConfig().onConfirm();
    expect(updatePost).toHaveBeenCalledWith(post());
    expect(createPost).not.toHaveBeenCalled();
    expect(wrapper.emitted('success')).toHaveLength(1);
  });

  it('clears the form data on close', async () => {
    mountForm();

    await modalConfig().onOpenChange(false);

    expect(getPost).not.toHaveBeenCalled();
    expect(state.modalApi.setState).not.toHaveBeenCalled();
  });

  it('skips submission when validation fails', async () => {
    state.formApi.validate.mockResolvedValue({ valid: false });
    mountForm();

    await modalConfig().onConfirm();

    expect(createPost).not.toHaveBeenCalled();
    expect(state.modalApi.lock).not.toHaveBeenCalled();
  });

  it('unlocks the modal when the create fails', async () => {
    state.modalApi.getData.mockReturnValue(null);
    vi.mocked(createPost).mockRejectedValue(new Error('request failed'));
    mountForm();

    await modalConfig().onOpenChange(true);
    await expect(modalConfig().onConfirm()).rejects.toThrow('request failed');

    expect(state.modalApi.close).not.toHaveBeenCalled();
    expect(showSuccessMessage).not.toHaveBeenCalled();
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();
  });
});
