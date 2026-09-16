import type { InfraJobLogApi } from '#/api/infra/job-log';

import { mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { getJobLog } from '#/api/infra/job-log';

import LogDetail from './detail.vue';

interface ModalConfig {
  onOpenChange: (isOpen: boolean) => Promise<void>;
}

function jobLogData(): InfraJobLogApi.JobLog {
  return {
    beginTime: new Date('2026-09-01T10:00:00Z'),
    cronExpression: '0 * * * * ?',
    duration: '10ms',
    endTime: new Date('2026-09-01T10:00:01Z'),
    executeIndex: '1',
    handlerName: 'demoTask',
    handlerParam: '',
    id: 11,
    jobId: 11,
    result: 'success',
    status: 1,
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

vi.mock('#/api/infra/job-log', () => ({
  getJobLog: vi.fn(),
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
  return mount(LogDetail);
}

describe('infra job log detail modal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.detailData = undefined;
    state.modalApi.getData.mockReturnValue({ id: 11 });
    vi.mocked(getJobLog).mockResolvedValue(jobLogData());
  });

  it('loads the log entry into the descriptions', async () => {
    mountDetail();

    await modalConfig().onOpenChange(true);

    expect(getJobLog).toHaveBeenCalledWith(11);
    expect(state.detailData).toEqual(jobLogData());
    expect(state.modalApi.lock).toHaveBeenCalledOnce();
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();
  });

  it('clears the detail on close and skips missing ids', async () => {
    mountDetail();

    await modalConfig().onOpenChange(false);
    expect(state.detailData).toBeUndefined();

    state.modalApi.getData.mockReturnValue({});
    await modalConfig().onOpenChange(true);
    expect(getJobLog).not.toHaveBeenCalled();
  });
});
