import type { SystemNotifyMessageApi } from '#/api/system/notify/message';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import MessageDetail from './detail.vue';

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
  return mount(MessageDetail);
}

function message(): SystemNotifyMessageApi.Message {
  return {
    createTime: new Date('2026-09-01T00:00:00Z'),
    id: 6,
    readStatus: false,
    templateCode: 'password_reset',
    templateContent: '您的验证码是 {code}',
    templateId: 3,
    templateNickname: '系统',
    templateParams: { code: '123456' },
    templateType: 1,
    userId: 1,
    userType: 1,
  };
}

describe('notify message detail modal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.detailData = undefined;
    state.modalApi.getData.mockReturnValue(message());
  });

  it('shows the selected message inside the descriptions', async () => {
    mountDetail();

    await modalConfig().onOpenChange(true);
    await flushPromises();

    expect(state.detailData).toEqual(message());
    expect(state.modalApi.lock).toHaveBeenCalledOnce();
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();
  });

  it('clears the detail on close and ignores rows without an id', async () => {
    mountDetail();

    await modalConfig().onOpenChange(false);
    await flushPromises();
    expect(state.detailData).toBeUndefined();

    state.modalApi.getData.mockReturnValue({ content: '你好' });
    await modalConfig().onOpenChange(true);
    await flushPromises();
    expect(state.detailData).toBeUndefined();
    expect(state.modalApi.lock).not.toHaveBeenCalled();
  });
});
