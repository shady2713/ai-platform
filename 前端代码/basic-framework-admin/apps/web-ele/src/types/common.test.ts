import { describe, expect, expectTypeOf, it } from 'vitest';

import * as common from './common';

describe('common types contract', () => {
  it('compiles to no runtime exports, matching the types-only policy', () => {
    // 该文件只有类型声明，运行时不应暴露任何值
    expect(typeof common).toBe('object');
    expect(Object.keys(common)).toHaveLength(0);
  });

  it('keeps the generic tree node and query shapes stable', () => {
    const node: common.TreeNodeData = {
      children: [{ id: 2, name: '研发部' }],
      id: 1,
      label: '总裁办',
    };
    expect(node.children?.[0]?.name).toBe('研发部');

    const query: common.DatabaseTableQueryParams = {
      comment: '用户表',
      name: 'system_user',
    };
    expect(query.name).toBe('system_user');

    type Page = { pageNo: number; pageSize: number };
    expectTypeOf<common.ExportParams>().toMatchTypeOf<Page>();
    expectTypeOf<common.TreeNodeData['id']>().toBeNumber();
    expectTypeOf<common.TreeNodeData>().toMatchTypeOf<{ id: number }>();
  });
});
