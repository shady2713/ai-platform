import type { TreeKey, TreeNode } from './types';

import { get } from '@vben-core/shared/utils';

export interface FlattenedTreeNode {
  hasChildren: boolean;
  id: TreeKey;
  level: number;
  parentId: null | TreeKey;
  parents: TreeKey[];
  value: TreeNode;
}

interface TreeFieldContract {
  childrenField: string;
  valueField: string;
}

export function getTreeNodeKey(item: TreeNode, valueField: string): TreeKey {
  const key = get(item, valueField);
  if (typeof key !== 'number' && typeof key !== 'string') {
    throw new TypeError(
      `Tree node field "${valueField}" must be a string or number`,
    );
  }
  if (typeof key === 'number' && !Number.isFinite(key)) {
    throw new TypeError(
      `Tree node field "${valueField}" must be finite when it is a number`,
    );
  }
  return key;
}

export function getTreeNodeChildren(
  item: TreeNode,
  childrenField: string,
): TreeNode[] | undefined {
  const children = get(item, childrenField);
  if (children === null || children === undefined) return undefined;
  if (
    !Array.isArray(children) ||
    children.some(
      (child) =>
        typeof child !== 'object' || child === null || Array.isArray(child),
    )
  ) {
    throw new TypeError(
      `Tree node field "${childrenField}" must be an array of objects`,
    );
  }
  return children as TreeNode[];
}

function formatLocation(
  indices: readonly number[],
  childrenField: string,
): string {
  let location = 'treeData';
  for (const [depth, index] of indices.entries()) {
    location += depth === 0 ? `[${index}]` : `.${childrenField}[${index}]`;
  }
  return location;
}

export function flattenTree(
  items: readonly TreeNode[],
  contract: TreeFieldContract,
): FlattenedTreeNode[] {
  const result: FlattenedTreeNode[] = [];
  const locationsByNormalizedKey = new Map<string, string>();

  function visit(
    nodes: readonly TreeNode[],
    level: number,
    parentId: null | TreeKey,
    parents: readonly TreeKey[],
    parentIndices: readonly number[],
  ) {
    nodes.forEach((item, index) => {
      const indices = [...parentIndices, index];
      const location = formatLocation(indices, contract.childrenField);
      const id = getTreeNodeKey(item, contract.valueField);
      const normalizedKey = String(id);
      const firstLocation = locationsByNormalizedKey.get(normalizedKey);
      if (firstLocation !== undefined) {
        throw new TypeError(
          `Tree node field "${contract.valueField}" must be globally unique after string normalization; duplicate key ${JSON.stringify(normalizedKey)} at ${location} (first at ${firstLocation})`,
        );
      }
      locationsByNormalizedKey.set(normalizedKey, location);

      const children = getTreeNodeChildren(item, contract.childrenField);
      result.push({
        hasChildren: Boolean(children?.length),
        id,
        level,
        parentId,
        parents: [...parents],
        value: item,
      });
      if (children?.length) {
        visit(children, level + 1, id, [...parents, id], indices);
      }
    });
  }

  visit(items, 0, null, [], []);
  return result;
}
