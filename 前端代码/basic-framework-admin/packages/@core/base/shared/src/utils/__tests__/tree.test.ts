import { describe, expect, it, vi } from 'vitest';

import {
  filterTree,
  handleTree,
  mapTree,
  sortTree,
  traverseTreeValues,
} from '../tree';

describe('traverseTreeValues', () => {
  interface Node {
    children?: Node[];
    name: string;
  }

  type NodeValue = string;

  const sampleTree: Node[] = [
    {
      name: 'A',
      children: [
        { name: 'B' },
        {
          name: 'C',
          children: [{ name: 'D' }, { name: 'E' }],
        },
      ],
    },
    {
      name: 'F',
      children: [
        { name: 'G' },
        {
          name: 'H',
          children: [{ name: 'I' }],
        },
      ],
    },
  ];

  it('traverses tree and returns all node values', () => {
    const values = traverseTreeValues<Node, NodeValue>(
      sampleTree,
      (node) => node.name,
      {
        childProps: 'children',
      },
    );
    expect(values).toEqual(['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I']);
  });

  it('handles empty tree', () => {
    const values = traverseTreeValues<Node, NodeValue>([], (node) => node.name);
    expect(values).toEqual([]);
  });

  it('handles tree with only root node', () => {
    const rootNode = { name: 'A' };
    const values = traverseTreeValues<Node, NodeValue>(
      [rootNode],
      (node) => node.name,
    );
    expect(values).toEqual(['A']);
  });

  it('handles tree with only leaf nodes', () => {
    const leafNodes = [{ name: 'A' }, { name: 'B' }, { name: 'C' }];
    const values = traverseTreeValues<Node, NodeValue>(
      leafNodes,
      (node) => node.name,
    );
    expect(values).toEqual(['A', 'B', 'C']);
  });

  it('preserves valid falsy values', () => {
    const values = traverseTreeValues(
      [{ value: 0 }, { value: false }, { value: '' }],
      (node) => node.value,
    );
    expect(values).toEqual([0, false, '']);
  });
});

describe('filterTree', () => {
  const tree = [
    {
      id: 1,
      children: [
        { id: 2 },
        { id: 3, children: [{ id: 4 }, { id: 5 }, { id: 6 }] },
        { id: 7 },
      ],
    },
    { id: 8, children: [{ id: 9 }, { id: 10 }] },
    { id: 11 },
  ];

  it('should return all nodes when condition is always true', () => {
    const result = filterTree(tree, () => true, { childProps: 'children' });
    expect(result).toEqual(tree);
  });

  it('should return only root nodes when condition is always false', () => {
    const result = filterTree(tree, () => false);
    expect(result).toEqual([]);
  });

  it('should return nodes with even id values', () => {
    const original = structuredClone(tree);
    const result = filterTree(tree, (node) => node.id % 2 === 0);
    expect(result).toEqual([{ id: 8, children: [{ id: 10 }] }]);
    expect(tree).toEqual(original);
  });

  it('should return nodes with odd id values and their ancestors', () => {
    const result = filterTree(tree, (node) => node.id % 2 === 1);
    expect(result).toEqual([
      {
        id: 1,
        children: [{ id: 3, children: [{ id: 5 }] }, { id: 7 }],
      },
      { id: 11 },
    ]);
  });

  it('should return nodes with "leaf" in their name', () => {
    const tree = [
      {
        name: 'root',
        children: [
          { name: 'leaf 1' },
          {
            name: 'branch',
            children: [{ name: 'leaf 2' }, { name: 'leaf 3' }],
          },
          { name: 'leaf 4' },
        ],
      },
    ];
    const result = filterTree(
      tree,
      (node) => node.name.includes('leaf') || node.name === 'root',
    );
    expect(result).toEqual([
      {
        name: 'root',
        children: [{ name: 'leaf 1' }, { name: 'leaf 4' }],
      },
    ]);
  });
});

describe('mapTree', () => {
  it('map infinite depth tree using mapTree', () => {
    const tree = [
      {
        id: 1,
        name: 'node1',
        children: [
          { id: 2, name: 'node2' },
          { id: 3, name: 'node3' },
          {
            id: 4,
            name: 'node4',
            children: [
              {
                id: 5,
                name: 'node5',
                children: [
                  { id: 6, name: 'node6' },
                  { id: 7, name: 'node7' },
                ],
              },
              { id: 8, name: 'node8' },
            ],
          },
        ],
      },
    ];
    const newTree = mapTree(tree, (node) => ({
      ...node,
      name: `${node.name}-new`,
    }));

    expect(newTree).toEqual([
      {
        id: 1,
        name: 'node1-new',
        children: [
          { id: 2, name: 'node2-new' },
          { id: 3, name: 'node3-new' },
          {
            id: 4,
            name: 'node4-new',
            children: [
              {
                id: 5,
                name: 'node5-new',
                children: [
                  { id: 6, name: 'node6-new' },
                  { id: 7, name: 'node7-new' },
                ],
              },
              { id: 8, name: 'node8-new' },
            ],
          },
        ],
      },
    ]);
  });

  it('supports a custom children property without mutating the source', () => {
    const tree = [{ id: 1, nodes: [{ id: 2 }] }];
    const result = mapTree(
      tree,
      (node) => ({ label: `node-${node.id}`, nodes: node.nodes }),
      { childProps: 'nodes' },
    );

    expect(result).toEqual([
      { label: 'node-1', nodes: [{ label: 'node-2', nodes: undefined }] },
    ]);
    expect(tree).toEqual([{ id: 1, nodes: [{ id: 2 }] }]);
  });
});

describe('handleTree', () => {
  it('builds a tree with custom fields and keeps input unchanged', () => {
    const source = [
      { key: 2, parentKey: 1, title: 'child' },
      { key: 1, parentKey: 0, title: 'root' },
      { key: 3, parentKey: 99, title: 'orphan' },
    ];

    const result = handleTree(source, 'key', 'parentKey', 'nodes');

    expect(result).toEqual([
      {
        key: 1,
        nodes: [{ key: 2, parentKey: 1, title: 'child' }],
        parentKey: 0,
        title: 'root',
      },
      { key: 3, parentKey: 99, title: 'orphan' },
    ]);
    expect(source.every((node) => !('nodes' in node))).toBe(true);
  });

  it('rejects a non-array value at the runtime boundary', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);

    expect(handleTree({ id: 1 } as unknown as object[])).toEqual([]);
    expect(warn).toHaveBeenCalledWith('data must be an array');

    warn.mockRestore();
  });

  it('does not create a cyclic tree when identifiers are duplicated', () => {
    const result = handleTree([
      { id: 1, parentId: 0 },
      { id: 2, parentId: 1 },
      { id: 1, parentId: 2 },
    ]);

    expect(result).toEqual([
      {
        children: [
          {
            children: [{ id: 1, parentId: 2 }],
            id: 2,
            parentId: 1,
          },
        ],
        id: 1,
        parentId: 0,
      },
    ]);
  });
});

describe('sortTree', () => {
  it('sorts every level without mutating the source', () => {
    const source = [{ id: 2 }, { children: [{ id: 3 }, { id: 1 }], id: 1 }];

    const result = sortTree(source, (left, right) => left.id - right.id);

    expect(result).toEqual([
      { children: [{ id: 1 }, { id: 3 }], id: 1 },
      { id: 2 },
    ]);
    expect(source).toEqual([
      { id: 2 },
      { children: [{ id: 3 }, { id: 1 }], id: 1 },
    ]);
  });
});
