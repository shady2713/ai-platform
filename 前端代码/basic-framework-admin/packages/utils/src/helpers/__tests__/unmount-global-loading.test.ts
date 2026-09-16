import { afterEach, describe, expect, it, vi } from 'vitest';

import { unmountGlobalLoading } from '../unmount-global-loading';

describe('unmountGlobalLoading', () => {
  afterEach(() => {
    vi.useRealTimers();
    document.body.innerHTML = '';
  });

  it('does nothing when the startup loading element is absent', () => {
    document.body.innerHTML = '<style data-app-loading="inject-css"></style>';

    expect(() => unmountGlobalLoading()).not.toThrow();
    expect(document.querySelector('[data-app-loading]')).not.toBeNull();
  });

  it('removes the loading element and injected assets after the transition', () => {
    vi.useFakeTimers();
    document.body.innerHTML = `
      <style data-app-loading="inject-css"></style>
      <script data-app-loading="inject-js"></script>
      <div id="__app-loading__"></div>
    `;

    unmountGlobalLoading();
    const loading = document.querySelector('#__app-loading__');
    expect(loading?.classList.contains('hidden')).toBe(true);

    loading?.dispatchEvent(new Event('transitionend'));

    expect(document.querySelector('#__app-loading__')).toBeNull();
    expect(document.querySelectorAll('[data-app-loading]')).toHaveLength(0);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('falls back to timed cleanup when no transition event is emitted', () => {
    vi.useFakeTimers();
    document.body.innerHTML = `
      <style data-app-loading="inject-css"></style>
      <div id="__app-loading__"></div>
    `;

    unmountGlobalLoading();
    vi.advanceTimersByTime(999);
    expect(document.querySelector('#__app-loading__')).not.toBeNull();

    vi.advanceTimersByTime(1);
    expect(document.querySelector('#__app-loading__')).toBeNull();
    expect(document.querySelector('[data-app-loading]')).toBeNull();
  });
});
