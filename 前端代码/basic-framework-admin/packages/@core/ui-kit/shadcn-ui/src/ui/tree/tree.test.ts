import { shallowMount } from '@vue/test-utils';

import { describe, expect, it, vi } from 'vitest';

import Tree from './tree.vue';

describe('vbenTree data contract', () => {
  it('rejects normalized key collisions introduced by a prop update', async () => {
    const wrapper = shallowMount(Tree, {
      props: {
        treeData: [{ value: 1 }, { value: 2 }],
      },
    });
    const warning = vi.spyOn(console, 'warn').mockImplementation(() => {});

    try {
      await expect(
        wrapper.setProps({ treeData: [{ value: 1 }, { value: '1' }] }),
      ).rejects.toThrow(
        'globally unique after string normalization; duplicate key "1"',
      );
      expect(
        warning.mock.calls.some(([message]) =>
          String(message).includes('watcher callback'),
        ),
      ).toBe(true);
    } finally {
      warning.mockRestore();
      wrapper.unmount();
    }
  });
});
