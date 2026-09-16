interface TreeConfigOptions {
  // 子属性的名称，默认为'children'
  childProps: string;
}

function readProperty(node: object, property: string): unknown {
  return (node as Record<string, unknown>)[property];
}

function setProperty(node: object, property: string, value: unknown): void {
  (node as Record<string, unknown>)[property] = value;
}

function readChildren<T extends object>(
  node: T,
  childProps: string,
): T[] | undefined {
  const children = readProperty(node, childProps);
  return Array.isArray(children) ? (children as T[]) : undefined;
}

/**
 * @zh_CN 遍历树形结构，并返回所有节点中指定的值。
 * @param tree 树形结构数组
 * @param getValue 获取节点值的函数
 * @param options 作为子节点数组的可选属性名称。
 * @returns 所有节点中指定的值的数组
 */
function traverseTreeValues<T extends object, V>(
  tree: T[],
  getValue: (node: T) => V,
  options?: TreeConfigOptions,
): V[] {
  const result: V[] = [];
  const { childProps } = options || {
    childProps: 'children',
  };

  const dfs = (treeNode: T) => {
    const value = getValue(treeNode);
    result.push(value);
    const children = readChildren(treeNode, childProps);
    if (!children) {
      return;
    }
    for (const child of children) {
      dfs(child);
    }
  };

  for (const treeNode of tree) {
    dfs(treeNode);
  }
  return result;
}

/**
 * 根据条件过滤给定树结构的节点，并以原有顺序返回所有匹配节点的数组。
 * @param tree 要过滤的树结构的根节点数组。
 * @param filter 用于匹配每个节点的条件。
 * @param options 作为子节点数组的可选属性名称。
 * @returns 包含所有匹配节点的数组。
 */
function filterTree<T extends object>(
  tree: T[],
  filter: (node: T) => boolean,
  options?: TreeConfigOptions,
): T[] {
  const { childProps } = options || {
    childProps: 'children',
  };

  const filterNodes = (nodes: T[]): T[] => {
    return nodes.flatMap((node) => {
      if (!filter(node)) {
        return [];
      }
      const children = readChildren(node, childProps);
      return [
        children
          ? ({ ...node, [childProps]: filterNodes(children) } as T)
          : ({ ...node } as T),
      ];
    });
  };

  return filterNodes(tree);
}

/**
 * 根据条件重新映射给定树结构的节
 * @param tree 要过滤的树结构的根节点数组。
 * @param mapper 用于map每个节点的条件。
 * @param options 作为子节点数组的可选属性名称。
 */
function mapTree<T extends object, V extends object>(
  tree: T[],
  mapper: (node: T) => V,
  options?: TreeConfigOptions,
): V[] {
  const { childProps } = options || {
    childProps: 'children',
  };
  return tree.map((node) => {
    const mappedNode = mapper(node);
    // 映射器可以通过返回空 children 剪枝；递归前其子节点仍须保持源节点结构。
    const children = readProperty(mappedNode, childProps);
    const sourceChildren = Array.isArray(children) ? (children as T[]) : null;
    return sourceChildren
      ? ({
          ...mappedNode,
          [childProps]: mapTree(sourceChildren, mapper, options),
        } as V)
      : mappedNode;
  });
}

/**
 * 构造树型结构数据
 *
 * @param {*} data 数据源
 * @param {*} id id字段 默认 'id'
 * @param {*} parentId 父节点字段 默认 'parentId'
 * @param {*} children 孩子节点字段 默认 'children'
 */
function handleTree<T extends object>(
  data: T[],
  id: string = 'id',
  parentId: string = 'parentId',
  children: string = 'children',
): T[] {
  if (!Array.isArray(data)) {
    console.warn('data must be an array');
    return [];
  }
  const config = {
    id,
    parentId,
    childrenList: children,
  };
  const childrenListMap = new Map<unknown, T[]>();
  const nodeIds = new Set<unknown>();
  const tree: T[] = [];
  const nodes = data.map((node) => ({ ...node }) as T);

  // 1. 数据预处理
  // 1.1 第一次遍历，生成 childrenListMap 和 nodeIds 映射
  for (const node of nodes) {
    const parentNodeId = readProperty(node, config.parentId);
    const siblings = childrenListMap.get(parentNodeId) ?? [];
    siblings.push(node);
    childrenListMap.set(parentNodeId, siblings);
    nodeIds.add(readProperty(node, config.id));
  }
  // 1.2 第二次遍历，找出根节点
  for (const node of nodes) {
    const parentNodeId = readProperty(node, config.parentId);
    if (!nodeIds.has(parentNodeId)) {
      tree.push(node);
    }
  }

  // 2. 构建树结：递归构建子节点
  const visited = new Set<T>();
  const adaptToChildrenList = (node: T): void => {
    visited.add(node);
    const childrenNodes = childrenListMap.get(readProperty(node, config.id));
    if (!childrenNodes || childrenNodes.length === 0) {
      return;
    }
    const unvisitedChildren = childrenNodes.filter(
      (child) => !visited.has(child),
    );
    if (unvisitedChildren.length === 0) {
      return;
    }
    setProperty(node, config.childrenList, unvisitedChildren);
    for (const child of unvisitedChildren) {
      adaptToChildrenList(child);
    }
  };

  // 3. 从根节点开始构建完整树
  for (const rootNode of tree) {
    adaptToChildrenList(rootNode);
  }

  return tree;
}

/**
 * 对树形结构数据进行递归排序
 * @param treeData - 树形数据数组
 * @param sortFunction - 排序函数，用于定义排序规则
 * @param options - 配置选项，包括子节点属性名
 * @returns 排序后的树形数据
 */
function sortTree<T extends object>(
  treeData: T[],
  sortFunction: (a: T, b: T) => number,
  options?: TreeConfigOptions,
): T[] {
  const { childProps } = options || {
    childProps: 'children',
  };

  return treeData.toSorted(sortFunction).map((item) => {
    const children = readChildren(item, childProps);
    if (children && children.length > 0) {
      return {
        ...item,
        [childProps]: sortTree(children, sortFunction, options),
      } as T;
    }
    return item;
  });
}

export { filterTree, handleTree, mapTree, sortTree, traverseTreeValues };
