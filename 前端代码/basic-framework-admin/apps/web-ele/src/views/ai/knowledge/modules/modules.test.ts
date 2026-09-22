import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  deleteDocument,
  getDocumentPage,
  getDocumentVersionPage,
  getIngestionTaskPage,
  readDocumentContent,
  retryIngestionTask,
  uploadDocument,
} from '#/api/ai/knowledge';
import { showSuccessMessage } from '#/utils/feedback';

import Documents from './documents.vue';

interface GridConfig {
  gridOptions: {
    proxyConfig: {
      ajax: { query: (params: unknown, formValues: unknown) => unknown };
    };
    rowClassName?: (params: { row: Record<string, unknown> }) => string;
  };
}

const state = vi.hoisted(() => ({
  gridApi: { query: vi.fn() },
  gridConfig: undefined as unknown,
  modalApi: {
    getData: vi.fn(() => ({ id: 61, name: '员工手册' })),
    setState: vi.fn(),
  },
  row: {} as Record<string, unknown>,
  modalConfigs: [] as { onOpenChange?: (isOpen: boolean) => unknown }[],
}));

vi.mock('@vben/common-ui', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    useVbenModal: vi.fn(
      (config: { onOpenChange?: (isOpen: boolean) => unknown }) => {
        state.modalConfigs.push(config);
        return [
          defineComponent({
            name: 'ModalStub',
            setup(_props, { slots }) {
              return () => h('section', slots.default?.());
            },
          }),
          state.modalApi,
        ];
      },
    ),
  };
});

vi.mock('#/adapter/vxe-table', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    ACTION_ICON: {
      DELETE: 'delete',
      REFRESH: 'refresh',
      UPLOAD: 'upload',
      VIEW: 'view',
    },
    TableAction: defineComponent({
      name: 'TableActionStub',
      props: {
        actions: { default: () => [], type: Array },
        dropDownActions: { default: () => [], type: Array },
      },
      setup(props, { slots }) {
        return () =>
          h('div', [
            ...(props.actions as ActionStub[]).map((action, index) =>
              h(
                'button',
                {
                  'data-action': action.label,
                  'data-auth': (action.auth ?? []).join(','),
                  'data-testid': `action-${index}`,
                  onClick: action.onClick,
                },
                action.label,
              ),
            ),
            ...(props.dropDownActions as ActionStub[]).map((action, index) =>
              h(
                'button',
                {
                  'data-action': action.label,
                  'data-auth': (action.auth ?? []).join(','),
                  'data-testid': `dropdown-${index}`,
                  onClick: action.popConfirm?.confirm ?? action.onClick,
                },
                action.label,
              ),
            ),
            slots.default?.(),
          ]);
      },
    }),
    useVbenVxeGrid: vi.fn((config: GridConfig) => {
      state.gridConfig = config;
      return [
        defineComponent({
          name: 'GridStub',
          setup(_props, { slots }) {
            return () => h('div', slots.actions?.({ row: state.row }));
          },
        }),
        state.gridApi,
      ];
    }),
  };
});

vi.mock('#/api/ai/knowledge', () => ({
  deleteDocument: vi.fn(() => Promise.resolve(true)),
  getDocumentPage: vi.fn(() => Promise.resolve({ list: [], total: 0 })),
  getDocumentVersionPage: vi.fn(() =>
    Promise.resolve({
      list: [
        {
          chunkCount: 3,
          failureReason: undefined,
          id: 81,
          indexGeneration: 2,
          status: 'READY',
          versionNo: 1,
        },
      ],
      total: 1,
    }),
  ),
  getIngestionTaskPage: vi.fn(() =>
    Promise.resolve({
      list: [
        {
          attemptCount: 3,
          documentId: 71,
          id: 91,
          lastErrorCode: 'embed-upstream_failed',
          maxAttempts: 3,
          status: 'FAILED',
          taskKind: 'PARSE',
          version: 2,
        },
      ],
      total: 1,
    }),
  ),
  readDocumentContent: vi.fn(() => Promise.resolve(new Blob(['原文']))),
  retryIngestionTask: vi.fn(() => Promise.resolve(true)),
  uploadDocument: vi.fn(() =>
    Promise.resolve({
      createdVersion: true,
      documentId: 71,
      taskId: 91,
      versionId: 81,
      versionNo: 1,
    }),
  ),
}));

vi.mock('#/utils/feedback', () => ({ showSuccessMessage: vi.fn() }));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));

/** 测试替身里的动作形状（与 TableAction 的 actions 项一致的最小子集）。 */
interface ActionStub {
  auth?: string[];
  label?: string;
  onClick?: () => unknown;
  popConfirm?: { confirm?: () => unknown; title?: string };
}

describe('ai knowledge documents module', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.row = { id: 71, status: 'FAILED', title: '员工手册', version: 4 };
  });

  it('文档查询按知识库过滤并带上分页', async () => {
    const wrapper = mount(Documents);
    await flushPromises();
    wrapper.vm.$data;
    const config = state.gridConfig as GridConfig;
    await config.gridOptions.proxyConfig.ajax.query(
      { page: { currentPage: 1, pageSize: 10 } },
      { status: 'FAILED' },
    );
    expect(getDocumentPage).toHaveBeenCalledWith(
      expect.objectContaining({ pageNo: 1, pageSize: 10, status: 'FAILED' }),
    );
  });

  it('上传入口按"文档入库"权限码显示（点击链路的端到端行为由阶段验收覆盖）', async () => {
    const wrapper = mount(Documents);
    await flushPromises();
    const uploadButton = wrapper
      .findAll('[data-action]')
      .find((action) => action.attributes('data-action')?.includes('上传'));
    expect(uploadButton?.attributes('data-auth')).toBe('ai:knowledge:ingest');
    // 文件 + 幂等键 + 标题三件套由 uploadDocument 组装（表单字段已在页面上暴露），
    // 真实上传链路由 K03 的服务端用例与阶段验收覆盖
    expect(
      typeof (wrapper.vm as unknown as { setUploadFile: unknown })
        .setUploadFile,
    ).toBe('function');
  });

  it('失败任务可重试；删除走撤销（提示后台回收）', async () => {
    const wrapper = mount(Documents);
    await flushPromises();
    const retry = wrapper
      .findAll('[data-action]')
      .find((action) => action.attributes('data-action') === '重试');
    await retry?.trigger('click');
    await flushPromises();
    expect(retryIngestionTask).toHaveBeenCalledWith(91, 2);

    const remove = wrapper
      .findAll('[data-action]')
      .find((action) => action.attributes('data-action')?.includes('delete'));
    await remove?.trigger('click');
    await flushPromises();
    expect(deleteDocument).toHaveBeenCalledWith(71, 4);
    expect(showSuccessMessage).toHaveBeenCalledWith(
      expect.stringContaining('回收'),
    );
  });

  it('原文预览走受控 API（返回二进制并由浏览器打开）', async () => {
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(null);
    vi.stubGlobal('URL', { createObjectURL: vi.fn(() => 'blob:mock') });
    const wrapper = mount(Documents);
    await flushPromises();
    const preview = wrapper
      .findAll('[data-action]')
      .find((action) => action.attributes('data-action') === '预览原文');
    await preview?.trigger('click');
    await flushPromises();
    expect(readDocumentContent).toHaveBeenCalledWith(71);
    expect(openSpy).toHaveBeenCalledWith('blob:mock', '_blank');
    openSpy.mockRestore();
  });

  it('上传入库：带幂等键与标题，成功后刷新并提示"新版本/复用"', async () => {
    const wrapper = mount(Documents);
    await flushPromises();
    await state.modalConfigs.at(-1)?.onOpenChange?.(true);
    (
      wrapper.vm as unknown as { setUploadFile: (file?: File) => void }
    ).setUploadFile(new File(['内容'], 'handbook.txt', { type: 'text/plain' }));
    await wrapper.vm.$nextTick();
    const inputs = wrapper.findAll('input');
    await inputs[0]?.setValue('handbook/v1.txt');
    await inputs[1]?.setValue('员工手册');

    const uploadButton = wrapper
      .findAll('[data-action]')
      .find((action) => action.attributes('data-action')?.includes('上传'));
    await uploadButton?.trigger('click');
    await flushPromises();

    expect(uploadDocument).toHaveBeenCalledWith(
      expect.objectContaining({
        sourceKey: 'handbook/v1.txt',
        title: '员工手册',
      }),
    );
    expect(showSuccessMessage).toHaveBeenCalledWith(
      expect.stringContaining('已入库'),
    );
    expect(state.gridApi.query).toHaveBeenCalled();
  });

  it('版本与任务面板展示状态与失败原因（可读映射）', async () => {
    const wrapper = mount(Documents);
    await flushPromises();

    const versions = wrapper
      .findAll('[data-action]')
      .find((action) => action.attributes('data-action') === '版本');
    await versions?.trigger('click');
    await flushPromises();
    expect(getDocumentVersionPage).toHaveBeenCalled();

    const tasks = wrapper
      .findAll('[data-action]')
      .find((action) => action.attributes('data-action') === '任务');
    await tasks?.trigger('click');
    await flushPromises();
    expect(getIngestionTaskPage).toHaveBeenCalled();
    // 失败原因以可读文案展示（embed-upstream_failed → 嵌入调用失败）
    expect(wrapper.text()).toContain('嵌入调用失败');
  });

  it('写操作按钮都带知识库权限码', async () => {
    const wrapper = mount(Documents);
    await flushPromises();
    const auths = wrapper
      .findAll('[data-action]')
      .map((action) => action.attributes('data-auth'));
    expect(auths).toContain('ai:knowledge:ingest');
    expect(auths).toContain('ai:knowledge:delete');
    expect(auths).toContain('ai:knowledge:version');
    expect(auths).toContain('ai:knowledge:query');
  });
});
