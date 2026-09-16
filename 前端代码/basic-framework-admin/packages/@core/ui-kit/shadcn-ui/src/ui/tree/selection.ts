import type { TreeKey, TreeNode } from './types';

import { get } from '@vben-core/shared/utils';

interface TreeEntry {
  id: TreeKey;
  value: TreeNode;
}

export function getEnabledParentKeys(
  parentKeys: readonly TreeKey[],
  entries: readonly TreeEntry[],
  disabledField: string,
): TreeKey[] {
  const nodesByKey = new Map(entries.map(({ id, value }) => [id, value]));
  return parentKeys.filter((key) => {
    const node = nodesByKey.get(key);
    return node !== undefined && !get(node, disabledField);
  });
}
