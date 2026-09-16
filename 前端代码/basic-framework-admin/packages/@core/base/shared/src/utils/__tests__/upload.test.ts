import { describe, expect, it } from 'vitest';

import {
  checkFileType,
  defaultFileAccepts,
  defaultImageAccepts,
} from '../upload';

describe('upload utilities', () => {
  it('keeps default file types aligned with the server allowlist', () => {
    expect(defaultImageAccepts).toEqual([
      'bmp',
      'gif',
      'jpeg',
      'jpg',
      'png',
      'webp',
    ]);
    expect(defaultImageAccepts).not.toContain('svg');
    expect(defaultFileAccepts).toEqual([
      ...defaultImageAccepts,
      'pdf',
      'doc',
      'docx',
      'xls',
      'xlsx',
      'ppt',
      'pptx',
      'txt',
      'zip',
    ]);
  });

  it('matches extensions without treating configuration as a regular expression', () => {
    const file = new File(['content'], 'REPORT.Final.PDF', {
      type: 'application/pdf',
    });

    expect(checkFileType(file, ['pdf'])).toBe(true);
    expect(checkFileType(file, ['.PDF'])).toBe(true);
    expect(checkFileType(file, ['[pdf'])).toBe(false);
    expect(checkFileType(file, [''])).toBe(false);
    expect(checkFileType(file, [])).toBe(true);
  });

  it('matches exact and wildcard mime types', () => {
    const image = new File(['image'], 'avatar.bin', { type: 'image/png' });

    expect(checkFileType(image, ['image/png'])).toBe(true);
    expect(checkFileType(image, ['image/*'])).toBe(true);
    expect(checkFileType(image, ['application/*'])).toBe(false);
  });
});
