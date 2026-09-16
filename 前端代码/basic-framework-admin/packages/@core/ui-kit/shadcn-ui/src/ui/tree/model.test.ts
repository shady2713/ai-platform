import { describe, expect, it } from 'vitest';

import { flattenTree } from './model';

const contract = {
  childrenField: 'children',
  valueField: 'value',
};

describe('flattenTree', () => {
  it('flattens nested nodes while preserving ancestry', () => {
    const child = { value: 'child' };
    const root = { children: [child], value: 1 };

    expect(flattenTree([root, { value: 2 }], contract)).toEqual([
      {
        hasChildren: true,
        id: 1,
        level: 0,
        parentId: null,
        parents: [],
        value: root,
      },
      {
        hasChildren: false,
        id: 'child',
        level: 1,
        parentId: 1,
        parents: [1],
        value: child,
      },
      {
        hasChildren: false,
        id: 2,
        level: 0,
        parentId: null,
        parents: [],
        value: { value: 2 },
      },
    ]);
  });

  it('rejects duplicate keys across different branches with locations', () => {
    const tree = [
      { children: [{ value: 'duplicate' }], value: 'first-root' },
      { children: [{ value: 'duplicate' }], value: 'second-root' },
    ];

    expect(() => flattenTree(tree, contract)).toThrow(
      'duplicate key "duplicate" at treeData[1].children[0] (first at treeData[0].children[0])',
    );
  });

  it('rejects numeric and string keys that collide in the UI layer', () => {
    expect(() => flattenTree([{ value: 1 }, { value: '1' }], contract)).toThrow(
      'globally unique after string normalization; duplicate key "1"',
    );
  });

  it('revalidates the current data on every invocation', () => {
    const tree: Array<{ value: number }> = [{ value: 1 }, { value: 2 }];
    expect(flattenTree(tree, contract)).toHaveLength(2);
    const second = tree[1];
    if (!second) throw new Error('Expected a second tree node');

    second.value = 1;
    expect(() => flattenTree(tree, contract)).toThrow('duplicate key "1"');

    second.value = 3;
    expect(flattenTree(tree, contract).map(({ id }) => id)).toEqual([1, 3]);
  });

  it('rejects malformed key and children contracts', () => {
    expect(() => flattenTree([{ value: null }], contract)).toThrow(
      'field "value" must be a string or number',
    );
    expect(() =>
      flattenTree([{ children: ['invalid'], value: 'root' }], contract),
    ).toThrow('field "children" must be an array of objects');
  });

  it.each([Number.NaN, Number.NEGATIVE_INFINITY, Number.POSITIVE_INFINITY])(
    'rejects the non-finite numeric key %s',
    (value) => {
      expect(() => flattenTree([{ value }], contract)).toThrow(
        'field "value" must be finite when it is a number',
      );
    },
  );
});
