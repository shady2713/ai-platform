import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { openWindow } from '../window';

describe('openWindow', () => {
  let originalOpen: typeof window.open;

  beforeEach(() => {
    originalOpen = window.open;
  });

  afterEach(() => {
    window.open = originalOpen;
  });

  it('should call window.open with correct arguments', () => {
    const url = 'https://example.com';
    const options = { noopener: true, noreferrer: true, target: '_blank' };

    window.open = vi.fn();

    openWindow(url, options);

    expect(window.open).toHaveBeenCalledWith(
      url,
      options.target,
      'noopener=yes,noreferrer=yes',
    );
  });

  it.each(['data:text/html,<script>alert(1)</script>', 'javascript:alert(1)'])(
    'rejects executable protocol %s',
    (url) => {
      window.open = vi.fn();

      expect(() => openWindow(url)).toThrow('Unsupported URL protocol.');
      expect(window.open).not.toHaveBeenCalled();
    },
  );
});
