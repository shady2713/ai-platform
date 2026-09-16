import type { InfraJobApi } from '#/api/infra/job';

import { mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { getJob, getJobNextTimes } from '#/api/infra/job';

import JobDetail from './detail.vue';

interface ModalConfig {
  onOpenChange: (isOpen: boolean) => Promise<void>;
}

function jobData(): InfraJobApi.Job {
  return {
    cronExpression: '0 0 * * * ?',
    handlerName: 'demoTask',
    handlerParam: '',
    id: 7,
    monitorTimeout: 0,
    name: '同步任务',
    retryCount: 0,
    retryInterval: 0,
    status: 2,
  };
}

const state = vi.hoisted(() => ({
  detailData: undefined as unknown,
  modalApi: {
    getData: vi.fn(),
    lock: vi.fn(),
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

vi.mock('#/api/infra/job', () => ({
  getJob: vi.fn(),
  getJobNextTimes: vi.fn(),
}));

vi.mock('#/components/description', async () => {
  const { defineComponent, h, watch } = await import('vue');
  return {
    useDescription: vi.fn(() => [
      defineComponent({
        name: 'DescriptionsStub',
        props: { data: { type: Object, default: undefined } },
        setup(props) {
          watch(
            () => props.data,
            (value) => {
              state.detailData = value;
            },
            { immediate: true },
          );
          return () => h('div', { 'data-test': 'descriptions' });
        },
      }),
      {},
    ]),
  };
});

vi.mock('../data', () => ({
  useDetailSchema: vi.fn(() => []),
}));

function modalConfig() {
  if (!state.modalConfig) {
    throw new Error('弹窗配置未初始化');
  }
  return state.modalConfig;
}

function mountDetail() {
  return mount(JobDetail);
}

describe('infra job detail modal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.detailData = undefined;
    state.modalApi.getData.mockReturnValue({ id: 7 });
    vi.mocked(getJob).mockResolvedValue(jobData());
    vi.mocked(getJobNextTimes).mockResolvedValue([
      new Date('2026-09-06T00:00:00Z'),
    ]);
  });

  it('loads the job and the next run times into the descriptions', async () => {
    mountDetail();

    await modalConfig().onOpenChange(true);

    expect(getJob).toHaveBeenCalledWith(7);
    expect(getJobNextTimes).toHaveBeenCalledWith(7);
    expect(state.detailData).toEqual({
      ...jobData(),
      nextTimes: [new Date('2026-09-06T00:00:00Z')],
    });
    expect(state.modalApi.lock).toHaveBeenCalledOnce();
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();
  });

  it('skips loading when the modal closes or the id is missing', async () => {
    mountDetail();

    await modalConfig().onOpenChange(false);
    expect(getJob).not.toHaveBeenCalled();

    state.modalApi.getData.mockReturnValue({});
    await modalConfig().onOpenChange(true);
    expect(getJob).not.toHaveBeenCalled();
    expect(state.modalApi.lock).not.toHaveBeenCalled();
  });
});
