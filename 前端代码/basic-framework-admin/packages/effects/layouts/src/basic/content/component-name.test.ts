import { createVNode } from 'vue';

import { describe, expect, it } from 'vitest';

import { setVNodeComponentName } from './component-name';

describe('setVNodeComponentName', () => {
  it('adds the route name to an unnamed object component', () => {
    const component: { name?: string } = {};
    const vnode = createVNode(component);

    expect(setVNodeComponentName(vnode, 'Dashboard')).toBe(vnode);
    expect(component.name).toBe('Dashboard');
  });

  it('preserves an existing component name', () => {
    const component = { name: 'ExistingName' };

    setVNodeComponentName(createVNode(component), 'ReplacementName');

    expect(component.name).toBe('ExistingName');
  });

  it('leaves native elements unchanged', () => {
    const vnode = createVNode('section');

    expect(setVNodeComponentName(vnode, 'NativeElement')).toBe(vnode);
    expect(vnode.type).toBe('section');
  });
});
