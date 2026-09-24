<script setup lang="ts">
/**
 * 引用卡片（C03）：展示服务端签发的引用，并按当前权限打开。
 *
 * <p>三态都要有明确表达：**有片段**照原样展示；**无片段**说明"请打开原文核对"而不是编一段摘要；
 * **打开失败**（失权/撤回/不存在）给固定提示且不显示任何内容——"曾经可读"不作为可继续读的依据（AT-048）。
 */
import type { CitationBlock } from '../message/blocks';
import type { CitationApi } from './citation';

import { computed, ref } from 'vue';

import {
  canOpenOriginal,
  citationLabel,
  citationSnippetText,
  openCitationOriginal,
  openCitationSnippet,
} from './citation';

const props = defineProps<{ api?: CitationApi; citation: CitationBlock }>();

const snippet = ref(citationSnippetText(props.citation));
const snippetError = ref('');
const original = ref('');
const originalError = ref('');
const running = ref(false);

/** 未注入端口时只读展示（不给"点了会失败"的按钮）。 */
const hasApi = computed(() => props.api !== undefined);
const canOpen = computed(() => hasApi.value && canOpenOriginal(props.citation));

async function reloadSnippet(): Promise<void> {
  const api = props.api;
  if (!api || running.value) {
    return;
  }
  running.value = true;
  snippetError.value = '';
  const outcome = await openCitationSnippet(api, props.citation.citationId);
  running.value = false;
  if (outcome.ok) {
    snippet.value = outcome.content;
  } else {
    snippetError.value = outcome.message;
  }
}

async function openOriginal(): Promise<void> {
  const api = props.api;
  const documentId = props.citation.documentId;
  if (!api || documentId === undefined || running.value) {
    return;
  }
  running.value = true;
  originalError.value = '';
  const outcome = await openCitationOriginal(api, documentId);
  running.value = false;
  if (outcome.ok) {
    original.value = outcome.content;
  } else {
    // 失权/撤回：不保留上一次的内容（读取结论只以本次响应为准）
    original.value = '';
    originalError.value = outcome.message;
  }
}
</script>

<template>
  <section class="ai-citation" data-testid="ai-message-citation">
    <p class="ai-citation__label" data-testid="ai-citation-label">
      {{ citationLabel(citation) }}
    </p>
    <code class="ai-citation__id" data-testid="ai-citation-id">{{
      citation.citationId
    }}</code>
    <p
      v-if="snippet"
      class="ai-citation__snippet"
      data-testid="ai-citation-snippet"
    >
      {{ snippet }}
    </p>
    <p v-else class="ai-citation__hint" data-testid="ai-citation-no-snippet">
      未附带片段：请按引用位置打开原文核对
    </p>
    <p
      v-if="snippetError"
      class="ai-citation__error"
      data-testid="ai-citation-snippet-error"
      role="alert"
    >
      {{ snippetError }}
    </p>
    <p
      v-if="originalError"
      class="ai-citation__error"
      data-testid="ai-citation-original-error"
      role="alert"
    >
      {{ originalError }}
    </p>
    <pre v-if="original" data-testid="ai-citation-text">{{ original }}</pre>
    <span class="ai-citation__controls">
      <button
        v-if="hasApi"
        :disabled="running"
        data-testid="ai-citation-reload"
        type="button"
        @click="reloadSnippet"
      >
        读取引用片段
      </button>
      <button
        v-if="canOpen"
        :disabled="running"
        data-testid="ai-citation-open-original"
        type="button"
        @click="openOriginal"
      >
        打开原文
      </button>
      <span v-if="!hasApi" data-testid="ai-citation-readonly">
        未接入引用读取端口：仅展示检索结果
      </span>
    </span>
  </section>
</template>
