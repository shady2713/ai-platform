import type { AiApplicationApi } from '#/api/ai/application';
import type { AiDiscoveryApi } from '#/api/ai/application/discovery';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getSystemCatalog,
  selectAnalysisScope,
  verifyAnalysisScope,
} from '#/api/ai/application/discovery';
import { showRequestError } from '#/utils/feedback';

import Discovery from './discovery.vue';

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
  getSystemCatalog: vi.fn(),
  selectAnalysisScope: vi.fn(),
  verifyAnalysisScope: vi.fn(),
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

function entry(
  overrides: Partial<AiDiscoveryApi.SystemEntry> = {},
): AiDiscoveryApi.SystemEntry {
  return {
    appCode: 'crm',
    applicationId: 5,
    currentSystem: true,
    externalUserId: 'alice',
    federationId: null,
    federationRevision: null,
    scopeSource: 'crm-auth',
    scopeVersion: 1,
    scopes: [{ actions: ['READ'], resourceKey: 'q3', resourceType: 'REPORT' }],
    subjectType: 'USER',
    systemFingerprint: 'system-fp-crm',
    systemName: 'CRM 系统',
    ...overrides,
  };
}

function catalog(
  overrides: Partial<AiDiscoveryApi.Catalog> = {},
): AiDiscoveryApi.Catalog {
  return {
    applicationId: 5,
    catalogFingerprint: 'catalog-fp',
    denied: false,
    entries: [
      entry(),
      entry({
        appCode: 'erp',
        applicationId: 9,
        currentSystem: false,
        federationId: 11,
        federationRevision: 2,
        systemFingerprint: 'system-fp-erp',
        systemName: 'ERP 系统',
      }),
    ],
    externalUserId: 'alice',
    modelCatalog: '[{"system":"crm"},{"system":"erp"}]',
    subjectType: 'USER',
    ...overrides,
  };
}

function selection(
  overrides: Partial<AiDiscoveryApi.AnalysisScopeSelection> = {},
): AiDiscoveryApi.AnalysisScopeSelection {
  return {
    applicationId: 5,
    catalogFingerprint: 'catalog-fp',
    externalUserId: 'alice',
    mode: 'CROSS_SYSTEM',
    modelCatalog: '[{"system":"crm"},{"system":"erp"}]',
    selectionFingerprint: 'selection-fp',
    subjectType: 'USER',
    systems: [],
    targetSystemCodes: ['crm', 'erp'],
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

describe('ai application discovery dialog', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.modalApi.getData.mockReturnValue(application());
    state.formApi.getValues.mockResolvedValue({
      externalUserId: 'alice',
      subjectType: 'USER',
    });
  });

  it('shows only discovered systems and preselects them', async () => {
    vi.mocked(getSystemCatalog).mockResolvedValue(catalog());
    const wrapper = mount(Discovery);

    await open(wrapper);
    await button(wrapper, 'discover-run').trigger('click');
    await flushPromises();

    expect(getSystemCatalog).toHaveBeenCalledWith({
      applicationId: 5,
      externalUserId: 'alice',
      subjectType: 'USER',
    });
    expect(wrapper.find('[data-test="entry-crm"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="entry-erp"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="scope-crm"]').text()).toContain(
      'REPORT/q3（READ）',
    );
    // 默认全选可访问系统（含当前系统）：目录之外的系统没有勾选入口
    const checkboxes = wrapper.findAll('input[type="checkbox"]');
    expect(checkboxes).toHaveLength(2);
    expect(
      checkboxes.every((box) => (box.element as HTMLInputElement).checked),
    ).toBe(true);
  });

  it('hides every system and explains the uniform denial', async () => {
    vi.mocked(getSystemCatalog).mockResolvedValue(
      catalog({ denied: true, entries: [] }),
    );
    const wrapper = mount(Discovery);

    await open(wrapper);
    await button(wrapper, 'discover-run').trigger('click');
    await flushPromises();

    // 无权系统不出现：既不列出系统，也不给"存在但无权"的提示
    expect(wrapper.find('[data-test="entry-crm"]').exists()).toBe(false);
    const denied = button(wrapper, 'discovery-denied');
    expect(denied.text()).toContain('没有任何可访问系统');
    expect(denied.text()).toContain('不区分');
  });

  it('fixes CURRENT_SYSTEM when only the current system stays selected', async () => {
    vi.mocked(getSystemCatalog).mockResolvedValue(catalog());
    vi.mocked(selectAnalysisScope).mockResolvedValue(
      selection({ mode: 'CURRENT_SYSTEM', targetSystemCodes: ['crm'] }),
    );
    const wrapper = mount(Discovery);

    await open(wrapper);
    await button(wrapper, 'discover-run').trigger('click');
    await flushPromises();
    await button(wrapper, 'toggle-erp').trigger('change');
    await button(wrapper, 'discover-select').trigger('click');
    await flushPromises();

    expect(selectAnalysisScope).toHaveBeenCalledWith({
      applicationId: 5,
      catalogFingerprint: 'catalog-fp',
      externalUserId: 'alice',
      mode: 'CURRENT_SYSTEM',
      subjectType: 'USER',
      targetSystemCodes: undefined,
    });
  });

  it('sends CROSS_SYSTEM with the observed catalog fingerprint and explicit targets', async () => {
    vi.mocked(getSystemCatalog).mockResolvedValue(catalog());
    vi.mocked(selectAnalysisScope).mockResolvedValue(selection());
    const wrapper = mount(Discovery);

    await open(wrapper);
    await button(wrapper, 'discover-run').trigger('click');
    await flushPromises();
    await button(wrapper, 'discover-select').trigger('click');
    await flushPromises();

    expect(selectAnalysisScope).toHaveBeenCalledWith({
      applicationId: 5,
      catalogFingerprint: 'catalog-fp',
      externalUserId: 'alice',
      mode: 'CROSS_SYSTEM',
      subjectType: 'USER',
      targetSystemCodes: ['crm', 'erp'],
    });
    expect(button(wrapper, 'selection-result').text()).toContain('跨系统分析');
    expect(button(wrapper, 'selection-result').text()).toContain(
      'selection-fp',
    );
  });

  it('reports selection denial and verification failure without hiding the fact', async () => {
    vi.mocked(getSystemCatalog).mockResolvedValue(catalog());
    vi.mocked(selectAnalysisScope).mockRejectedValue(new Error('409'));
    const wrapper = mount(Discovery);

    await open(wrapper);
    await button(wrapper, 'discover-run').trigger('click');
    await flushPromises();
    await button(wrapper, 'discover-select').trigger('click');
    await flushPromises();

    expect(showRequestError).toHaveBeenCalledWith(
      expect.any(Error),
      '范围选择被拒绝',
    );

    vi.mocked(selectAnalysisScope).mockResolvedValue(selection());
    await button(wrapper, 'discover-select').trigger('click');
    await flushPromises();
    vi.mocked(verifyAnalysisScope).mockRejectedValue(new Error('409'));
    await button(wrapper, 'discover-verify').trigger('click');
    await flushPromises();

    expect(button(wrapper, 'selection-verify-result').text()).toContain(
      '核验失败',
    );

    vi.mocked(verifyAnalysisScope).mockResolvedValue(selection());
    await button(wrapper, 'discover-verify').trigger('click');
    await flushPromises();
    expect(button(wrapper, 'selection-verify-result').text()).toContain(
      '核验通过',
    );
  });

  it('reports discovery failure and clears state when closed', async () => {
    vi.mocked(getSystemCatalog).mockRejectedValue(new Error('boom'));
    const wrapper = mount(Discovery);

    await open(wrapper);
    await button(wrapper, 'discover-run').trigger('click');
    await flushPromises();
    expect(showRequestError).toHaveBeenCalledWith(
      expect.any(Error),
      '授权发现失败',
    );

    // 关闭即清理：再次打开不会看到上一次的目录
    vi.mocked(getSystemCatalog).mockResolvedValue(catalog());
    await state.modalConfig?.onOpenChange(false);
    await open(wrapper);
    expect(wrapper.find('[data-test="entry-crm"]').exists()).toBe(false);
  });
});
