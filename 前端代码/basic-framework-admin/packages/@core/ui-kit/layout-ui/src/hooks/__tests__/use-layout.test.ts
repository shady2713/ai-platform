import type { VbenLayoutProps } from '../../admin-layout';

import { describe, expect, it } from 'vitest';

import { useLayout } from '../use-layout';

function props(isMobile: boolean, layout?: string): VbenLayoutProps {
  return { isMobile, layout: layout as VbenLayoutProps['layout'] };
}

describe('useLayout', () => {
  it('forces sidebar-nav on mobile regardless of layout', () => {
    const { currentLayout, isFullContent, isSidebarMixedNav, isHeaderNav } =
      useLayout(props(true, 'mixed-nav'));

    expect(currentLayout.value).toBe('sidebar-nav');
    expect(isFullContent.value).toBe(false);
    expect(isSidebarMixedNav.value).toBe(false);
    expect(isHeaderNav.value).toBe(false);
  });

  it('detects full-content layout', () => {
    const { currentLayout, isFullContent } = useLayout(
      props(false, 'full-content'),
    );
    expect(currentLayout.value).toBe('full-content');
    expect(isFullContent.value).toBe(true);
    expect(useLayout(props(false, 'full-content')).isMixedNav.value).toBe(
      false,
    );
  });

  it('detects sidebar-mixed-nav layout', () => {
    const { isSidebarMixedNav } = useLayout(props(false, 'sidebar-mixed-nav'));
    expect(isSidebarMixedNav.value).toBe(true);
  });

  it('detects header-nav layout', () => {
    const { isHeaderNav, isMixedNav, isHeaderMixedNav } = useLayout(
      props(false, 'header-nav'),
    );
    expect(isHeaderNav.value).toBe(true);
    expect(isMixedNav.value).toBe(false);
    expect(isHeaderMixedNav.value).toBe(false);
  });

  it('detects mixed-nav and legacy header-sidebar-nav layouts', () => {
    expect(useLayout(props(false, 'mixed-nav')).isMixedNav.value).toBe(true);
    expect(useLayout(props(false, 'header-sidebar-nav')).isMixedNav.value).toBe(
      true,
    );
  });

  it('detects header-mixed-nav layout', () => {
    const { isHeaderMixedNav, isMixedNav } = useLayout(
      props(false, 'header-mixed-nav'),
    );
    expect(isHeaderMixedNav.value).toBe(true);
    expect(isMixedNav.value).toBe(false);
  });

  it('treats the default sidebar-nav layout as non-special', () => {
    const { isFullContent, isHeaderNav, isMixedNav, isSidebarMixedNav } =
      useLayout(props(false, 'sidebar-nav'));
    expect(isFullContent.value).toBe(false);
    expect(isHeaderNav.value).toBe(false);
    expect(isMixedNav.value).toBe(false);
    expect(isSidebarMixedNav.value).toBe(false);
  });
});
