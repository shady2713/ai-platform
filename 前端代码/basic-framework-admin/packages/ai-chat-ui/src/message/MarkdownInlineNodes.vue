<script setup lang="ts">
/**
 * 受控行内节点渲染（C03）：只渲染 markdown.ts 产出的允许节点。
 *
 * <p>递归组件（按文件名自引用）渲染强调/加粗/链接的子节点；`<a>` 只出现在
 * **已通过协议与 Origin 校验**的链接节点上，且固定加 `noopener noreferrer nofollow`。
 * 其余全部走文本插值——这里没有 `v-html`，也不存在把节点变成 HTML 的路径。
 */
import type { MarkdownInline } from './markdown';

defineProps<{ nodes: MarkdownInline[] }>();
</script>

<template>
  <template v-for="(node, index) in nodes" :key="index">
    <em v-if="node.type === 'emphasis'">
      <MarkdownInlineNodes :nodes="node.children" />
    </em>
    <strong v-else-if="node.type === 'strong'">
      <MarkdownInlineNodes :nodes="node.children" />
    </strong>
    <code v-else-if="node.type === 'code'" class="ai-md__code">{{
      node.text
    }}</code>
    <a
      v-else-if="node.type === 'link'"
      class="ai-md__link"
      data-testid="ai-message-link"
      :href="node.href"
      rel="noopener noreferrer nofollow"
      target="_blank"
    >
      <MarkdownInlineNodes :nodes="node.children" />
    </a>
    <template v-else>{{ node.text }}</template>
  </template>
</template>
