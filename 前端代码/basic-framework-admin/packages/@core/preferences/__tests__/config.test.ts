import { describe, expect, it } from 'vitest';

import { defaultPreferences } from '../src/config';

describe('defaultPreferences immutability test', () => {
  it('disables language and timezone widgets by default', () => {
    expect(defaultPreferences.widget.languageToggle).toBe(false);
    expect(defaultPreferences.widget.timezone).toBe(false);
  });

  it('should not modify the config object', () => {
    expect(defaultPreferences).toMatchSnapshot();
  });
});
