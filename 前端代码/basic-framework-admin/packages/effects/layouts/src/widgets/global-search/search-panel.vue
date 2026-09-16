<script setup lang="ts">
import type { MenuRecordRaw } from '@vben/types';

import { nextTick, onMounted, ref, shallowRef, watch } from 'vue';
import { useRouter } from 'vue-router';

import { SearchX, X } from '@vben/icons';
import { $t } from '@vben/locales';
import {
  mapTree,
  openWindow,
  traverseTreeValues,
  uniqueByField,
} from '@vben/utils';

import { VbenIcon, VbenScrollbar } from '@vben-core/shadcn-ui';

import { onKeyStroke, useLocalStorage, useThrottleFn } from '@vueuse/core';

import { resolveNavigationDestination } from '../../navigation-destination';

defineOptions({
  name: 'SearchPanel',
});

const props = withDefaults(
  defineProps<{
    active: boolean;
    keyword?: string;
    menus?: MenuRecordRaw[];
  }>(),
  {
    keyword: '',
    menus: () => [],
  },
);
const emit = defineEmits<{ close: [] }>();

const router = useRouter();
const searchHistory = useLocalStorage<MenuRecordRaw[]>(
  `__search-history-${location.hostname}__`,
  [],
);
const activeIndex = ref(-1);
const searchItems = shallowRef<MenuRecordRaw[]>([]);
const searchResults = ref<MenuRecordRaw[]>([]);

const handleSearch = useThrottleFn(search, 200);

function search(searchKey: string) {
  searchKey = searchKey.trim().toLowerCase();
  if (!searchKey) {
    searchResults.value = [];
    activeIndex.value = -1;
    return;
  }
  const results: MenuRecordRaw[] = [];
  traverseTreeValues(searchItems.value, (item) => {
    if (
      !item.disabled &&
      matchesSearchKey(item.name.toLowerCase(), searchKey)
    ) {
      results.push(item);
    }
  });
  searchResults.value = uniqueByField(results, 'path');
  activeIndex.value = searchResults.value.length > 0 ? 0 : -1;
}

// When the keyboard up and down keys move to an invisible place
// the scroll bar needs to scroll automatically
function scrollIntoView() {
  const element = document.querySelector(
    `[data-search-item="${activeIndex.value}"]`,
  );

  if (element) {
    element.scrollIntoView({ block: 'nearest' });
  }
}

async function handleEnter() {
  if (!props.active || searchResults.value.length === 0) {
    return;
  }
  const result = searchResults.value;
  const index = activeIndex.value;
  if (result.length === 0 || index < 0) {
    return;
  }
  const to = result[index];
  if (to) {
    const destination = resolveNavigationDestination(to.path);
    if (!destination) {
      return;
    }
    searchHistory.value = uniqueByField([...searchHistory.value, to], 'path');
    handleClose();
    await nextTick();
    if (destination.kind === 'external') {
      openWindow(destination.url);
    } else {
      await router.push({ path: destination.path, replace: true });
    }
  }
}

function handleUp() {
  if (!props.active || searchResults.value.length === 0) {
    return;
  }
  activeIndex.value--;
  if (activeIndex.value < 0) {
    activeIndex.value = searchResults.value.length - 1;
  }
  scrollIntoView();
}

function handleDown() {
  if (!props.active || searchResults.value.length === 0) {
    return;
  }
  activeIndex.value++;
  if (activeIndex.value > searchResults.value.length - 1) {
    activeIndex.value = 0;
  }
  scrollIntoView();
}

function handleClose() {
  searchResults.value = [];
  emit('close');
}

function handleMouseenter(e: MouseEvent) {
  const index = (e.currentTarget as HTMLElement).dataset.index;
  activeIndex.value = Number(index);
}

async function handleSelect(index: number) {
  activeIndex.value = index;
  await handleEnter();
}

function removeItem(index: number) {
  if (props.keyword) {
    searchResults.value.splice(index, 1);
  } else {
    searchHistory.value.splice(index, 1);
  }
  activeIndex.value =
    searchResults.value.length > 0 ? Math.max(activeIndex.value - 1, 0) : -1;
  scrollIntoView();
}

function matchesSearchKey(value: string, searchKey: string): boolean {
  let searchIndex = 0;
  for (const character of value) {
    if (character === searchKey[searchIndex]) {
      searchIndex += 1;
      if (searchIndex === searchKey.length) {
        return true;
      }
    }
  }
  return false;
}

watch(
  () => props.keyword,
  (val) => {
    if (val) {
      handleSearch(val);
    } else {
      searchResults.value = [...searchHistory.value];
    }
  },
);

watch(
  () => props.menus,
  (menus) => {
    searchItems.value = mapTree(menus, (item) => ({
      ...item,
      name: $t(item.name),
    }));
    if (props.keyword) {
      handleSearch(props.keyword);
    }
  },
  { immediate: true },
);

onMounted(() => {
  if (searchHistory.value.length > 0) {
    searchResults.value = searchHistory.value;
  }
  onKeyStroke('Enter', handleEnter);
  onKeyStroke('ArrowUp', handleUp);
  onKeyStroke('ArrowDown', handleDown);
  onKeyStroke('Escape', () => {
    if (props.active) {
      handleClose();
    }
  });
});
</script>

<template>
  <VbenScrollbar>
    <div class="!flex h-full justify-center px-2 sm:max-h-[450px]">
      <!-- 无搜索结果 -->
      <div
        v-if="keyword && searchResults.length === 0"
        class="text-muted-foreground text-center"
      >
        <SearchX class="mx-auto mt-4 size-12" />
        <p class="mb-10 mt-6 text-xs">
          {{ $t('ui.widgets.search.noResults') }}
          <span class="text-foreground text-sm font-medium">
            "{{ keyword }}"
          </span>
        </p>
      </div>
      <!-- 历史搜索记录 & 没有搜索结果 -->
      <div
        v-if="!keyword && searchResults.length === 0"
        class="text-muted-foreground text-center"
      >
        <p class="my-10 text-xs">
          {{ $t('ui.widgets.search.noRecent') }}
        </p>
      </div>

      <ul v-show="searchResults.length > 0" class="w-full">
        <li
          v-if="searchHistory.length > 0 && !keyword"
          class="text-muted-foreground mb-2 text-xs"
        >
          {{ $t('ui.widgets.search.recent') }}
        </li>
        <li
          v-for="(item, index) in searchResults"
          :key="item.path"
          :class="
            activeIndex === index
              ? 'active bg-primary text-primary-foreground'
              : ''
          "
          :data-index="index"
          :data-search-item="index"
          class="bg-accent group mb-3 flex w-full items-center rounded-lg"
          @mouseenter="handleMouseenter"
        >
          <button
            class="flex-center flex-1 cursor-pointer px-4 py-4 text-left"
            type="button"
            @click="handleSelect(index)"
          >
            <VbenIcon
              :icon="item.icon"
              class="mr-2 size-5 flex-shrink-0"
              fallback
            />
            <span class="flex-1">{{ item.name }}</span>
          </button>
          <button
            :aria-label="$t('common.delete')"
            class="flex-center dark:hover:bg-accent hover:text-primary-foreground rounded-full p-1 hover:scale-110"
            type="button"
            @click="removeItem(index)"
          >
            <X class="size-4" />
          </button>
        </li>
      </ul>
    </div>
  </VbenScrollbar>
</template>
