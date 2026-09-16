const LOADING_TRANSITION_FALLBACK_MS = 1000;

/** 隐藏并移除应用启动遮罩；动画事件缺失时使用超时兜底。 */
export function unmountGlobalLoading() {
  const loadingElement = document.querySelector('#__app-loading__');
  if (!loadingElement) {
    return;
  }

  loadingElement.classList.add('hidden');
  const injectLoadingElements = document.querySelectorAll(
    '[data-app-loading^="inject"]',
  );
  let removed = false;
  const cleanup = () => {
    if (removed) {
      return;
    }
    removed = true;
    loadingElement.remove();
    injectLoadingElements.forEach((element) => element.remove());
  };
  const fallbackTimer = window.setTimeout(
    cleanup,
    LOADING_TRANSITION_FALLBACK_MS,
  );
  loadingElement.addEventListener(
    'transitionend',
    () => {
      window.clearTimeout(fallbackTimer);
      cleanup();
    },
    { once: true },
  );
}
