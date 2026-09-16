import type { VxeGridProps as NativeGridOptions } from 'vxe-table';

import { describe, expect, it, vi } from 'vitest';

import {
  buildGridOptions,
  buildToolbarOptions,
  getDelegatedFormSlots,
  getDelegatedGridSlots,
  getSeparatorBackground,
  shouldShowDefaultEmpty,
  shouldShowSeparator,
} from './grid-options';

describe('vXE grid option helpers', () => {
  it.each([
    [false, true, undefined],
    [true, false, undefined],
    [true, true, false],
    [true, true, { show: false }],
  ])(
    'hides the form separator when its contract is disabled',
    (hasForm, showSearchForm, separator) => {
      expect(shouldShowSeparator(hasForm, showSearchForm, separator)).toBe(
        false,
      );
    },
  );

  it.each([undefined, true, { backgroundColor: '#fff' }, { show: true }])(
    'shows the form separator for enabled configurations',
    (separator) => {
      expect(shouldShowSeparator(true, true, separator)).toBe(true);
    },
  );

  it('returns a background only for object separator options', () => {
    expect(getSeparatorBackground({ backgroundColor: '#123456' })).toBe(
      '#123456',
    );
    expect(getSeparatorBackground(true)).toBeUndefined();
    expect(getSeparatorBackground(undefined)).toBeUndefined();
  });

  it('disables an unused toolbar and keeps valid configured tools', () => {
    const result = buildToolbarOptions({
      gridOptions: {
        toolbarConfig: { tools: [{ code: 'refresh' }, undefined] },
      },
      hasActionSlot: false,
      hasForm: false,
      hasToolSlot: false,
      showSearchForm: true,
      showTableTitle: false,
      showToolbar: false,
      translate: vi.fn((key: string) => key),
    });

    expect(result.toolbarConfig).toEqual({
      enabled: false,
      tools: [{ code: 'refresh' }],
    });
  });

  it.each([
    [true, 'primary', 'common.hideSearchPanel'],
    [false, undefined, 'common.showSearchPanel'],
  ])(
    'adds a search tool with current visibility state',
    (showSearchForm, status, title) => {
      const translate = vi.fn((key: string) => key);
      const result = buildToolbarOptions({
        gridOptions: { toolbarConfig: { search: true } },
        hasActionSlot: true,
        hasForm: true,
        hasToolSlot: true,
        showSearchForm,
        showTableTitle: false,
        showToolbar: true,
        translate,
      });

      expect(result.toolbarConfig?.slots).toEqual({
        buttons: 'toolbar-actions',
        tools: 'toolbar-tools',
      });
      expect(result.toolbarConfig?.tools).toContainEqual({
        circle: true,
        code: 'search',
        icon: 'vxe-icon-search',
        status,
        title,
      });
      expect(translate).toHaveBeenCalledWith(title);
    },
  );

  it('uses the title as the toolbar action slot trigger without search', () => {
    const result = buildToolbarOptions({
      gridOptions: undefined,
      hasActionSlot: false,
      hasForm: true,
      hasToolSlot: false,
      showSearchForm: undefined,
      showTableTitle: true,
      showToolbar: true,
      translate: (key) => key,
    });

    expect(result.toolbarConfig).toEqual({
      slots: { buttons: 'toolbar-actions' },
      tools: [],
    });
  });

  it('supports a toolbar containing only the consumer tool slot', () => {
    const result = buildToolbarOptions({
      gridOptions: undefined,
      hasActionSlot: false,
      hasForm: false,
      hasToolSlot: true,
      showSearchForm: undefined,
      showTableTitle: false,
      showToolbar: true,
      translate: (key) => key,
    });

    expect(result.toolbarConfig?.slots).toEqual({ tools: 'toolbar-tools' });
  });

  it('normalizes proxy, pager and form options without mutating inputs', () => {
    const globalOptions: NativeGridOptions = {
      pagerConfig: { pageSize: 10 },
      proxyConfig: {},
      size: 'small',
    };
    const localOptions = {
      formConfig: { enabled: true },
      pagerConfig: { pageSize: 50 },
      proxyConfig: { ajax: {} },
    };
    const result = buildGridOptions(
      globalOptions,
      localOptions,
      { toolbarConfig: { enabled: true } },
      false,
    );

    expect(result).toMatchObject({
      formConfig: { enabled: false },
      pagerConfig: {
        background: true,
        layouts: [
          'Total',
          'Sizes',
          'Home',
          'PrevJump',
          'PrevPage',
          'Number',
          'NextPage',
          'NextJump',
          'End',
        ],
        pageSize: 50,
        size: 'mini',
      },
      proxyConfig: { autoLoad: false, enabled: true },
      size: 'small',
      toolbarConfig: { enabled: true },
    });
    expect(localOptions).toEqual({
      formConfig: { enabled: true },
      pagerConfig: { pageSize: 50 },
      proxyConfig: { ajax: {} },
    });
  });

  it('uses mobile pagination and disables a proxy without ajax', () => {
    const result = buildGridOptions(
      {},
      { pagerConfig: {}, proxyConfig: {} },
      {},
      true,
    );

    expect(result.pagerConfig?.layouts).toEqual([
      'PrevJump',
      'PrevPage',
      'Number',
      'NextPage',
      'NextJump',
    ]);
    expect(result.proxyConfig).toMatchObject({
      autoLoad: false,
      enabled: false,
    });
  });

  it('leaves optional grid capabilities absent when unconfigured', () => {
    expect(buildGridOptions({}, undefined, {}, false)).toEqual({});
  });

  it('delegates only consumer-owned grid and form slots', () => {
    const slots = [
      'empty',
      'form',
      'loading',
      'toolbar-actions',
      'toolbar-tools',
      'cell-name',
      'form-department',
      'form-user',
    ];

    expect(getDelegatedGridSlots(slots)).toEqual([
      'cell-name',
      'form-department',
      'form-user',
    ]);
    expect(getDelegatedFormSlots(slots)).toEqual(['department', 'user']);
  });

  it('uses the framework empty state only when VXE has no override', () => {
    expect(shouldShowDefaultEmpty({})).toBe(true);
    expect(shouldShowDefaultEmpty({ emptyText: '' })).toBe(false);
    expect(shouldShowDefaultEmpty({ emptyRender: {} })).toBe(false);
  });
});
