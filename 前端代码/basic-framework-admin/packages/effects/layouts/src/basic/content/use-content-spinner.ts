import type { RouteLocationNormalized } from 'vue-router';

import { computed, onScopeDispose, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { preferences } from '@vben/preferences';

const MINIMUM_VISIBLE_TIME_MS = 500;

function useContentSpinner() {
  const router = useRouter();
  const spinning = ref(false);
  const enableLoading = computed(() => preferences.transition.loading);
  const pendingRoutes = new Set<RouteLocationNormalized>();

  let hideTimer: ReturnType<typeof setTimeout> | undefined;
  let visibleSince = 0;

  function clearHideTimer() {
    if (hideTimer) {
      clearTimeout(hideTimer);
      hideTimer = undefined;
    }
  }

  function hide() {
    clearHideTimer();
    spinning.value = false;
    visibleSince = 0;
  }

  function start() {
    clearHideTimer();
    if (!spinning.value) {
      visibleSince = performance.now();
      spinning.value = true;
    }
  }

  function finish() {
    if (!spinning.value || pendingRoutes.size > 0) {
      return;
    }

    const remainingTime =
      MINIMUM_VISIBLE_TIME_MS - (performance.now() - visibleSince);
    if (remainingTime <= 0) {
      hide();
      return;
    }

    hideTimer = setTimeout(hide, remainingTime);
  }

  function shouldTrack(route: RouteLocationNormalized) {
    return enableLoading.value && !route.meta.loaded && !route.meta.iframeSrc;
  }

  const removeBeforeGuard = router.beforeEach((to) => {
    if (shouldTrack(to)) {
      pendingRoutes.add(to);
      start();
    }
    return true;
  });

  const removeAfterHook = router.afterEach((to) => {
    if (pendingRoutes.delete(to)) {
      finish();
    }
  });

  const stopPreferenceWatch = watch(enableLoading, (enabled) => {
    if (!enabled) {
      pendingRoutes.clear();
      hide();
    }
  });

  onScopeDispose(() => {
    removeBeforeGuard();
    removeAfterHook();
    stopPreferenceWatch();
    pendingRoutes.clear();
    hide();
  });

  return { spinning };
}

export { useContentSpinner };
