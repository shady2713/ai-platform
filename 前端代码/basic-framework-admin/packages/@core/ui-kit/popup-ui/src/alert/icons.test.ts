import type { Component } from 'vue';

import { isVNode } from 'vue';

import { describe, expect, it } from 'vitest';

import { resolveAlertIcon } from './icons';

describe('resolveAlertIcon', () => {
  it('preserves custom components and handles an omitted icon', () => {
    const component = { name: 'CustomAlertIcon' } as Component;

    expect(resolveAlertIcon(component)).toBe(component);
    expect(resolveAlertIcon(undefined)).toBeNull();
  });

  it.each(['error', 'info', 'success', 'warning'] as const)(
    'creates a styled vnode for %s',
    (icon) => {
      expect(isVNode(resolveAlertIcon(icon))).toBe(true);
    },
  );

  it('supports the question component and rejects unknown icon names', () => {
    expect(resolveAlertIcon('question')).not.toBeNull();
    expect(() => resolveAlertIcon('unsupported' as unknown as 'info')).toThrow(
      'Unsupported alert icon: unsupported',
    );
  });
});
