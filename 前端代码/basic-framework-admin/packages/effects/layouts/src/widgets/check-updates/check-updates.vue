<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';

import { $t } from '@vben/locales';

import { useVbenModal } from '@vben-core/popup-ui';

interface Props {
  checkUpdatesInterval?: number;
  checkUpdateUrl?: string;
}

defineOptions({ name: 'CheckUpdates' });

const props = withDefaults(defineProps<Props>(), {
  checkUpdatesInterval: 1,
  checkUpdateUrl: import.meta.env.BASE_URL || '/',
});

let activeRequest: AbortController | undefined;
let isCheckingUpdates = false;
let isMounted = false;
let timer: ReturnType<typeof setInterval> | undefined;
const currentVersionTag = ref('');
const lastVersionTag = ref('');

const [UpdateNoticeModal, modalApi] = useVbenModal({
  closable: false,
  closeOnPressEscape: false,
  closeOnClickModal: false,
  onCancel() {
    lastVersionTag.value = currentVersionTag.value;
    currentVersionTag.value = '';
    start();
  },
  onConfirm() {
    lastVersionTag.value = currentVersionTag.value;
    location.reload();
  },
});

async function getVersionTag(signal: AbortSignal) {
  try {
    if (
      location.hostname === 'localhost' ||
      location.hostname === '127.0.0.1'
    ) {
      return null;
    }
    const response = await fetch(props.checkUpdateUrl, {
      cache: 'no-cache',
      method: 'HEAD',
      redirect: 'manual',
      signal,
    });

    return (
      response.headers.get('etag') || response.headers.get('last-modified')
    );
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      return null;
    }
    console.error('Failed to fetch version tag');
    return null;
  }
}

async function checkForUpdates(signal: AbortSignal) {
  const versionTag = await getVersionTag(signal);
  if (!versionTag) {
    return;
  }

  if (!lastVersionTag.value) {
    lastVersionTag.value = versionTag;
    return;
  }

  if (lastVersionTag.value !== versionTag) {
    stop();
    handleNotice(versionTag);
  }
}

function handleNotice(versionTag: string) {
  currentVersionTag.value = versionTag;
  modalApi.open();
}

function start() {
  stop();
  const interval = props.checkUpdatesInterval;
  if (
    !isMounted ||
    document.hidden ||
    !Number.isFinite(interval) ||
    interval <= 0
  ) {
    return;
  }

  timer = setInterval(runCheck, interval * 60 * 1000);
}

async function runCheck() {
  if (
    !isMounted ||
    document.hidden ||
    isCheckingUpdates ||
    currentVersionTag.value
  ) {
    return;
  }

  isCheckingUpdates = true;
  const request = new AbortController();
  activeRequest = request;
  try {
    await checkForUpdates(request.signal);
  } finally {
    if (activeRequest === request) {
      activeRequest = undefined;
    }
    isCheckingUpdates = false;
  }
}

function handleVisibilitychange() {
  if (document.hidden) {
    stop();
    activeRequest?.abort();
    return;
  }

  runCheck().finally(start);
}

function stop() {
  if (timer) {
    clearInterval(timer);
    timer = undefined;
  }
}

onMounted(() => {
  isMounted = true;
  start();
  document.addEventListener('visibilitychange', handleVisibilitychange);
});

onUnmounted(() => {
  isMounted = false;
  stop();
  activeRequest?.abort();
  document.removeEventListener('visibilitychange', handleVisibilitychange);
});
</script>
<template>
  <UpdateNoticeModal
    :cancel-text="$t('common.cancel')"
    :confirm-text="$t('common.refresh')"
    :fullscreen-button="false"
    :title="$t('ui.widgets.checkUpdatesTitle')"
    centered
    content-class="px-8 min-h-10"
    footer-class="border-none mb-3 mr-3"
    header-class="border-none"
  >
    {{ $t('ui.widgets.checkUpdatesDescription') }}
  </UpdateNoticeModal>
</template>
