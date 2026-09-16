import type { SystemLoginLogApi } from '#/api/system/login-log';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import LoginLogDetail from './detail.vue';

interface ModalConfig {
  onOpenChange: (isOpen: boolean) => Promise<void>;
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
  return mount(LoginLogDetail);
}

function loginLog(): SystemLoginLogApi.LoginLog {
  return {
    createTime: '2026-09-01 10:00:00',
    id: 5,
    logType: 1,
    result: 0,
    status: 0,
    traceId: 1001,
    userAgent: 'Chrome',
    userId: 1,
    userIp: '127.0.0.1',
    username: 'admin',
    userType: 1,
  };
}

describe('login log detail modal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.detailData = undefined;
    state.modalApi.getData.mockReturnValue(loginLog());
  });

  it('shows the selected log row inside the descriptions', async () => {
    mountDetail();

    await modalConfig().onOpenChange(true);
    await flushPromises();

    expect(state.detailData).toEqual(loginLog());
    expect(state.modalApi.lock).toHaveBeenCalledOnce();
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();
  });

  it('clears the detail on close and ignores rows without an id', async () => {
    mountDetail();

    await modalConfig().onOpenChange(false);
    await flushPromises();
    expect(state.detailData).toBeUndefined();

    state.modalApi.getData.mockReturnValue({ username: 'admin' });
    await modalConfig().onOpenChange(true);
    await flushPromises();
    expect(state.detailData).toBeUndefined();
    expect(state.modalApi.lock).not.toHaveBeenCalled();
  });
});
