<script lang="ts" setup>
import type { SupportedLanguagesType } from '@vben/locales';
import type { Preferences } from '@vben/preferences';
import type { DeepPartial } from '@vben/types';

import { computed, useAttrs } from 'vue';

import { Settings } from '@vben/icons';
import { $t, loadLocaleMessages } from '@vben/locales';
import { preferences, updatePreferences } from '@vben/preferences';
import { capitalizeFirstLetter } from '@vben/utils';

import { useVbenDrawer } from '@vben-core/popup-ui';
import { VbenButton } from '@vben-core/shadcn-ui';

import PreferencesDrawer from './preferences-drawer.vue';

const [Drawer, drawerApi] = useVbenDrawer({
  connectedComponent: PreferencesDrawer,
});

const rawAttrs = useAttrs();

function preferenceUpdate(
  group: keyof Preferences,
  key: string,
  value: unknown,
): DeepPartial<Preferences> {
  return { [group]: { [key]: value } } as DeepPartial<Preferences>;
}

function isSupportedLanguage(value: unknown): value is SupportedLanguagesType {
  return value === 'en-US' || value === 'zh-CN';
}

/**
 * preferences 转成 vue props
 * preferences.widget.fullscreen=>widgetFullscreen
 */
const attrs = computed(() => {
  const result: Record<string, unknown> = {};
  for (const key of Object.keys(preferences) as Array<keyof Preferences>) {
    const value = preferences[key];
    for (const [subKey, subValue] of Object.entries(value)) {
      result[`${key}${capitalizeFirstLetter(subKey)}`] = subValue;
    }
  }
  return result;
});

/**
 * preferences 转成 vue listener
 * preferences.widget.fullscreen=>@update:widgetFullscreen
 */
const listen = computed(() => {
  const result: Record<string, (value: unknown) => void> = {};
  for (const key of Object.keys(preferences) as Array<keyof Preferences>) {
    const value = preferences[key];
    for (const subKey of Object.keys(value)) {
      result[`update:${key}${capitalizeFirstLetter(subKey)}`] = (val) => {
        updatePreferences(preferenceUpdate(key, subKey, val));
        if (key === 'app' && subKey === 'locale' && isSupportedLanguage(val)) {
          loadLocaleMessages(val);
        }
      };
    }
  }
  return result;
});

const drawerBindings = computed<Record<string, unknown>>(() => {
  const result = {
    ...rawAttrs,
    ...attrs.value,
  };
  if (result.class === null || result.class === undefined) {
    delete result.class;
  }
  return result;
});
</script>
<template>
  <div>
    <Drawer v-bind="drawerBindings" v-on="listen" />

    <div @click="() => drawerApi.open()">
      <slot>
        <VbenButton
          :title="$t('preferences.title')"
          class="bg-primary flex-col-center size-10 cursor-pointer rounded-l-lg rounded-r-none border-none"
        >
          <Settings class="size-5" />
        </VbenButton>
      </slot>
    </div>
  </div>
</template>
