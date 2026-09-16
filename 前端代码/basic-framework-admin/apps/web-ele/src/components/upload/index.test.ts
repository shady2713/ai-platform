import { describe, expect, it, vi } from 'vitest';

import { FileUpload, ImageUpload } from './index';

vi.mock('@vben/icons', () => ({
  IconifyIcon: { name: 'IconifyIcon' },
}));

vi.mock('@vben/locales', () => ({
  $t: (key: string) => key,
}));

vi.mock('@vben/utils', () => ({
  checkFileType: vi.fn(),
  defaultFileAccepts: '.jpg,.png',
  defaultImageAccepts: '.jpg,.png,.gif,.webp',
  logError: vi.fn(),
  openWindow: vi.fn(),
}));

vi.mock('element-plus', async () => {
  const { defineComponent, h } = await import('vue');
  const slotOnly = (name: string) =>
    defineComponent({
      name,
      setup(_props, { slots }) {
        return () => h('div', slots.default?.());
      },
    });
  return {
    ElButton: slotOnly('ElButton'),
    ElDialog: slotOnly('ElDialog'),
    ElUpload: slotOnly('ElUpload'),
  };
});

vi.mock('#/utils/feedback', () => ({
  showError: vi.fn(),
  showErrorMessage: vi.fn(),
}));

vi.mock('./upload-security', () => ({
  isSafeUploadUrl: vi.fn(() => true),
}));

vi.mock('./use-upload', () => ({
  useUploadType: vi.fn(() => ({ api: undefined, mode: 'input' })),
}));

vi.mock('./use-upload-list-state', () => ({
  useUploadListState: vi.fn(() => ({
    list: [],
    updateList: vi.fn(),
  })),
}));

describe('upload index contract', () => {
  it('re-exports both upload components under their declared names', () => {
    expect(FileUpload).toBeDefined();
    expect((FileUpload as { name?: string }).name).toBe('FileUpload');
    expect(ImageUpload).toBeDefined();
    expect((ImageUpload as { name?: string }).name).toBe('ImageUpload');
  });
});
