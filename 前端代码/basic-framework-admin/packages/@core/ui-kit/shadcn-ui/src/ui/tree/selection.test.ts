import { describe, expect, it } from 'vitest';

import { getEnabledParentKeys } from './selection';

describe('getEnabledParentKeys', () => {
  it('keeps enabled parents and excludes disabled or missing nodes', () => {
    const entries = [
      { id: 1, value: { disabled: false } },
      { id: 2, value: { disabled: true } },
      { id: 'nested', value: { state: { locked: true } } },
    ];

    expect(
      getEnabledParentKeys([1, 2, 'nested', 404], entries, 'disabled'),
    ).toEqual([1, 'nested']);
    expect(
      getEnabledParentKeys([1, 2, 'nested'], entries, 'state.locked'),
    ).toEqual([1, 2]);
  });
});
