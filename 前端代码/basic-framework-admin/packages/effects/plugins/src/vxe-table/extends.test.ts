import type { VxeUIExport } from 'vxe-table';

import type { VxeTableGridOptions } from './types';

import { describe, expect, it, vi } from 'vitest';

import { extendProxyOptions, extendsDefaultFormatter } from './extends';

interface Row {
  id: number;
}

async function invokeQuery(
  query: unknown,
  ...args: unknown[]
): Promise<unknown> {
  if (typeof query !== 'function') throw new TypeError('query is unavailable');
  return await query(...args);
}

describe('vXE proxy extensions', () => {
  it('adds current form values to query methods and preserves extra arguments', async () => {
    const query = vi.fn(async (...args: unknown[]) => args);
    const queryAll = vi.fn(async (...args: unknown[]) => args);
    const updates: Array<Partial<{ gridOptions: VxeTableGridOptions<Row> }>> =
      [];
    const api = { setState: vi.fn((state) => updates.push(state)) };
    const options = {
      proxyConfig: { ajax: { query, queryAll } },
    } as unknown as VxeTableGridOptions<Row>;

    extendProxyOptions(api, options, () => ({ keyword: 'new', page: 2 }));

    expect(api.setState).toHaveBeenCalledTimes(2);
    const wrappedQuery = updates[0]?.gridOptions?.proxyConfig?.ajax?.query;
    const wrappedQueryAll =
      updates[1]?.gridOptions?.proxyConfig?.ajax?.queryAll;
    await invokeQuery(
      wrappedQuery,
      { page: { currentPage: 1 } },
      { keyword: 'old', tenantId: 3 },
      'extra',
    );
    await invokeQuery(wrappedQueryAll, { sort: [] }, 12);

    expect(query).toHaveBeenCalledWith(
      { page: { currentPage: 1 } },
      { keyword: 'new', page: 2, tenantId: 3 },
      'extra',
    );
    expect(queryAll).toHaveBeenCalledWith(
      { sort: [] },
      { keyword: 'new', page: 2 },
    );
  });

  it('ignores missing proxy queries and event-like custom values', async () => {
    const query = vi.fn(async (...args: unknown[]) => args);
    const updates: Array<Partial<{ gridOptions: VxeTableGridOptions<Row> }>> =
      [];
    const api = { setState: vi.fn((state) => updates.push(state)) };

    extendProxyOptions(api, {} as VxeTableGridOptions<Row>, () => ({}));
    expect(api.setState).not.toHaveBeenCalled();

    extendProxyOptions(
      api,
      { proxyConfig: { ajax: { query } } } as VxeTableGridOptions<Row>,
      () => ({ active: true }),
    );
    const wrappedQuery = updates[0]?.gridOptions?.proxyConfig?.ajax?.query;
    await invokeQuery(wrappedQuery, {}, 'not-an-object');

    expect(query).toHaveBeenCalledWith({}, { active: true });
  });
});

describe('vXE default formatters', () => {
  it('registers date formatters and rejects unsupported values', () => {
    const formatters = new Map<
      string,
      { tableCellFormatMethod: (params: { cellValue: unknown }) => string }
    >();
    const vxeUI = {
      formats: {
        add: vi.fn((name: string, formatter) => {
          formatters.set(name, formatter);
        }),
      },
    } as unknown as VxeUIExport;

    extendsDefaultFormatter(vxeUI);

    expect(vxeUI.formats.add).toHaveBeenCalledTimes(2);
    expect(
      formatters.get('formatDate')?.tableCellFormatMethod({
        cellValue: '2026-08-30T10:20:30',
      }),
    ).toMatch(/^2026-08-30$/);
    expect(
      formatters.get('formatDateTime')?.tableCellFormatMethod({
        cellValue: new Date(2026, 7, 30, 10, 20, 30),
      }),
    ).toMatch(/^2026-08-30 10:20:30$/);
    expect(
      formatters.get('formatDate')?.tableCellFormatMethod({ cellValue: {} }),
    ).toBe('');
  });
});
