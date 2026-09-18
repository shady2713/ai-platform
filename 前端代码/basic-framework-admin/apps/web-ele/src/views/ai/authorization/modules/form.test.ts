import type { AiGrantApi } from '#/api/ai/grant';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { createGrant, getGrantPage, updateGrant } from '#/api/ai/grant';
import { showConfirmDialog, showSuccessMessage } from '#/utils/feedback';

import GrantForm from './form.vue';

interface ModalConfig {
  onConfirm: () => Promise<void>;
  onOpenChange: (isOpen: boolean) => Promise<void>;
}

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn(),
    resetForm: vi.fn(() => Promise.resolve()),
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

vi.mock('#/api/ai/grant', () => ({
  createGrant: vi.fn(),
  getGrantPage: vi.fn(),
  updateGrant: vi.fn(),
}));

vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('#/utils/feedback', () => ({
  showConfirmDialog: vi.fn(),
  showSuccessMessage: vi.fn(),
}));

function modalConfig() {
  if (!state.modalConfig) {
    throw new Error('弹窗配置未初始化');
  }
  return state.modalConfig;
}

function grant(overrides: Partial<AiGrantApi.Grant> = {}): AiGrantApi.Grant {
  return {
    actions: ['READ'],
    applicationId: 5,
    authzRevision: 1,
    externalUserId: 'alice',
    id: 3,
    resourceKey: 'report-1',
    resourceType: 'REPORT',
    status: 'ACTIVE',
    subjectType: 'USER',
    version: 1,
    ...overrides,
  };
}

describe('ai authorization form', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.modalApi.getData.mockReturnValue(undefined);
    vi.mocked(getGrantPage).mockResolvedValue({
      list: [
        grant({ resourceKey: 'report-1', resourceType: 'REPORT' }),
        grant({ id: 4, resourceKey: 'kb-1', resourceType: 'KNOWLEDGE_BASE' }),
      ],
      total: 2,
    } as never);
  });

  it('shows the blast-radius warning and loads only already-authorized resources', async () => {
    state.modalApi.getData.mockReturnValue(grant());
    const wrapper = mount(GrantForm);

    await modalConfig().onOpenChange(true);
    await flushPromises();

    expect(wrapper.find('[data-test="grant-scope-warning"]').text()).toContain(
      '立即生效',
    );
    expect(getGrantPage).toHaveBeenCalledWith(
      expect.objectContaining({
        applicationId: 5,
        externalUserId: 'alice',
        subjectType: 'USER',
      }),
    );
  });

  it('creates a grant for an authorized resource without extra confirmation', async () => {
    state.formApi.getValues.mockResolvedValue({
      actions: ['READ'],
      applicationId: '5',
      externalUserId: 'alice',
      resourceKey: 'report-1',
      resourceType: 'REPORT',
      subjectType: 'USER',
    });
    vi.mocked(createGrant).mockResolvedValue(3);
    // 新建入口：不带行数据（getData 为空），选项在确认时按表单里的应用/主体现取
    mount(GrantForm);
    await modalConfig().onConfirm();

    expect(showConfirmDialog).not.toHaveBeenCalled();
    expect(createGrant).toHaveBeenCalledWith({
      actions: ['READ'],
      applicationId: 5,
      externalUserId: 'alice',
      resourceKey: 'report-1',
      resourceType: 'REPORT',
      subjectType: 'USER',
    });
    expect(showSuccessMessage).toHaveBeenCalled();
  });

  it('requires confirmation when granting a resource outside the authorized list', async () => {
    state.formApi.getValues.mockResolvedValue({
      actions: ['READ'],
      applicationId: '5',
      externalUserId: 'alice',
      resourceKey: 'report-999',
      resourceType: 'REPORT',
      subjectType: 'USER',
    });
    vi.mocked(showConfirmDialog).mockResolvedValue({
      action: 'cancel',
    } as never);
    mount(GrantForm);

    await modalConfig().onConfirm();
    expect(showConfirmDialog).toHaveBeenCalled();
    expect(createGrant).not.toHaveBeenCalled();

    vi.mocked(showConfirmDialog).mockResolvedValue({
      action: 'confirm',
    } as never);
    await modalConfig().onConfirm();
    expect(createGrant).toHaveBeenCalled();
  });

  it('updates only the action white list and keeps the optimistic lock version', async () => {
    state.modalApi.getData.mockReturnValue(grant());
    state.formApi.getValues.mockResolvedValue({
      actions: ['READ', 'EXPORT'],
      applicationId: '5',
      id: 3,
      resourceKey: 'report-1',
      resourceType: 'REPORT',
      subjectType: 'USER',
      version: 1,
    });
    mount(GrantForm);

    await modalConfig().onOpenChange(true);
    await flushPromises();
    await modalConfig().onConfirm();

    expect(updateGrant).toHaveBeenCalledWith({
      actions: ['READ', 'EXPORT'],
      id: 3,
      version: 1,
    });
    expect(createGrant).not.toHaveBeenCalled();
  });
});
