import type { VxeGridInstance } from 'vxe-table';

import type { ExtendedFormApi } from '@vben-core/form-ui';

import { describe, expect, it, vi } from 'vitest';

import { VxeGridApi } from './api';

interface Row {
  id: number;
}

function createMountedApi() {
  const commitProxy = vi.fn(async () => undefined);
  const grid = { commitProxy } as unknown as VxeGridInstance<Row>;
  const formApi = { getValues: vi.fn() } as unknown as ExtendedFormApi;
  const api = new VxeGridApi<Row>();
  api.mount(grid, formApi);
  return { api, commitProxy, formApi, grid };
}

describe('vxeGridApi', () => {
  it('starts with framework defaults and fails loud before mounting', async () => {
    const api = new VxeGridApi<Row>({ showSearchForm: false });

    expect(api.state).toMatchObject({
      class: '',
      formOptions: undefined,
      gridClass: '',
      gridEvents: {},
      gridOptions: {},
      showSearchForm: false,
    });
    expect(() => api.grid).toThrow('grid is unavailable');
    expect(() => api.formApi).toThrow('form API is unavailable');
    await expect(api.query()).rejects.toThrow('grid is unavailable');
  });

  it('mounts once and delegates query and reload parameters', async () => {
    const { api, commitProxy, formApi, grid } = createMountedApi();
    const replacement = {
      commitProxy: vi.fn(),
    } as unknown as VxeGridInstance<Row>;

    api.mount(replacement, {} as ExtendedFormApi);
    await api.query({ keyword: 'one' });
    await api.reload({ keyword: 'two' });

    expect(api.grid).toBe(grid);
    expect(api.formApi).toBe(formApi);
    expect(commitProxy).toHaveBeenNthCalledWith(1, 'query', {
      keyword: 'one',
    });
    expect(commitProxy).toHaveBeenNthCalledWith(2, 'reload', {
      keyword: 'two',
    });
  });

  it('updates options and search visibility through its store', () => {
    const api = new VxeGridApi<Row>();

    api.setGridOptions({ height: 500 });
    api.setLoading(true);
    api.setState((state) => ({ class: state.class ? '' : 'ready' }));

    expect(api.state?.gridOptions).toMatchObject({
      height: 500,
      loading: true,
    });
    expect(api.state?.class).toBe('ready');
    expect(api.toggleSearchForm()).toBe(false);
    expect(api.toggleSearchForm(true)).toBe(true);
  });

  it('propagates proxy failures and clears mounted instances', async () => {
    const { api, commitProxy } = createMountedApi();
    commitProxy.mockRejectedValueOnce(new Error('proxy failed'));

    await expect(api.query()).rejects.toThrow('proxy failed');
    api.unmount();

    expect(() => api.grid).toThrow('grid is unavailable');
    expect(() => api.formApi).toThrow('form API is unavailable');
  });
});
