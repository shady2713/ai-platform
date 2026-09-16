import type { VNode } from 'vue';

import { isVNode } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

const {
  decimalFormatterMock,
  fileSizeFormatterMock,
  minorUnitsFormatterMock,
  pastFormatterMock,
  setupVbenVxeTableMock,
} = vi.hoisted(() => ({
  decimalFormatterMock: vi.fn((value: unknown, digits: number) =>
    value === null || value === undefined
      ? ''
      : `number:${String(value)}:${digits}`,
  ),
  fileSizeFormatterMock: vi.fn(
    (value: unknown, digits: number) => `size:${String(value)}:${digits}`,
  ),
  minorUnitsFormatterMock: vi.fn((value: unknown) => Number(value) / 100),
  pastFormatterMock: vi.fn((value: unknown) => `past:${String(value)}`),
  setupVbenVxeTableMock: vi.fn(),
}));

vi.mock('@vben/plugins/vxe-table', () => ({
  AsyncVxeColumn: { name: 'AsyncVxeColumn' },
  AsyncVxeTable: { name: 'AsyncVxeTable' },
  createRequiredValidation: vi.fn(),
  setupVbenVxeTable: setupVbenVxeTableMock,
  useVbenVxeGrid: vi.fn(),
}));

vi.mock('@vben/icons', () => ({
  IconifyIcon: { name: 'IconifyIcon' },
}));

vi.mock('@vben/locales', () => ({
  $te: (key: string) => key === 'common.archive',
}));

vi.mock('@vben/utils', () => ({
  formatDecimal: decimalFormatterMock,
  formatFileSize: fileSizeFormatterMock,
  formatPast2: pastFormatterMock,
  isFunction: (value: unknown) => typeof value === 'function',
  isString: (value: unknown) => typeof value === 'string',
  minorUnitsToMajorUnits: minorUnitsFormatterMock,
}));

vi.mock('element-plus', () => ({
  ElButton: { name: 'ElButton' },
  ElImage: { name: 'ElImage' },
  ElPopconfirm: { name: 'ElPopconfirm' },
  ElSwitch: { name: 'ElSwitch' },
  ElTag: { name: 'ElTag' },
}));

vi.mock('#/components/dict-tag', () => ({
  DictTag: { name: 'DictTag' },
}));

vi.mock('#/components/table-action', () => ({}));

vi.mock('#/locales', () => ({
  $t: (key: string, params?: unknown[]) =>
    params?.length ? `${key}:${params.join(',')}` : key,
}));

vi.mock('./form', () => ({
  useVbenForm: vi.fn(),
}));

await import('./vxe-table');

type Renderer = {
  renderTableDefault: (...args: unknown[]) => unknown;
};

type Format = {
  tableCellFormatMethod: (
    params: { cellValue: unknown },
    digits?: number,
  ) => unknown;
};

type FakeVxeUi = {
  formats: { add: (name: string, format: Format) => void };
  renderer: { add: (name: string, renderer: Renderer) => void };
  setConfig: (config: Record<string, unknown>) => void;
};

type TableSetup = {
  configVxeTable: (vxeUi: FakeVxeUi) => void;
  useVbenForm: unknown;
};

const tableSetup = setupVbenVxeTableMock.mock.calls[0]?.[0] as TableSetup;

describe('vxe-table adapter', () => {
  const formats = new Map<string, Format>();
  const renderers = new Map<string, Renderer>();
  const setConfig = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
    formats.clear();
    renderers.clear();
    tableSetup.configVxeTable({
      formats: { add: (name, format) => formats.set(name, format) },
      renderer: { add: (name, renderer) => renderers.set(name, renderer) },
      setConfig,
    });
  });

  it('registers fail-safe grid defaults, renderers, and formatters', () => {
    expect(setConfig).toHaveBeenCalledWith(
      expect.objectContaining({
        grid: expect.objectContaining({
          formConfig: { enabled: false },
          pagerConfig: { enabled: true },
          proxyConfig: expect.objectContaining({
            autoLoad: true,
            response: { result: 'list', total: 'total' },
          }),
          sortConfig: { multiple: true },
        }),
      }),
    );
    expect([...renderers.keys()]).toEqual([
      'CellImage',
      'CellLink',
      'CellTag',
      'CellTags',
      'CellDict',
      'CellSwitch',
      'CellOperation',
    ]);
    expect([...formats.keys()]).toEqual([
      'formatPast2',
      'formatAmount3',
      'formatAmount2',
      'formatFenToYuanAmount',
      'formatFileSize',
    ]);
  });

  it('renders images, links, tags, and dictionary values from row fields', () => {
    const params = {
      column: { field: 'value' },
      row: { value: 'https://example.com/avatar.png' },
    };
    const image = expectVNode(
      renderer('CellImage').renderTableDefault(
        { props: { class: 'avatar', height: 40, width: 60 } },
        params,
      ),
    );
    const link = expectVNode(
      renderer('CellLink').renderTableDefault({ props: { text: '查看' } }),
    );
    const tag = expectVNode(
      renderer('CellTag').renderTableDefault(
        { props: { color: 'green' } },
        params,
      ),
    );
    const dict = expectVNode(
      renderer('CellDict').renderTableDefault(
        { props: { type: 'status' } },
        params,
      ),
    );

    expect(image.props).toMatchObject({
      class: 'avatar',
      previewSrcList: ['https://example.com/avatar.png'],
      src: 'https://example.com/avatar.png',
      style: { height: '40px', width: '60px' },
    });
    expect(slot(link, 'default')()).toBe('查看');
    expect(slot(tag, 'default')()).toBe('https://example.com/avatar.png');
    expect(dict.props).toMatchObject({
      type: 'status',
      value: 'https://example.com/avatar.png',
    });
    expect(renderer('CellDict').renderTableDefault({}, params)).toBe('');
  });

  it('renders tag collections only for array values', () => {
    const tagRenderer = renderer('CellTags');
    expect(
      tagRenderer.renderTableDefault(
        { props: {} },
        { column: { field: 'tags' }, row: { tags: [] } },
      ),
    ).toBe('');
    expect(
      tagRenderer.renderTableDefault(
        { props: {} },
        { column: { field: 'tags' }, row: { tags: 'not-an-array' } },
      ),
    ).toBe('');

    const container = expectVNode(
      tagRenderer.renderTableDefault(
        { props: { color: 'blue' } },
        { column: { field: 'tags' }, row: { tags: ['A', 'B'] } },
      ),
    );
    const tags = vnodeArray(container.children);
    expect(tags).toHaveLength(2);
  });

  it('updates switch state only when the async guard accepts the change', async () => {
    const row: Record<string, unknown> = { status: 0 };
    const beforeChange = vi.fn().mockResolvedValue(true);
    const vnode = expectVNode(
      renderer('CellSwitch').renderTableDefault(
        { attrs: { beforeChange }, props: {} },
        { column: { field: 'status' }, row },
      ),
    );

    await invoke(vnode.props?.['onUpdate:modelValue'], 1);
    expect(beforeChange).toHaveBeenCalledWith(1, row);
    expect(row).toMatchObject({ __loading_status: false, status: 1 });

    beforeChange.mockResolvedValue(false);
    await invoke(vnode.props?.['onUpdate:modelValue'], 0);
    expect(row.status).toBe(1);

    beforeChange.mockRejectedValue(new Error('permission denied'));
    await expect(
      invoke(vnode.props?.['onUpdate:modelValue'], 0),
    ).rejects.toThrow('permission denied');
    expect(row.__loading_status).toBe(false);
  });

  it('filters dynamic operations and preserves delete confirmation', () => {
    const onClick = vi.fn();
    const row = { id: 7, name: '管理员' };
    const operations = expectVNode(
      renderer('CellOperation').renderTableDefault(
        {
          attrs: { nameField: 'name', onClick },
          options: [
            'edit',
            'archive',
            { code: 'hidden', show: () => false, text: '隐藏' },
            'delete',
          ],
          props: {},
        },
        { column: { align: 'center' }, row },
      ),
    );
    const buttons = vnodeArray(operations.children);
    expect(operations.props?.style).toEqual({ justifyContent: 'center' });
    expect(buttons).toHaveLength(3);

    invokeSync(buttons[0]?.props?.onClick);
    expect(onClick).toHaveBeenCalledWith({ code: 'edit', row });
    expect(slot(buttons[1] as VNode, 'default')()).toContain('common.archive');

    const confirmation = buttons[2] as VNode;
    expect(confirmation.props?.title).toBe('ui.actionTitle.delete:');
    invokeSync(confirmation.props?.onConfirm);
    expect(onClick).toHaveBeenLastCalledWith({ code: 'delete', row });
    expect(slot(confirmation, 'default')()).toBeInstanceOf(Object);
    expect(slot(confirmation, 'reference')()).toBeInstanceOf(Object);

    const iconOperations = expectVNode(
      renderer('CellOperation').renderTableDefault(
        {
          attrs: { onClick },
          options: [{ code: 'inspect', icon: 'lucide:search', text: '查看' }],
          props: {},
        },
        { column: {}, row },
      ),
    );
    expect(iconOperations.props?.style).toEqual({ justifyContent: 'end' });
    const iconButton = vnodeArray(iconOperations.children)[0] as VNode;
    const iconContent = slot(iconButton, 'default')();
    expect(iconContent).toHaveLength(2);
    expect(isVNode(iconContent[0])).toBe(true);
    expect(iconContent[1]).toBe('查看');
  });

  it('delegates every registered cell formatter with defaults', () => {
    expect(format('formatPast2', '2026-08-30')).toBe('past:2026-08-30');
    expect(format('formatAmount3', null)).toBe('');
    expect(format('formatAmount3', 12)).toBe('number:12:3');
    expect(format('formatAmount2', 12)).toBe('number:12:2');
    expect(format('formatAmount2', 12, 4)).toBe('number:12:4');
    expect(format('formatFenToYuanAmount', 1234)).toBe('number:12.34:2');
    expect(format('formatFileSize', 1024, 1)).toBe('size:1024:1');
  });

  function renderer(name: string) {
    const registered = renderers.get(name);
    if (!registered) {
      throw new Error(`Missing renderer: ${name}`);
    }
    return registered;
  }

  function format(name: string, value: unknown, digits?: number) {
    const registered = formats.get(name);
    if (!registered) {
      throw new Error(`Missing format: ${name}`);
    }
    return registered.tableCellFormatMethod({ cellValue: value }, digits);
  }
});

function expectVNode(value: unknown) {
  if (!isVNode(value)) {
    throw new TypeError('Expected a VNode');
  }
  return value;
}

function slot(vnode: VNode, name: string) {
  if (!vnode.children || typeof vnode.children !== 'object') {
    throw new TypeError(`Missing VNode slots: ${name}`);
  }
  const candidate = (vnode.children as Record<string, unknown>)[name];
  if (typeof candidate !== 'function') {
    throw new TypeError(`Missing VNode slot: ${name}`);
  }
  return candidate;
}

function vnodeArray(value: unknown) {
  if (!Array.isArray(value) || !value.every((child) => isVNode(child))) {
    throw new TypeError('Expected VNode children');
  }
  return value as VNode[];
}

async function invoke(handler: unknown, value: unknown) {
  if (typeof handler !== 'function') {
    throw new TypeError('Expected an event handler');
  }
  return handler(value);
}

function invokeSync(handler: unknown) {
  if (typeof handler !== 'function') {
    throw new TypeError('Expected an event handler');
  }
  handler();
}
