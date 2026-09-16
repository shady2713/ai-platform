import type { UploadFile } from 'element-plus';

import { describe, expect, it, vi } from 'vitest';

import {
  getUploadModelValue,
  normalizeUploadFileList,
  removeUploadFile,
  removeUploadFileByUid,
  upsertUploadedFile,
} from './upload-list';

vi.mock('./use-upload', () => ({ useUpload: vi.fn() }));

function uploaded(response: unknown, uid = 1): UploadFile {
  return {
    name: 'file',
    response,
    status: 'success',
    uid,
  };
}

describe('upload list', () => {
  it('normalizes scalar, comma-separated, array, and numeric models', () => {
    expect(normalizeUploadFileList(undefined, 1)).toEqual([]);
    expect(normalizeUploadFileList('', 1)).toEqual([]);
    expect(normalizeUploadFileList('/a.pdf,/b.pdf', 5)).toHaveLength(2);
    expect(normalizeUploadFileList(['/a.pdf', 42], 5)).toMatchObject([
      { name: 'a.pdf', response: '/a.pdf', url: '/a.pdf' },
      { name: '42', response: 42, url: undefined },
    ]);
    expect(normalizeUploadFileList('javascript:alert(1)', 1)[0]?.url).toBe(
      undefined,
    );
  });

  it('preserves the configured model shape and validates scalar results', () => {
    const files = [uploaded('/a.pdf'), uploaded(42, 2)];
    expect(getUploadModelValue(files, { maxNumber: 1 })).toBe('/a.pdf');
    expect(getUploadModelValue(files, { maxNumber: 5, value: [] })).toEqual([
      '/a.pdf',
      42,
    ]);
    expect(getUploadModelValue(files, { maxNumber: 5, value: '' })).toBe(
      '/a.pdf,42',
    );
    expect(() =>
      getUploadModelValue([uploaded({ id: 1 })], { maxNumber: 1 }),
    ).toThrow('字符串或数字');
  });

  it('upserts by uid, requires safe image urls, and removes predictably', () => {
    const list: UploadFile[] = [{ name: 'pending', status: 'ready', uid: 7 }];
    const file = Object.assign(new File(['image'], 'avatar.png'), { uid: 7 });

    upsertUploadedFile(list, file, { url: '/avatar.png' }, true);
    expect(list).toMatchObject([
      { name: 'avatar.png', status: 'success', url: '/avatar.png', uid: 7 },
    ]);
    expect(() =>
      upsertUploadedFile(list, file, { url: 'javascript:alert(1)' }, true),
    ).toThrow('安全的图片 URL');

    removeUploadFile(list, list[0] as UploadFile);
    expect(list).toEqual([]);
    removeUploadFileByUid(list, 999);
    expect(list).toEqual([]);
  });
});
