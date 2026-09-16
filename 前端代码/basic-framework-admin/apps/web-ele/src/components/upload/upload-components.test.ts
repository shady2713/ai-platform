import type {
  UploadFile,
  UploadRawFile,
  UploadRequestOptions,
} from 'element-plus';

import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick } from 'vue';

import {
  afterAll,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from 'vitest';

let FileUpload: (typeof import('./file-upload.vue'))['default'];
let ImageUpload: (typeof import('./image-upload.vue'))['default'];

const mocks = vi.hoisted(() => ({
  logError: vi.fn(),
  openWindow: vi.fn(),
  showError: vi.fn(),
  showErrorMessage: vi.fn(),
  showSuccessMessage: vi.fn(),
}));

vi.mock('@vben/icons', () => ({
  IconifyIcon: defineComponent({
    name: 'IconifyIcon',
    setup: () => () => h('i'),
  }),
}));
vi.mock('@vben/locales', async () => {
  const actual =
    await vi.importActual<typeof import('@vben/locales')>('@vben/locales');
  return { ...actual, $t: (key: string) => key };
});
vi.mock('@vben/utils', async () => {
  const actual =
    await vi.importActual<typeof import('@vben/utils')>('@vben/utils');
  return {
    ...actual,
    logError: mocks.logError,
    openWindow: mocks.openWindow,
  };
});
vi.mock('#/utils/feedback', () => ({
  showError: mocks.showError,
  showErrorMessage: mocks.showErrorMessage,
  showSuccessMessage: mocks.showSuccessMessage,
}));
vi.mock('element-plus', () => {
  const ElUpload = defineComponent({
    name: 'ElUpload',
    inheritAttrs: false,
    props: {
      accept: { default: undefined, type: String },
      beforeUpload: { default: undefined, type: Function },
      disabled: Boolean,
      drag: Boolean,
      fileList: { default: () => [], type: Array },
      httpRequest: { default: undefined, type: Function },
      limit: { default: undefined, type: Number },
      listType: { default: undefined, type: String },
      multiple: Boolean,
      onExceed: { default: undefined, type: Function },
      onPreview: { default: undefined, type: Function },
      onRemove: { default: undefined, type: Function },
    },
    emits: ['update:fileList'],
    setup(_props, { attrs, slots }) {
      return () => h('section', attrs, slots.default?.());
    },
  });
  const ElButton = defineComponent({
    name: 'ElButton',
    setup(_props, { attrs, slots }) {
      return () => h('button', attrs, slots.default?.());
    },
  });
  const ElDialog = defineComponent({
    name: 'ElDialog',
    props: {
      modelValue: Boolean,
      title: { default: undefined, type: String },
      width: { default: undefined, type: String },
    },
    emits: ['update:modelValue', 'close'],
    setup(_props, { attrs, slots }) {
      return () => h('aside', attrs, slots.default?.());
    },
  });
  return { ElButton, ElDialog, ElUpload };
});

function rawFile(name: string, uid: number, type: string): UploadRawFile {
  return Object.assign(new File(['content'], name, { type }), { uid });
}

function requestOptions(file: UploadRawFile): UploadRequestOptions {
  return {
    action: '',
    data: {},
    file,
    filename: 'file',
    headers: {},
    method: 'post',
    onError: vi.fn(),
    onProgress: vi.fn(),
    onSuccess: vi.fn(),
    withCredentials: false,
  };
}

function uploadProps(wrapper: ReturnType<typeof mount>) {
  return wrapper.findComponent({ name: 'ElUpload' }).props() as {
    beforeUpload: (file: UploadRawFile) => boolean;
    fileList: UploadFile[];
    httpRequest: (options: UploadRequestOptions) => Promise<void>;
    onPreview: (file: UploadFile) => Promise<void> | void;
    onRemove: (file: UploadFile) => void;
  };
}

beforeAll(async () => {
  vi.stubEnv('VITE_GLOB_API_URL', '/api');
  [{ default: FileUpload }, { default: ImageUpload }] = await Promise.all([
    import('./file-upload.vue'),
    import('./image-upload.vue'),
  ]);
}, 30_000);

beforeEach(() => {
  vi.clearAllMocks();
});

afterAll(() => {
  vi.unstubAllEnvs();
});

describe('upload components', () => {
  it('uploads, emits model changes, removes files, and blocks unsafe previews', async () => {
    const api = vi.fn().mockResolvedValue({ url: '/report.txt' });
    const wrapper = mount(FileUpload, {
      props: { accept: ['txt'], api, maxNumber: 2, value: [] },
    });
    const props = uploadProps(wrapper);
    const file = rawFile('report.txt', 1, 'text/plain');
    const request = requestOptions(file);

    expect(props.beforeUpload(file)).toBe(true);
    await props.httpRequest(request);
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([
      ['/report.txt'],
    ]);
    expect(request.onSuccess).toHaveBeenCalledOnce();

    props.onPreview({
      name: 'unsafe.txt',
      status: 'success',
      uid: 9,
      url: 'javascript:alert(1)',
    });
    expect(mocks.openWindow).not.toHaveBeenCalled();
    expect(mocks.showErrorMessage).toHaveBeenCalledWith(
      'ui.upload.previewUnavailable',
    );

    const uploaded = uploadProps(wrapper).fileList[0];
    if (!uploaded) throw new Error('expected uploaded file');
    props.onPreview(uploaded);
    expect(mocks.openWindow).toHaveBeenCalledWith('/report.txt');
    props.onRemove(uploaded);
    expect(wrapper.emitted('delete')?.[0]).toEqual([uploaded]);
  });

  it('previews only safe image urls and exposes an accessible dialog image', async () => {
    const api = vi.fn().mockResolvedValue({ url: '/avatar.png' });
    const wrapper = mount(ImageUpload, {
      props: { accept: ['png'], api, value: '' },
    });
    const props = uploadProps(wrapper);
    const file = rawFile('avatar.png', 2, 'image/png');

    expect(props.beforeUpload(file)).toBe(true);
    await props.httpRequest(requestOptions(file));
    const uploaded = uploadProps(wrapper).fileList[0];
    if (!uploaded) throw new Error('expected uploaded image');
    await props.onPreview(uploaded);
    await nextTick();

    const dialog = wrapper.findComponent({ name: 'ElDialog' });
    expect(dialog.props('modelValue')).toBe(true);
    expect(wrapper.find('img').attributes()).toMatchObject({
      alt: 'avatar.png',
      src: '/avatar.png',
    });

    await props.onPreview({ name: 'missing', status: 'success', uid: 3 });
    expect(mocks.showErrorMessage).toHaveBeenCalledWith(
      'ui.upload.previewUnavailable',
    );
    dialog.vm.$emit('close');
    await nextTick();
    expect(wrapper.find('img').exists()).toBe(false);
  });
});
