<script lang="ts" setup>
/**
 * 检索调试（K09）：用指定有权主体检索并展示**真实引用**。
 *
 * 调试只做两件事：发问题、显示后端返回的候选与引用（含位置与片段）。
 * 过滤条件由服务端按当前主体授权生成，界面**不能**构造过滤条件——调试台也不能越权。
 * 原文预览在"文档"页按文档编号走受控 API（`/ai/knowledge/document/content`），
 * 引用本身只提供片段回读（`/ai/knowledge/citation`），避免界面凭引用猜文档编号。
 */
import type { AiKnowledgeApi } from '#/api/ai/knowledge';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { readCitation, searchKnowledge } from '#/api/ai/knowledge';
import { showSuccessMessage } from '#/utils/feedback';

const query = ref('');
const searching = ref(false);
const result = ref<AiKnowledgeApi.SearchResult>();
const snippet = ref('');

const [Modal] = useVbenModal({ footer: false });

async function handleSearch() {
  if (!query.value.trim()) {
    showSuccessMessage('请输入问题');
    return;
  }
  searching.value = true;
  try {
    result.value = await searchKnowledge({ query: query.value, topK: 5 });
  } finally {
    searching.value = false;
  }
}

async function handleCitation(citation: AiKnowledgeApi.Citation) {
  snippet.value = await readCitation(citation.citationId);
}

defineExpose({ handleCitation, handleSearch, query, result, snippet });
</script>

<template>
  <Modal title="检索调试（过滤条件由服务端按当前授权生成）" class="w-[800px]">
    <div class="flex items-center gap-2">
      <input
        class="w-full rounded border px-2 py-1"
        placeholder="输入问题，例如：华东区域上个月净额是多少"
        v-model="query"
        @keyup.enter="handleSearch"
      />
      <button
        class="rounded bg-primary px-3 py-1 text-white"
        :disabled="searching"
        @click="handleSearch"
      >
        {{ searching ? '检索中…' : '检索' }}
      </button>
    </div>

    <div v-if="result" class="mt-4 text-sm">
      <div class="mb-2 text-gray-500">
        候选 {{ result.candidateCount ?? 0 }} 条，复核丢弃
        {{ result.filteredOutCount ?? 0 }} 条， 检索知识库
        {{ result.searchedKnowledgeBaseCount ?? 0 }} 个
      </div>
      <div
        v-if="result.noEvidence"
        class="rounded bg-amber-50 p-3 text-amber-700"
      >
        没有可用证据：请确认当前主体对相关知识库有 READ
        授权，且文档已可用（非失败/删除中）。
      </div>
      <ul v-else class="space-y-2">
        <li
          v-for="(citation, index) in result.citations"
          :key="citation.citationId"
          class="rounded border p-2"
        >
          <div class="font-medium">
            [{{ index + 1 }}] {{ citation.title ?? '未命名文档' }} · v{{
              citation.versionNo
            }}
            ·
            {{ citation.locationRef ?? '位置未知' }}
          </div>
          <div class="mt-1 text-gray-700">{{ citation.snippet }}</div>
          <div class="mt-1 flex gap-2">
            <button class="text-primary" @click="handleCitation(citation)">
              查看引用片段
            </button>
          </div>
        </li>
      </ul>
      <div v-if="snippet" class="mt-3 rounded bg-gray-50 p-3">
        <div class="mb-1 font-medium">引用片段（重新鉴权后从原文取回）</div>
        <div>{{ snippet }}</div>
      </div>
    </div>
  </Modal>
</template>
