import { describe, expect, it } from 'vitest';

import { selectPublicRuntimeConfig } from './extra-app-config';

describe('public runtime configuration', () => {
  it('exposes only explicitly public runtime keys', () => {
    expect(
      selectPublicRuntimeConfig({
        PRIVATE_TOKEN: 'must-not-reach-the-browser',
        VITE_APP_TITLE: 'Admin',
        VITE_GLOB_API_URL: '/admin-api',
      }),
    ).toEqual({ VITE_GLOB_API_URL: '/admin-api' });
  });

  it('fails the build when the required API endpoint is absent', () => {
    expect(() =>
      selectPublicRuntimeConfig({ VITE_APP_TITLE: 'Admin' }),
    ).toThrow('Missing public runtime config: VITE_GLOB_API_URL');
  });
});
