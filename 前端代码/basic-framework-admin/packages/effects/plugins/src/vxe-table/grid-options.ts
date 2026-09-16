import type { VxePagerPropTypes } from 'vxe-pc-ui';
import type {
  VxeGridProps as NativeGridOptions,
  VxeGridPropTypes,
  VxeToolbarPropTypes,
} from 'vxe-table';

import type { DeepPartial } from '@vben/types';

import type { SeparatorOptions, VxeTableGridOptions } from './types';

import { cloneDeep, mergeWithArrayOverride } from '@vben/utils';

const FORM_SLOT_PREFIX = 'form-';
const RESERVED_GRID_SLOTS = [
  'empty',
  'form',
  'loading',
  'toolbar-actions',
  'toolbar-tools',
] as const;

const MOBILE_PAGER_LAYOUTS = [
  'PrevJump',
  'PrevPage',
  'Number',
  'NextPage',
  'NextJump',
] satisfies VxePagerPropTypes.Layouts;

const DESKTOP_PAGER_LAYOUTS = [
  'Total',
  'Sizes',
  'Home',
  ...MOBILE_PAGER_LAYOUTS,
  'End',
] satisfies VxePagerPropTypes.Layouts;

interface ToolbarOptionsInput {
  gridOptions: DeepPartial<VxeTableGridOptions> | undefined;
  hasActionSlot: boolean;
  hasForm: boolean;
  hasToolSlot: boolean;
  showSearchForm: boolean | undefined;
  showToolbar: boolean;
  showTableTitle: boolean;
  translate: (key: string) => string;
}

function isToolbarToolConfig(
  value: unknown,
): value is VxeToolbarPropTypes.ToolConfig {
  return typeof value === 'object' && value !== null;
}

export function shouldShowSeparator(
  hasForm: boolean,
  showSearchForm: boolean | undefined,
  separator: boolean | SeparatorOptions | undefined,
): boolean {
  if (!hasForm || showSearchForm === false || separator === false) return false;
  return typeof separator !== 'object' || separator.show !== false;
}

export function getSeparatorBackground(
  separator: boolean | SeparatorOptions | undefined,
): string | undefined {
  return typeof separator === 'object' ? separator.backgroundColor : undefined;
}

export function buildToolbarOptions({
  gridOptions,
  hasActionSlot,
  hasForm,
  hasToolSlot,
  showSearchForm,
  showToolbar,
  showTableTitle,
  translate,
}: ToolbarOptionsInput): Pick<VxeTableGridOptions, 'toolbarConfig'> {
  const configuredTools = gridOptions?.toolbarConfig?.tools;
  const tools: VxeToolbarPropTypes.ToolConfig[] = Array.isArray(configuredTools)
    ? configuredTools.filter((tool) => isToolbarToolConfig(tool))
    : [];

  if (gridOptions?.toolbarConfig?.search && hasForm) {
    tools.push({
      circle: true,
      code: 'search',
      icon: 'vxe-icon-search',
      status: showSearchForm ? 'primary' : undefined,
      title: translate(
        showSearchForm ? 'common.hideSearchPanel' : 'common.showSearchPanel',
      ),
    });
  }

  const toolbarConfig: VxeGridPropTypes.ToolbarConfig = { tools };
  if (!showToolbar) {
    toolbarConfig.enabled = false;
    return { toolbarConfig };
  }

  toolbarConfig.slots = {
    ...(hasActionSlot || showTableTitle ? { buttons: 'toolbar-actions' } : {}),
    ...(hasToolSlot ? { tools: 'toolbar-tools' } : {}),
  };
  return { toolbarConfig };
}

export function buildGridOptions(
  globalOptions: NativeGridOptions,
  gridOptions: DeepPartial<VxeTableGridOptions> | undefined,
  toolbarOptions: Pick<VxeTableGridOptions, 'toolbarConfig'>,
  isMobile: boolean,
): NativeGridOptions {
  const mergedOptions: NativeGridOptions = cloneDeep(
    mergeWithArrayOverride(
      {},
      toolbarOptions,
      gridOptions ?? {},
      globalOptions,
    ),
  );

  if (mergedOptions.proxyConfig) {
    mergedOptions.proxyConfig.enabled = Boolean(mergedOptions.proxyConfig.ajax);
    mergedOptions.proxyConfig.autoLoad = false;
  }

  if (mergedOptions.pagerConfig) {
    mergedOptions.pagerConfig = mergeWithArrayOverride(
      {},
      mergedOptions.pagerConfig,
      {
        background: true,
        className: 'mt-2 w-full',
        layouts: isMobile ? MOBILE_PAGER_LAYOUTS : DESKTOP_PAGER_LAYOUTS,
        pageSize: 20,
        pageSizes: [10, 20, 30, 50, 100, 200],
        size: 'mini' as const,
      },
    );
  }

  if (mergedOptions.formConfig) mergedOptions.formConfig.enabled = false;
  return mergedOptions;
}

export function getDelegatedGridSlots(slotNames: string[]): string[] {
  return slotNames.filter(
    (slotName) =>
      !RESERVED_GRID_SLOTS.includes(
        slotName as (typeof RESERVED_GRID_SLOTS)[number],
      ),
  );
}

export function getDelegatedFormSlots(slotNames: string[]): string[] {
  return slotNames
    .filter((slotName) => slotName.startsWith(FORM_SLOT_PREFIX))
    .map((slotName) => slotName.slice(FORM_SLOT_PREFIX.length));
}

export function shouldShowDefaultEmpty(options: NativeGridOptions): boolean {
  return options.emptyText === undefined && options.emptyRender === undefined;
}
