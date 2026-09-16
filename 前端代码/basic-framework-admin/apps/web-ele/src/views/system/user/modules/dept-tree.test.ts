import type { SystemDeptApi } from '#/api/system/dept';

import { flushPromises, mount } from '@vue/test-utils';

import { handleTree, logError } from '@vben/utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { getSimpleDeptList } from '#/api/system/dept';

import DeptTree from './dept-tree.vue';

const state = vi.hoisted(() => ({
  handleTree: vi.fn((value: unknown) => value),
}));

vi.mock('@vben/icons', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    Search: defineComponent({
      name: 'SearchIcon',
      setup() {
        return () => h('span', { 'data-test': 'search-icon' });
      },
    }),
  };
});

vi.mock('@vben/utils', () => ({
  handleTree: vi.fn((value: unknown) => state.handleTree(value)),
  logError: vi.fn(),
}));

vi.mock('element-plus', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    ElInput: defineComponent({
      name: 'ElInput',
      emits: ['input'],
      setup(_props, { emit, slots }) {
        return () =>
          h('div', [
            h('input', {
              'data-test': 'dept-search',
              onInput: (event: Event) =>
                emit('input', (event.target as HTMLInputElement).value),
            }),
            slots.prefix?.(),
          ]);
      },
    }),
    ElTree: defineComponent({
      name: 'ElTree',
      props: { data: { type: Array, default: () => [] } },
      emits: ['node-click'],
      template: `
        <div data-test="dept-tree">
          <button
            v-for="node in data"
            :key="node.id"
            data-test="dept-node"
            @click="$emit('node-click', node)"
          >
            {{ node.name }}
          </button>
        </div>
      `,
    }),
  };
});

vi.mock('#/api/system/dept', () => ({
  getSimpleDeptList: vi.fn(),
}));

function department(id: number, name: string): SystemDeptApi.Dept {
  return {
    createTime: new Date('2026-09-01T00:00:00Z'),
    email: '',
    id,
    leaderUserId: null,
    name,
    phone: '',
    sort: id,
    status: 0,
  };
}

function mountTree() {
  return mount(DeptTree, {
    global: { directives: { loading: () => undefined } },
  });
}

function treeData(wrapper: ReturnType<typeof mountTree>) {
  const tree = wrapper.findComponent({ name: 'ElTree' });
  if (!tree.exists()) {
    return undefined;
  }
  return tree.props('data') as SystemDeptApi.Dept[];
}

describe('system user dept tree', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.handleTree.mockImplementation((value: unknown) => value);
    vi.mocked(getSimpleDeptList).mockResolvedValue([
      department(1, '总部'),
      department(2, '研发部'),
      department(3, 'QA组'),
    ]);
  });

  it('loads the department list and renders the tree', async () => {
    const wrapper = mountTree();
    await flushPromises();

    expect(getSimpleDeptList).toHaveBeenCalledOnce();
    expect(handleTree).toHaveBeenCalledWith([
      department(1, '总部'),
      department(2, '研发部'),
      department(3, 'QA组'),
    ]);
    expect(treeData(wrapper)).toHaveLength(3);
    expect(wrapper.text()).not.toContain('暂无数据');
    expect(wrapper.find('[data-test="search-icon"]').exists()).toBe(true);
  });

  it('logs the error and shows the empty placeholder when loading fails', async () => {
    const error = new Error('request failed');
    vi.mocked(getSimpleDeptList).mockRejectedValue(error);
    const wrapper = mountTree();
    await flushPromises();

    expect(logError).toHaveBeenCalledWith('system:user:dept-tree:load', error);
    expect(treeData(wrapper)).toBeUndefined();
    expect(wrapper.text()).toContain('暂无数据');
  });

  it('filters departments case-insensitively and restores on clear', async () => {
    const wrapper = mountTree();
    await flushPromises();

    const search = wrapper.find('[data-test="dept-search"]');
    await search.setValue('qa');
    expect(treeData(wrapper)).toEqual([department(3, 'QA组')]);

    await search.setValue('研发');
    expect(treeData(wrapper)).toEqual([department(2, '研发部')]);

    await search.setValue('');
    expect(treeData(wrapper)).toHaveLength(3);
  });

  it('emits the selected department on node click', async () => {
    const wrapper = mountTree();
    await flushPromises();

    const node = wrapper
      .findAll('[data-test="dept-node"]')
      .find((candidate) => candidate.text() === '研发部');
    if (!node) {
      throw new Error('未找到部门节点：研发部');
    }
    await node.trigger('click');

    expect(wrapper.emitted('select')?.[0]?.[0]).toEqual(
      department(2, '研发部'),
    );
  });
});
