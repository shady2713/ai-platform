import type { SystemUserApi } from '#/api/system/user';

import { mount } from '@vue/test-utils';

import { downloadFileFromBlobPart } from '@vben/utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { importUser, importUserTemplate } from '#/api/system/user';
import { showAlertDialog, showSuccessMessage } from '#/utils/feedback';

import ImportForm from './import-form.vue';

interface ImportUserValues {
  file: File;
  updateSupport: boolean;
}

interface ModalConfig {
  onConfirm: () => Promise<void>;
}

interface FakeUploadFile {
  name: string;
  raw?: File;
}

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn<() => Promise<ImportUserValues>>(),
    setFieldValue: vi.fn(),
    validate: vi.fn<() => Promise<{ valid: boolean }>>(),
  },
  modalApi: {
    close: vi.fn(() => Promise.resolve()),
    lock: vi.fn(),
    unlock: vi.fn(),
  },
  modalConfig: undefined as ModalConfig | undefined,
  pendingUploadFile: undefined as FakeUploadFile | undefined,
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
            return () =>
              h('section', [slots.default?.(), slots['prepend-footer']?.()]);
          },
        }),
        state.modalApi,
      ];
    }),
  };
});

vi.mock('@vben/utils', () => ({
  downloadFileFromBlobPart: vi.fn(),
}));

vi.mock('element-plus', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    ElButton: defineComponent({
      name: 'ElButton',
      setup(_props, { slots }) {
        return () => h('button', slots.default?.());
      },
    }),
    ElUpload: defineComponent({
      name: 'ElUpload',
      props: {
        onChange: { type: Function, required: true },
      },
      setup(props, { slots }) {
        return () =>
          h('div', [
            h(
              'button',
              {
                'data-test': 'upload-change',
                onClick: () =>
                  state.pendingUploadFile &&
                  props.onChange(state.pendingUploadFile),
              },
              '模拟选择文件',
            ),
            slots.default?.(),
          ]);
      },
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
          return () => h('form', slots.file?.({}));
        },
      }),
      state.formApi,
    ]),
  };
});

vi.mock('#/api/system/user', () => ({
  importUser: vi.fn(),
  importUserTemplate: vi.fn(),
}));

vi.mock('#/locales', () => ({
  $t: (key: string) => key,
}));

vi.mock('#/utils/feedback', () => ({
  showAlertDialog: vi.fn(),
  showSuccessMessage: vi.fn(),
}));

vi.mock('../data', () => ({
  useImportFormSchema: vi.fn(() => []),
}));

function modalConfig() {
  if (!state.modalConfig) {
    throw new Error('弹窗配置未初始化');
  }
  return state.modalConfig;
}

function mountForm() {
  return mount(ImportForm);
}

function buttonByText(wrapper: ReturnType<typeof mountForm>, text: string) {
  const button = wrapper
    .findAll('button')
    .find((candidate) => candidate.text().trim() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  return button;
}

describe('system user import form', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.pendingUploadFile = undefined;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.formApi.getValues.mockResolvedValue({
      file: new File(['x'], 'users.xlsx'),
      updateSupport: true,
    });
    vi.mocked(importUser).mockResolvedValue({
      createUsernames: ['zhangsan'],
      failureUsernames: {},
      updateUsernames: [],
    });
    vi.mocked(importUserTemplate).mockResolvedValue(new Blob(['template']));
  });

  it('stores the selected raw file into the form', async () => {
    const raw = new File(['x'], 'users.xlsx');
    state.pendingUploadFile = { name: 'users.xlsx', raw };
    const wrapper = mountForm();

    await wrapper.find('[data-test="upload-change"]').trigger('click');
    expect(state.formApi.setFieldValue).toHaveBeenCalledWith('file', raw);

    state.pendingUploadFile = { name: 'cleared.xlsx' };
    await wrapper.find('[data-test="upload-change"]').trigger('click');
    expect(state.formApi.setFieldValue).toHaveBeenCalledTimes(1);
  });

  it('downloads the import template', async () => {
    const wrapper = mountForm();

    await buttonByText(wrapper, '下载导入模板').trigger('click');

    expect(importUserTemplate).toHaveBeenCalledOnce();
    expect(downloadFileFromBlobPart).toHaveBeenCalledWith({
      fileName: '用户导入模板.xls',
      source: expect.any(Blob),
    });
  });

  it('imports the file and shows a success summary without failures', async () => {
    const wrapper = mountForm();
    const file = new File(['x'], 'users.xlsx');
    state.formApi.getValues.mockResolvedValue({ file, updateSupport: true });

    await modalConfig().onConfirm();

    expect(importUser).toHaveBeenCalledWith(file, true);
    expect(state.modalApi.close).toHaveBeenCalledOnce();
    expect(wrapper.emitted('success')).toHaveLength(1);
    expect(showSuccessMessage).toHaveBeenCalledWith(
      'ui.actionMessage.operationSuccess：新增 1 个，更新 0 个',
    );
    expect(showAlertDialog).not.toHaveBeenCalled();
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();
  });

  it('shows an alert dialog with escaped failure details', async () => {
    vi.mocked(importUser).mockResolvedValue({
      createUsernames: ['zhangsan'],
      failureUsernames: { '<script>"&\'': '账号已存在' },
      updateUsernames: ['lisi'],
    } as SystemUserApi.UserImportResp);
    const wrapper = mountForm();

    await modalConfig().onConfirm();

    expect(state.modalApi.close).toHaveBeenCalledOnce();
    expect(wrapper.emitted('success')).toHaveLength(1);
    expect(showAlertDialog).toHaveBeenCalledWith(
      expect.stringContaining('新增 1 个，更新 1 个，失败 1 个'),
      '导入结果',
      {
        confirmButtonText: '确定',
        dangerouslyUseHTMLString: true,
        type: 'warning',
      },
    );
    const html = vi.mocked(showAlertDialog).mock.calls[0]?.[0] ?? '';
    expect(html).toContain('&lt;script&gt;&quot;&amp;&#39;');
    expect(html).not.toContain('<script>');
    expect(showSuccessMessage).not.toHaveBeenCalled();
  });

  it('treats an empty import result as zero counts', async () => {
    vi.mocked(importUser).mockResolvedValue(
      undefined as unknown as SystemUserApi.UserImportResp,
    );
    mountForm();

    await modalConfig().onConfirm();

    expect(showSuccessMessage).toHaveBeenCalledWith(
      'ui.actionMessage.operationSuccess：新增 0 个，更新 0 个',
    );
  });

  it('skips submission when validation fails', async () => {
    state.formApi.validate.mockResolvedValue({ valid: false });
    mountForm();

    await modalConfig().onConfirm();

    expect(importUser).not.toHaveBeenCalled();
    expect(state.modalApi.lock).not.toHaveBeenCalled();
  });

  it('keeps the modal open and unlocks it when the import fails', async () => {
    vi.mocked(importUser).mockRejectedValue(new Error('request failed'));
    mountForm();

    await expect(modalConfig().onConfirm()).rejects.toThrow('request failed');

    expect(state.modalApi.close).not.toHaveBeenCalled();
    expect(showSuccessMessage).not.toHaveBeenCalled();
    expect(showAlertDialog).not.toHaveBeenCalled();
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();
  });
});
