import type { Ref } from 'vue';

import type { ExtendedVxeGridApi, VxeGridProps } from './types';

import { mount } from '@vue/test-utils';
import { nextTick, ref } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import VxeGridComponent from './use-vxe-grid.vue';

interface PriorityValues {
  class: Ref<string | undefined>;
  formOptions: Ref<Record<string, unknown> | undefined>;
  gridClass: Ref<string | undefined>;
  gridEvents: Ref<Record<string, unknown> | undefined>;
  gridOptions: Ref<VxeGridProps['gridOptions']>;
  separator: Ref<boolean | undefined>;
  showSearchForm: Ref<boolean | undefined>;
  tableTitle: Ref<string | undefined>;
  tableTitleHelp: Ref<string | undefined>;
}

interface FormSetupOptions {
  handleReset: () => Promise<void>;
  handleSubmit: () => Promise<void>;
}

const mocks = vi.hoisted(() => ({
  extendProxyOptions: vi.fn(),
  formApi: {
    getLatestSubmissionValues: vi.fn(() => ({})),
    getState: vi.fn(() => ({ compact: true })),
    getValues: vi.fn(
      async (): Promise<Record<string, unknown> | undefined> => ({ status: 1 }),
    ),
    resetForm: vi.fn(async () => undefined),
    setLatestSubmissionValues: vi.fn(),
    setState: vi.fn((update: (state: object) => object) => update({})),
    unmount: vi.fn(),
  },
  formSetup: undefined as unknown as FormSetupOptions,
  gridAttrs: {} as Record<string, unknown>,
  gridCommit: vi.fn(async () => undefined),
  isMobile: undefined as unknown as Ref<boolean>,
  priority: undefined as unknown as PriorityValues,
  vxeConfig: undefined as undefined | { grid: Record<string, unknown> },
  useTableForm: vi.fn((options: FormSetupOptions) => {
    mocks.formSetup = options;
    return [{ name: 'FormStub', render: () => null }, mocks.formApi] as const;
  }),
}));

vi.mock('@vben/hooks', () => ({
  usePriorityValues: () => mocks.priority,
}));

vi.mock('@vben/icons', () => ({
  EmptyIcon: { name: 'EmptyIcon', render: () => null },
}));

vi.mock('@vben/locales', () => ({ $t: (key: string) => key }));

vi.mock('@vben/preferences', () => ({
  usePreferences: () => ({ isMobile: mocks.isMobile }),
}));

vi.mock('@vben-core/shadcn-ui', () => ({
  VbenHelpTooltip: { name: 'VbenHelpTooltip', render: () => null },
  VbenLoading: { name: 'VbenLoading', render: () => null },
}));

vi.mock('./extends', () => ({
  extendProxyOptions: mocks.extendProxyOptions,
}));

vi.mock('./init', () => ({ useTableForm: mocks.useTableForm }));

vi.mock('vxe-pc-ui', () => ({
  VxeButton: { name: 'VxeButton', render: () => null },
}));

vi.mock('vxe-table', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    VxeGrid: defineComponent({
      name: 'VxeGridStub',
      inheritAttrs: false,
      setup: (_, { attrs, expose, slots }) => {
        mocks.gridAttrs = attrs;
        expose({ commitProxy: mocks.gridCommit });
        return () =>
          h('section', { class: 'grid-stub' }, [
            slots['toolbar-actions']?.({}),
            slots.form?.(),
            slots.loading?.(),
            slots.empty?.(),
            slots['toolbar-tools']?.({}),
          ]);
      },
    }),
    VxeUI: { getConfig: () => mocks.vxeConfig },
  };
});

function createApi(): ExtendedVxeGridApi {
  return {
    grid: { commitProxy: mocks.gridCommit },
    mount: vi.fn(),
    reload: vi.fn(async () => undefined),
    setState: vi.fn(),
    toggleSearchForm: vi.fn(),
    unmount: vi.fn(),
    useStore: vi.fn(() => ref({})),
  } as unknown as ExtendedVxeGridApi;
}

describe('vxeGrid component wiring', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.isMobile = ref(false);
    mocks.vxeConfig = { grid: {} };
    mocks.priority = {
      class: ref(undefined),
      formOptions: ref({ submitOnChange: true }),
      gridClass: ref(undefined),
      gridEvents: ref(undefined),
      gridOptions: ref({
        pagerConfig: {},
        proxyConfig: { ajax: {}, autoLoad: true },
        toolbarConfig: { search: true },
      }),
      separator: ref(undefined),
      showSearchForm: ref(true),
      tableTitle: ref(undefined),
      tableTitleHelp: ref(undefined),
    };
  });

  it('mounts, auto-loads with form values and releases both APIs', async () => {
    const api = createApi();
    const wrapper = mount(VxeGridComponent, { props: { api } });
    await nextTick();
    await nextTick();

    expect(api.mount).toHaveBeenCalledOnce();
    expect(mocks.gridCommit).toHaveBeenCalledWith('query', { status: 1 });
    expect(api.setState).toHaveBeenCalledOnce();
    expect(mocks.extendProxyOptions).toHaveBeenCalledOnce();
    const getLatestSubmissionValues =
      mocks.extendProxyOptions.mock.calls[0]?.[2];
    expect(getLatestSubmissionValues?.()).toEqual({});

    wrapper.unmount();
    expect(mocks.formApi.unmount).toHaveBeenCalledOnce();
    expect(api.unmount).toHaveBeenCalledOnce();
  });

  it('delegates submit, reset and toolbar events through public APIs', async () => {
    const toolbarListener = vi.fn();
    mocks.priority.gridEvents.value = {
      toolbarToolClick: toolbarListener,
    };
    mocks.priority.showSearchForm.value = false;
    mocks.priority.gridOptions.value = {
      proxyConfig: { ajax: {}, autoLoad: false },
      toolbarConfig: { search: true },
    };
    const api = createApi();
    const wrapper = mount(VxeGridComponent, { props: { api } });
    await nextTick();

    await mocks.formSetup.handleSubmit();
    expect(mocks.formApi.setLatestSubmissionValues).toHaveBeenCalledWith({
      status: 1,
    });
    expect(api.reload).toHaveBeenCalledWith({ status: 1 });

    mocks.formApi.getValues
      .mockResolvedValueOnce({ status: 1 })
      .mockResolvedValueOnce({ status: 2 });
    await mocks.formSetup.handleReset();
    expect(mocks.formApi.resetForm).toHaveBeenCalledOnce();
    expect(api.reload).toHaveBeenCalledTimes(1);

    mocks.priority.formOptions.value = { submitOnChange: false };
    mocks.formApi.getValues
      .mockResolvedValueOnce({ status: 2 })
      .mockResolvedValueOnce({ status: 3 });
    await mocks.formSetup.handleReset();
    expect(api.reload).toHaveBeenLastCalledWith({ status: 3 });

    const toolbarHandler = mocks.gridAttrs.onToolbarToolClick;
    expect(toolbarHandler).toBeTypeOf('function');
    if (typeof toolbarHandler === 'function') {
      toolbarHandler({ code: 'search' });
    }
    expect(api.toggleSearchForm).toHaveBeenCalledOnce();
    expect(toolbarListener).toHaveBeenCalledWith({ code: 'search' });

    wrapper.unmount();
  });

  it('renders a titled toolbar and warns about unsupported native forms', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    mocks.priority.tableTitle.value = 'Orders';
    mocks.priority.tableTitleHelp.value = 'Order help';
    mocks.vxeConfig = undefined;
    mocks.priority.gridOptions.value = {
      formConfig: { enabled: true },
      proxyConfig: { autoLoad: false },
    };
    const wrapper = mount(VxeGridComponent, { props: { api: createApi() } });
    await nextTick();
    await nextTick();

    expect(wrapper.text()).toContain('Orders');
    expect(warn).toHaveBeenCalledWith(
      expect.stringContaining('formConfig in the grid is not supported'),
    );

    wrapper.unmount();
    warn.mockRestore();
  });

  it('auto-loads with empty parameters when no search form exists', async () => {
    mocks.priority.formOptions.value = undefined;
    mocks.priority.gridOptions.value = {
      proxyConfig: { ajax: {}, autoLoad: true },
    };
    const wrapper = mount(VxeGridComponent, { props: { api: createApi() } });
    await nextTick();
    await nextTick();

    expect(mocks.gridCommit).toHaveBeenCalledWith('query', {});

    wrapper.unmount();
  });

  it('normalizes an empty form result during automatic loading', async () => {
    mocks.formApi.getValues.mockResolvedValueOnce(undefined);
    const wrapper = mount(VxeGridComponent, { props: { api: createApi() } });
    await nextTick();
    await nextTick();

    expect(mocks.gridCommit).toHaveBeenCalledWith('query', {});

    wrapper.unmount();
  });
});
