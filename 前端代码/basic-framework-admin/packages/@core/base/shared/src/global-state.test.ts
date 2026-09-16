import { defineComponent } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import { globalShareState } from './global-state';

describe('globalShareState', () => {
  afterEach(() => {
    globalShareState.defineMessage({});
    globalShareState.setComponents({});
  });

  it('stores the shared message callbacks', () => {
    const copyPreferencesSuccess = vi.fn();

    globalShareState.defineMessage({ copyPreferencesSuccess });
    globalShareState.getMessage().copyPreferencesSuccess?.('title', 'content');

    expect(copyPreferencesSuccess).toHaveBeenCalledWith('title', 'content');
  });

  it('keeps only defined component registrations', () => {
    const AvailableComponent = defineComponent({ name: 'AvailableComponent' });

    globalShareState.setComponents({
      AvailableComponent,
      MissingComponent: undefined,
    });

    expect(globalShareState.getComponents()).toEqual({ AvailableComponent });
  });
});
