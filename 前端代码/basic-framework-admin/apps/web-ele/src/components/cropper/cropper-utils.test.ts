import { describe, expect, it } from 'vitest';

import { isImageFile, toCssDimension } from './cropper-utils';

describe('cropper utilities', () => {
  it('adds px only to numeric dimensions', () => {
    expect(toCssDimension(120)).toBe('120px');
    expect(toCssDimension('8rem')).toBe('8rem');
    expect(toCssDimension('50%')).toBe('50%');
  });

  it('accepts image MIME types and rejects missing or unrelated types', () => {
    expect(isImageFile({ type: 'image/png' })).toBe(true);
    expect(isImageFile({ type: 'image/svg+xml' })).toBe(true);
    expect(isImageFile({ type: 'application/pdf' })).toBe(false);
    expect(isImageFile({ type: '' })).toBe(false);
  });
});
