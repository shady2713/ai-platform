import type { BaseFormComponentType, VbenFormProps } from '@vben-core/form-ui';

import type { SetupVxeTable } from './types';

import { defineComponent, watch } from 'vue';

import { usePreferences } from '@vben/preferences';

import { useVbenForm as defaultUseVbenForm } from '@vben-core/form-ui';

import {
  VxeButton,
  VxeCheckbox,
  VxeIcon,
  VxeInput,
  VxeLoading,
  VxeModal,
  VxeNumberInput,
  VxePager,
  VxeRadio,
  VxeRadioButton,
  VxeRadioGroup,
  VxeSelect,
  VxeTooltip,
  VxeUI,
  VxeUpload,
} from 'vxe-pc-ui';
import enUS from 'vxe-pc-ui/lib/language/en-US';
import zhCN from 'vxe-pc-ui/lib/language/zh-CN';
import {
  VxeColgroup,
  VxeColumn,
  VxeGrid,
  VxeTable,
  VxeToolbar,
} from 'vxe-table';

import { extendsDefaultFormatter } from './extends';

const LOCALES = {
  'en-US': enUS,
  'zh-CN': zhCN,
};

let componentsRegistered = false;
let setupCompleted = false;
let tableFormFactory: typeof defaultUseVbenForm | undefined;

function createVirtualComponent(name: string) {
  return defineComponent({
    name,
  });
}

export function useTableForm<
  T extends BaseFormComponentType = BaseFormComponentType,
>(options: VbenFormProps<T>) {
  if (!tableFormFactory) {
    throw new Error(
      'VXE table is not configured: call setupVbenVxeTable before rendering grids',
    );
  }
  return tableFormFactory(options);
}

export function initVxeTable() {
  if (componentsRegistered) return;

  VxeUI.component(VxeTable);
  VxeUI.component(VxeColumn);
  VxeUI.component(VxeColgroup);
  VxeUI.component(VxeGrid);
  VxeUI.component(VxeToolbar);

  VxeUI.component(VxeButton);
  VxeUI.component(VxeCheckbox);
  VxeUI.component(createVirtualComponent('VxeForm'));
  VxeUI.component(VxeIcon);
  VxeUI.component(VxeInput);
  VxeUI.component(VxeLoading);
  VxeUI.component(VxeModal);
  VxeUI.component(VxeNumberInput);
  VxeUI.component(VxePager);
  VxeUI.component(VxeRadio);
  VxeUI.component(VxeRadioButton);
  VxeUI.component(VxeRadioGroup);
  VxeUI.component(VxeSelect);
  VxeUI.component(VxeTooltip);
  VxeUI.component(VxeUpload);

  componentsRegistered = true;
}

export function setupVbenVxeTable(setupOptions: SetupVxeTable) {
  if (setupCompleted) {
    throw new Error('VXE table has already been configured');
  }
  const { configVxeTable, useVbenForm: formFactory } = setupOptions;

  initVxeTable();
  tableFormFactory = formFactory;

  const { isDark, locale } = usePreferences();

  watch(
    [() => isDark.value, () => locale.value],
    ([isDarkValue, localeValue]) => {
      VxeUI.setTheme(isDarkValue ? 'dark' : 'light');
      VxeUI.setI18n(localeValue, LOCALES[localeValue]);
      VxeUI.setLanguage(localeValue);
    },
    {
      immediate: true,
    },
  );

  extendsDefaultFormatter(VxeUI);

  configVxeTable(VxeUI);
  setupCompleted = true;
}
