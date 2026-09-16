import { describe, expect, it } from 'vitest';

import * as vxeTable from './index';

describe('vxe-table plugin barrel', () => {
  it('exposes the setup and grid composition entry points', () => {
    expect(vxeTable.setupVbenVxeTable).toBeTypeOf('function');
    expect(vxeTable.useVbenVxeGrid).toBeTypeOf('function');
    expect(vxeTable.VbenVxeGrid).toBeDefined();
  });

  it('exposes lazily-loaded vxe components for standalone usage', () => {
    expect(vxeTable.AsyncVxeTable).toBeTypeOf('object');
    expect(vxeTable.AsyncVxeColumn).toBeTypeOf('object');
  });

  it('exposes the shared validation helpers', () => {
    expect(vxeTable.createRequiredValidation).toBeTypeOf('function');
  });
});
