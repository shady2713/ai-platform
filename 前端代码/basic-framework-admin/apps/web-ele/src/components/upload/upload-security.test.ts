import { describe, expect, it } from 'vitest';

import {
  assertUploadConfiguration,
  isAllowedUploadFile,
  isSafeUploadUrl,
} from './upload-security';

describe('upload security', () => {
  it('fails loud for invalid limits', () => {
    expect(() => assertUploadConfiguration(1, 2)).not.toThrow();
    expect(() => assertUploadConfiguration(Infinity, 0.1)).not.toThrow();
    expect(() => assertUploadConfiguration(0, 2)).toThrow('maxNumber');
    expect(() => assertUploadConfiguration(1.5, 2)).toThrow('maxNumber');
    expect(() => assertUploadConfiguration(1, 0)).toThrow('maxSize');
    expect(() => assertUploadConfiguration(1, Infinity)).toThrow('maxSize');
  });

  it('requires an allowed extension and an image mime when present', () => {
    const image = new File(['image'], 'avatar.png', { type: 'image/png' });
    const disguised = new File(['pdf'], 'avatar.png', {
      type: 'application/pdf',
    });
    const unknownMime = new File(['image'], 'avatar.png');

    expect(isAllowedUploadFile(image, ['png'], true)).toBe(true);
    expect(isAllowedUploadFile(disguised, ['png'], true)).toBe(false);
    expect(isAllowedUploadFile(unknownMime, ['png'], true)).toBe(true);
    expect(isAllowedUploadFile(image, ['jpg'], false)).toBe(false);
  });

  it('accepts preview-safe urls and rejects executable schemes', () => {
    expect(isSafeUploadUrl('/files/report.pdf')).toBe(true);
    expect(isSafeUploadUrl('https://cdn.example/file.png')).toBe(true);
    expect(isSafeUploadUrl('blob:https://example.test/id')).toBe(true);
    expect(isSafeUploadUrl('javascript:alert(1)')).toBe(false);
    expect(isSafeUploadUrl('file:///etc/passwd')).toBe(false);
    expect(isSafeUploadUrl('data:image/svg+xml;base64,PHN2Zz4=')).toBe(false);
    expect(isSafeUploadUrl('data:image/png;base64,YQ==')).toBe(false);
    expect(isSafeUploadUrl('data:image/png;base64,YQ==', true)).toBe(true);
    expect(isSafeUploadUrl('')).toBe(false);
  });
});
