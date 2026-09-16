<script setup lang="ts">
import type { MenuRecordRaw } from '@vben/types';

import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue';

import {
  ArrowDown,
  ArrowUp,
  CornerDownLeft,
  MdiKeyboardEsc,
  Search,
} from '@vben/icons';
import { $t } from '@vben/locales';
import { isWindowsOs } from '@vben/utils';

import { useVbenModal } from '@vben-core/popup-ui';

import { useMagicKeys, whenever } from '@vueuse/core';

import SearchPanel from './search-panel.vue';

defineOptions({
  name: 'GlobalSearch',
});

const props = withDefaults(
  defineProps<{ enableShortcutKey?: boolean; menus?: MenuRecordRaw[] }>(),
  {
    enableShortcutKey: true,
    menus: () => [],
  },
);

const keyword = ref('');
const searchInputRef = ref<HTMLInputElement>();

const [Modal, modalApi] = useVbenModal({
  onCancel() {
    modalApi.close();
  },
  onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      keyword.value = '';
    }
  },
});
const open = modalApi.useStore((state) => state.isOpen);

function handleClose() {
  modalApi.close();
  keyword.value = '';
}

const keys = useMagicKeys();
const cmd = isWindowsOs() ? keys['ctrl+k'] : keys['cmd+k'];
if (cmd) {
  whenever(cmd, () => {
    if (props.enableShortcutKey) {
      modalApi.open();
    }
  });
}

whenever(open, () => {
  nextTick(() => {
    searchInputRef.value?.focus();
  });
});

const preventDefaultBrowserSearchHotKey = (event: KeyboardEvent) => {
  if (event.key?.toLowerCase() === 'k' && (event.metaKey || event.ctrlKey)) {
    event.preventDefault();
  }
};

const toggleKeydownListener = () => {
  if (props.enableShortcutKey) {
    window.addEventListener('keydown', preventDefaultBrowserSearchHotKey);
  } else {
    window.removeEventListener('keydown', preventDefaultBrowserSearchHotKey);
  }
};

const toggleOpen = () => {
  open.value ? modalApi.close() : modalApi.open();
};

watch(() => props.enableShortcutKey, toggleKeydownListener);

onMounted(() => {
  toggleKeydownListener();

  onUnmounted(() => {
    window.removeEventListener('keydown', preventDefaultBrowserSearchHotKey);
  });
});
</script>

<template>
  <div>
    <Modal
      :fullscreen-button="false"
      class="w-[600px]"
      header-class="py-2 border-b"
    >
      <template #title>
        <div class="flex items-center">
          <Search class="text-muted-foreground mr-2 size-4" />
          <input
            ref="searchInputRef"
            v-model="keyword"
            :aria-label="$t('ui.widgets.search.searchNavigate')"
            :placeholder="$t('ui.widgets.search.searchNavigate')"
            autocomplete="off"
            class="ring-none placeholder:text-muted-foreground focus-visible:ring-primary w-[80%] rounded-md border border-none bg-transparent p-2 pl-0 text-sm font-normal outline-none ring-0 ring-offset-transparent focus-visible:ring-1"
            spellcheck="false"
            type="search"
          />
        </div>
      </template>

      <SearchPanel
        :active="open === true"
        :keyword="keyword"
        :menus="menus"
        @close="handleClose"
      />
      <template #footer>
        <div class="flex w-full justify-start text-xs">
          <div class="mr-2 flex items-center">
            <CornerDownLeft class="mr-1 size-3" />
            {{ $t('ui.widgets.search.select') }}
          </div>
          <div class="mr-2 flex items-center">
            <ArrowUp class="mr-1 size-3" />
            <ArrowDown class="mr-1 size-3" />
            {{ $t('ui.widgets.search.navigate') }}
          </div>
          <div class="flex items-center">
            <MdiKeyboardEsc class="mr-1 size-3" />
            {{ $t('ui.widgets.search.close') }}
          </div>
        </div>
      </template>
    </Modal>
    <button
      :aria-label="$t('ui.widgets.search.title')"
      class="md:bg-accent focus-visible:ring-ring group flex h-8 cursor-pointer items-center gap-3 rounded-2xl border-none bg-none px-2 py-0.5 outline-none focus-visible:ring-2 focus-visible:ring-offset-2"
      type="button"
      @click="toggleOpen()"
    >
      <Search
        class="text-muted-foreground group-hover:text-foreground size-4 group-hover:opacity-100"
      />
      <span
        class="text-muted-foreground group-hover:text-foreground hidden text-xs duration-300 md:block"
      >
        {{ $t('ui.widgets.search.title') }}
      </span>
      <span
        v-if="enableShortcutKey"
        class="bg-background border-foreground/60 text-muted-foreground group-hover:text-foreground relative hidden rounded-sm rounded-r-xl px-1.5 py-1 text-xs leading-none group-hover:opacity-100 md:block"
      >
        {{ isWindowsOs() ? 'Ctrl' : '⌘' }}
        <kbd>K</kbd>
      </span>
    </button>
  </div>
</template>
