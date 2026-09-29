import type { AiApplicationApi } from '#/api/ai/application';
import type { AiDiscoveryApi } from '#/api/ai/application/discovery';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  approveSubjectFederation,
  getSubjectFederationPage,
  revokeSubjectFederation,
  submitSubjectFederation,
} from '#/api/ai/application/discovery';
import { showRequestError } from '#/utils/feedback';

import Federation from './federation.vue';

interface ModalConfig {
  onOpenChange: (isOpen: boolean) => Promise<void> | void;
}

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn(),
  },
  modalApi: {
    getData: vi.fn(),
    lock: vi.fn(),
    setState: vi.fn(),
    unlock: vi.fn(),
  },
  modalConfig: undefined as ModalConfig | undefined,
}));

vi.mock('@vben/common-ui', async () => {
  const actual =
    await vi.importActual<typeof import('@vben/common-ui')>('@vben/common-ui');
  const { defineComponent, h } = await import('vue');
  return {
    z: actual.z,
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

vi.mock('#/api/ai/application/discovery', () => ({
  approveSubjectFederation: vi.fn(),
  getSubjectFederationPage: vi.fn(),
  revokeSubjectFederation: vi.fn(),
  submitSubjectFederation: vi.fn(),
}));

vi.mock('#/utils/feedback', () => ({
  showRequestError: vi.fn(),
  showSuccessMessage: vi.fn(),
}));

function application(): AiApplicationApi.Application {
  return {
    appCode: 'crm-portal',
    credentialConfigured: true,
    description: '',
    enabled: true,
    id: 5,
    name: 'CRM 门户',
    origins: ['https://crm.example.com'],
    version: 2,
  };
}

function federation(
  overrides: Partial<AiDiscoveryApi.Federation> = {},
): AiDiscoveryApi.Federation {
  return {
    approvedBy: null,
    id: 42,
    requestedBy: 1001,
    revision: 1,
    sourceApplicationId: 5,
    sourceExternalUserId: 'alice',
    sourceSubjectType: 'USER',
    status: 'PENDING',
    targetApplicationId: 9,
    targetExternalUserId: 'alice',
    targetSubjectType: 'USER',
    version: 0,
    ...overrides,
  };
}

async function open(wrapper: ReturnType<typeof mount>) {
  await state.modalConfig?.onOpenChange(true);
  await flushPromises();
  return wrapper;
}

function button(wrapper: ReturnType<typeof mount>, test: string) {
  const found = wrapper.find(`[data-test="${test}"]`);
  if (found.exists()) {
    return found;
  }
  throw new Error(`未找到元素：${test}`);
}

describe('ai application federation dialog', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.modalApi.getData.mockReturnValue(application());
    vi.mocked(getSubjectFederationPage).mockResolvedValue({
      list: [federation()],
      total: 1,
    });
    state.formApi.getValues.mockResolvedValue({
      sourceExternalUserId: 'alice',
      sourceSubjectType: 'USER',
      targetApplicationId: '9',
      targetExternalUserId: 'alice',
      targetSubjectType: 'USER',
    });
  });

  it('loads approved and pending mappings with their status', async () => {
    vi.mocked(getSubjectFederationPage).mockResolvedValue({
      list: [
        federation(),
        federation({ id: 43, revision: 2, status: 'APPROVED', version: 1 }),
      ],
      total: 2,
    });
    const wrapper = mount(Federation);

    await open(wrapper);

    expect(getSubjectFederationPage).toHaveBeenCalledWith({
      pageNo: 1,
      pageSize: 20,
      sourceApplicationId: 5,
    });
    expect(button(wrapper, 'federation-42').text()).toContain('USER:alice');
    expect(button(wrapper, 'federation-42').text()).toContain('待独立审批');
    expect(button(wrapper, 'federation-43').text()).toContain('已批准');
  });

  it('submits the explicit identity pair without any name-based inference', async () => {
    vi.mocked(submitSubjectFederation).mockResolvedValue(42);
    const wrapper = mount(Federation);

    await open(wrapper);
    await button(wrapper, 'federation-submit').trigger('click');
    await flushPromises();

    expect(submitSubjectFederation).toHaveBeenCalledWith({
      sourceApplicationId: 5,
      sourceExternalUserId: 'alice',
      sourceSubjectType: 'USER',
      targetApplicationId: 9,
      targetExternalUserId: 'alice',
      targetSubjectType: 'USER',
    });
    expect(button(wrapper, 'federation-no-inference').text()).toContain(
      '不代表',
    );
  });

  it('drops onesided identity fields for APP subjects', async () => {
    state.formApi.getValues.mockResolvedValue({
      sourceExternalUserId: 'ignored',
      sourceSubjectType: 'APP',
      targetApplicationId: '9',
      targetExternalUserId: 'ignored',
      targetSubjectType: 'APP',
    });
    vi.mocked(submitSubjectFederation).mockResolvedValue(42);
    const wrapper = mount(Federation);

    await open(wrapper);
    await button(wrapper, 'federation-submit').trigger('click');
    await flushPromises();

    expect(submitSubjectFederation).toHaveBeenCalledWith(
      expect.objectContaining({
        sourceExternalUserId: undefined,
        targetExternalUserId: undefined,
      }),
    );
  });

  it('refuses my own request and non-numeric target application', async () => {
    state.formApi.getValues.mockResolvedValue({
      sourceExternalUserId: 'alice',
      sourceSubjectType: 'USER',
      targetApplicationId: 'not-a-number',
      targetExternalUserId: 'alice',
      targetSubjectType: 'USER',
    });
    const wrapper = mount(Federation);

    await open(wrapper);
    await button(wrapper, 'federation-submit').trigger('click');
    await flushPromises();

    expect(submitSubjectFederation).not.toHaveBeenCalled();
    expect(showRequestError).toHaveBeenCalledWith(
      expect.any(Error),
      '登记被拒绝',
    );
  });

  it('approves pending mappings with the current version and revokes effective ones', async () => {
    vi.mocked(approveSubjectFederation).mockResolvedValue(true);
    vi.mocked(revokeSubjectFederation).mockResolvedValue(true);
    const wrapper = mount(Federation);

    await open(wrapper);
    await button(wrapper, 'federation-approve-42').trigger('click');
    await flushPromises();

    expect(approveSubjectFederation).toHaveBeenCalledWith({
      approvalNote: '独立审批通过（映射 #42）',
      id: 42,
      version: 0,
    });

    vi.mocked(getSubjectFederationPage).mockResolvedValue({
      list: [federation({ revision: 2, status: 'APPROVED', version: 1 })],
      total: 1,
    });
    await open(wrapper);
    await button(wrapper, 'federation-revoke-42').trigger('click');
    await flushPromises();

    expect(revokeSubjectFederation).toHaveBeenCalledWith(42, 1);
  });

  it('reports failures and shows the empty state', async () => {
    vi.mocked(getSubjectFederationPage).mockResolvedValue({
      list: [],
      total: 0,
    });
    const wrapper = mount(Federation);

    await open(wrapper);
    expect(button(wrapper, 'federation-empty').text()).toContain(
      '只能看到当前系统',
    );

    vi.mocked(approveSubjectFederation).mockRejectedValue(new Error('409'));
    vi.mocked(getSubjectFederationPage).mockResolvedValue({
      list: [federation()],
      total: 1,
    });
    await open(wrapper);
    await button(wrapper, 'federation-approve-42').trigger('click');
    await flushPromises();
    expect(showRequestError).toHaveBeenCalledWith(
      expect.any(Error),
      '审批被拒绝',
    );
  });
});
