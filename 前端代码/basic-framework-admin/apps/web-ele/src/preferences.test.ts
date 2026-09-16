import { describe, expect, it } from 'vitest';

import { overridesPreferences } from './preferences';

describe('application preference overrides', () => {
  it('keeps backend routing, refresh rotation and product branding enabled', () => {
    expect(overridesPreferences).toMatchObject({
      app: {
        accessMode: 'backend',
        defaultHomePath: '/dashboard',
        enableRefreshToken: true,
        name: import.meta.env.VITE_APP_TITLE,
      },
      copyright: {
        companyName: import.meta.env.VITE_APP_TITLE,
        companySiteLink: '',
      },
      logo: {
        source: '/brand-logo.svg',
        sourceDark: '/brand-logo.svg',
      },
    });
  });
});
