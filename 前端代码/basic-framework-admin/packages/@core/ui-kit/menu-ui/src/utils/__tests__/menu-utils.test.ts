import type { ComponentInternalInstance } from 'vue';

import { h } from 'vue';

import { describe, expect, it } from 'vitest';

import { findComponentUpward, flattedChildren } from '../index';

function instanceWithParents(names: (string | undefined)[]): {
  instance: ComponentInternalInstance;
  nodes: unknown[];
} {
  interface ParentNode {
    parent: null | ParentNode;
    type: { name: string | undefined };
  }
  const nodes: ParentNode[] = [];
  let current: null | ParentNode = null;
  for (const name of names) {
    const node: ParentNode = { type: { name }, parent: current };
    nodes.push(node);
    current = node;
  }
  return {
    instance: { parent: current } as ComponentInternalInstance,
    nodes,
  };
}

describe('findComponentUpward', () => {
  it('returns undefined when no parent chain exists', () => {
    const { instance } = instanceWithParents([]);
    expect(findComponentUpward(instance, ['Menu'])).toBeNull();
  });

  it('returns undefined when no matching parent name is found', () => {
    const { instance } = instanceWithParents(['SubMenu', 'Wrapper']);
    expect(findComponentUpward(instance, ['Menu'])).toBeNull();
  });

  it('returns the nearest matching ancestor', () => {
    const { instance, nodes } = instanceWithParents([
      'MenuItem',
      'SubMenu',
      'Menu',
    ]);
    expect(findComponentUpward(instance, ['Menu', 'SubMenu'])).toBe(nodes[2]);
  });

  it('ignores parents without a name', () => {
    const { instance, nodes } = instanceWithParents([undefined, 'Menu']);
    expect(findComponentUpward(instance, ['Menu'])).toBe(nodes[1]);
  });
});

describe('flattedChildren', () => {
  it('flattens nested arrays of atoms', () => {
    const nested = ['a', ['b', 'c']] as unknown as Parameters<
      typeof flattedChildren
    >[0];
    expect(flattedChildren(nested)).toEqual(['a', 'b', 'c']);
  });

  it('passes through a flat list', () => {
    const input = ['x', 'y'];
    expect(flattedChildren(input)).toEqual(['x', 'y']);
  });

  it('flattens vnode children arrays recursively', () => {
    // createVNode normalizes nested arrays away, so the recursion is
    // exercised with a flat children array.
    const root = h('div', [h('span', 'a'), h('i', 'b')]);
    const result = flattedChildren(root);
    const types = result.map((child) => (child as { type?: string }).type);
    expect(types).toEqual(['span', 'i']);
  });

  it('descends into a mounted component sub tree', () => {
    const subTree = h('em', 'leaf');
    const mountedComponentVNode = {
      __v_isVNode: true,
      component: { subTree },
    } as unknown as import('vue').VNode;
    const result = flattedChildren(mountedComponentVNode);
    expect(result[0]).toBe(mountedComponentVNode);
    expect(result.includes(subTree)).toBe(true);
  });
});
