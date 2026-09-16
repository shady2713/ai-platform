import type { InfraJobApi } from '#/api/infra/job';

import { mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { createJob, getJob, updateJob } from '#/api/infra/job';
import { showSuccessMessage } from '#/utils/feedback';

import JobForm from './form.vue';

interface ModalConfig {
  onConfirm: () => Promise<void>;
  onOpenChange: (isOpen: boolean) => Promise<void>;
}

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn<() => Promise<InfraJobApi.Job>>(),
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

vi.mock('#/api/infra/job', () => ({
  createJob: vi.fn(),
  getJob: vi.fn(),
  updateJob: vi.fn(),
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
  return mount(JobForm);
}

function jobData(overrides: Partial<InfraJobApi.Job> = {}): InfraJobApi.Job {
  return {
    cronExpression: '0 0 * * * ?',
    handlerName: 'demoTask',
    handlerParam: '',
    id: 7,
    monitorTimeout: 0,
    name: '同步任务',
    retryCount: 3,
    retryInterval: 60,
    status: 2,
    ...overrides,
  };
}

describe('infra job form modal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.formApi.getValues.mockResolvedValue(jobData());
    state.modalApi.getData.mockReturnValue({ id: 7 });
    vi.mocked(getJob).mockResolvedValue(jobData({ name: '重命名任务' }));
  });

  it('validates before submitting and creates a job', async () => {
    const wrapper = mountForm();

    await modalConfig().onConfirm();

    expect(createJob).toHaveBeenCalledWith(jobData());
    expect(updateJob).not.toHaveBeenCalled();
    expect(state.modalApi.close).toHaveBeenCalledOnce();
    expect(wrapper.emitted('success')).toHaveLength(1);
    expect(showSuccessMessage).toHaveBeenCalledWith(
      'ui.actionMessage.operationSuccess',
    );
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();
  });

  it('loads the job for editing and submits via update', async () => {
    const wrapper = mountForm();

    await modalConfig().onOpenChange(true);
    expect(getJob).toHaveBeenCalledWith(7);
    expect(state.formApi.setValues).toHaveBeenCalledWith(
      jobData({ name: '重命名任务' }),
    );

    await modalConfig().onConfirm();
    expect(updateJob).toHaveBeenCalledWith(jobData());
    expect(createJob).not.toHaveBeenCalled();
    expect(wrapper.emitted('success')).toHaveLength(1);
  });

  it('skips submission when validation fails', async () => {
    state.formApi.validate.mockResolvedValue({ valid: false });
    mountForm();

    await modalConfig().onConfirm();

    expect(createJob).not.toHaveBeenCalled();
    expect(state.modalApi.lock).not.toHaveBeenCalled();
  });

  it('resets the edit target when reopening without an id', async () => {
    mountForm();

    await modalConfig().onOpenChange(true);
    state.modalApi.getData.mockReturnValue({});
    await modalConfig().onOpenChange(false);
    await modalConfig().onOpenChange(true);
    expect(getJob).toHaveBeenCalledOnce();
    expect(state.modalApi.lock).toHaveBeenCalledOnce();

    await modalConfig().onConfirm();
    expect(createJob).toHaveBeenCalledOnce();
    expect(updateJob).not.toHaveBeenCalled();
  });
});
