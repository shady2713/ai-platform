<script lang="ts" setup>
import type { RouteRecordNormalized } from 'vue-router';

import type { BreadcrumbStyleType } from '@vben/types';

import type { IBreadcrumb } from '@vben-core/shadcn-ui';

import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { $t } from '@vben/locales';

import { VbenBreadcrumbView } from '@vben-core/shadcn-ui';

import { resolveNavigationDestination } from '../navigation-destination';

interface Props {
  hideWhenOnlyOne?: boolean;
  showHome?: boolean;
  showIcon?: boolean;
  type?: BreadcrumbStyleType;
}

const props = withDefaults(defineProps<Props>(), {
  showHome: false,
  showIcon: false,
  type: 'normal',
});

const route = useRoute();
const router = useRouter();

function resolveBreadcrumbPath(match: RouteRecordNormalized) {
  if (!match.path.includes(':')) {
    return match.path;
  }
  if (!match.name) {
    return undefined;
  }

  try {
    return router.resolve({ name: match.name, params: route.params }).path;
  } catch {
    return undefined;
  }
}

const breadcrumbs = computed((): IBreadcrumb[] => {
  const resultBreadcrumb: IBreadcrumb[] = [];

  for (const match of route.matched) {
    const { hideChildrenInMenu, hideInBreadcrumb, icon, title } = match.meta;
    const titleKey = title?.trim();
    if (hideInBreadcrumb || hideChildrenInMenu || !match.path || !titleKey) {
      continue;
    }

    resultBreadcrumb.push({
      icon,
      path: resolveBreadcrumbPath(match),
      title: $t(titleKey),
    });
  }
  if (props.showHome) {
    resultBreadcrumb.unshift({
      icon: 'mdi:home-outline',
      isHome: true,
      path: '/',
    });
  }
  if (props.hideWhenOnlyOne && resultBreadcrumb.length === 1) {
    return [];
  }

  return resultBreadcrumb;
});

function handleSelect(path: string) {
  const destination = resolveNavigationDestination(path);
  if (destination?.kind === 'internal') {
    void router.push(destination.path);
  }
}
</script>
<template>
  <VbenBreadcrumbView
    :breadcrumbs="breadcrumbs"
    :show-icon="showIcon"
    :style-type="type"
    class="ml-2"
    @select="handleSelect"
  />
</template>
