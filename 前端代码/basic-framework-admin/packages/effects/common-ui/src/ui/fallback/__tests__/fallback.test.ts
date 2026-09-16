import type { FallbackProps } from '../fallback';

import { describe, expect, it } from 'vitest';

// Side-effect import executes the type-only fallback contract module.
import '../fallback';

describe('fallback contract types', () => {
  it('fallbackProps accepts the built-in status values', () => {
    const props: FallbackProps = { status: '404', homePath: '/' };
    expect(props.status).toBe('404');
    expect(props.homePath).toBe('/');
  });
});
