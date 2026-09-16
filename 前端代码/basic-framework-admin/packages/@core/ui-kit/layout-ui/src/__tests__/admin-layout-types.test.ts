import type { VbenLayoutProps } from '../admin-layout';

import { describe, expect, it } from 'vitest';

// Side-effect import executes the type-only VbenLayoutProps contract.
import '../admin-layout';

describe('admin-layout contract types', () => {
  it('accepts the documented layout props', () => {
    const props: VbenLayoutProps = {
      contentCompact: 'compact',
      layout: 'sidebar-mixed-nav',
      sidebarCollapse: true,
      sidebarTheme: 'dark',
    };
    expect(props.layout).toBe('sidebar-mixed-nav');
    expect(props.sidebarCollapse).toBe(true);
    expect(props.sidebarTheme).toBe('dark');
    expect(props.contentCompact).toBe('compact');
  });
});
