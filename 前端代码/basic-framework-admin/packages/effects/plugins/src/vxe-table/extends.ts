import type { VxeUIExport } from 'vxe-table';

import type { VxeGridApi } from './api';
import type {
  VxeGridFormValues,
  VxeGridRow,
  VxeTableGridOptions,
} from './types';

import { formatDate, formatDateTime, isDate, isDayjsObject } from '@vben/utils';

type ProxyQueryKey = 'query' | 'queryAll';
type ProxyQuery = (...args: unknown[]) => unknown;
type GridStateWriter<T extends object> = Pick<VxeGridApi<T>, 'setState'>;

const PROXY_QUERY_KEYS: readonly ProxyQueryKey[] = ['query', 'queryAll'];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isPointerEvent(value: unknown): boolean {
  return typeof PointerEvent !== 'undefined' && value instanceof PointerEvent;
}

function formatCellDate(value: unknown, includeTime: boolean): string {
  if (
    typeof value !== 'number' &&
    typeof value !== 'string' &&
    !isDate(value) &&
    !isDayjsObject(value)
  ) {
    return '';
  }
  return includeTime ? formatDateTime(value) : formatDate(value);
}

/** Injects current form values into VXE query and query-all proxy calls. */
export function extendProxyOptions<T extends object = VxeGridRow>(
  api: GridStateWriter<T>,
  options: VxeTableGridOptions<T>,
  getFormValues: () => VxeGridFormValues,
): void {
  for (const key of PROXY_QUERY_KEYS) {
    extendProxyOption(key, api, options, getFormValues);
  }
}

function extendProxyOption<T extends object>(
  key: ProxyQueryKey,
  api: GridStateWriter<T>,
  options: VxeTableGridOptions<T>,
  getFormValues: () => VxeGridFormValues,
): void {
  const candidate: unknown = options.proxyConfig?.ajax?.[key];
  if (typeof candidate !== 'function') return;
  const query = candidate as ProxyQuery;

  const wrappedQuery: ProxyQuery = async (
    params: unknown,
    customValues: unknown,
    ...args: unknown[]
  ) => {
    const suppliedValues =
      isRecord(customValues) && !isPointerEvent(customValues)
        ? customValues
        : {};
    return await query(
      params,
      { ...suppliedValues, ...getFormValues() },
      ...args,
    );
  };

  api.setState({
    gridOptions: {
      proxyConfig: { ajax: { [key]: wrappedQuery } },
    },
  });
}

export function extendsDefaultFormatter(vxeUI: VxeUIExport): void {
  vxeUI.formats.add('formatDate', {
    tableCellFormatMethod({ cellValue }) {
      return formatCellDate(cellValue, false);
    },
  });

  vxeUI.formats.add('formatDateTime', {
    tableCellFormatMethod({ cellValue }) {
      return formatCellDate(cellValue, true);
    },
  });
}
