<script setup lang="ts">
/**
 * 受控块级节点渲染（C03）：段落 / 标题 / 引用 / 列表 / 围栏代码。
 *
 * <p>标题与列表用 `<component :is>` 在**固定标签集合**内选择标签（h1–h6 / ul / ol），
 * 标签名来自解析器给出的数值与布尔，不来自内容——内容永远只是文本。
 */
import type { MarkdownNode } from './markdown';

import MarkdownInlineNodes from './MarkdownInlineNodes.vue';

defineProps<{ nodes: MarkdownNode[] }>();

/** 标题标签：级别已由解析器收敛到 1..6。 */
function headingTag(level: number): string {
  return `h${Math.min(Math.max(level, 1), 6)}`;
}
</script>

<template>
  <template v-for="(node, index) in nodes" :key="index">
    <component
      :is="headingTag(node.level)"
      v-if="node.type === 'heading'"
      class="ai-md__heading"
      :data-level="node.level"
    >
      <MarkdownInlineNodes :nodes="node.children" />
    </component>
    <blockquote v-else-if="node.type === 'blockquote'" class="ai-md__quote">
      <MarkdownInlineNodes :nodes="node.children" />
    </blockquote>
    <pre v-else-if="node.type === 'codeBlock'" class="ai-md__pre"><code
      class="ai-md__pre-code"
      :data-language="node.language"
    >{{ node.code }}</code></pre>
    <component
      :is="node.ordered ? 'ol' : 'ul'"
      v-else-if="node.type === 'list'"
      class="ai-md__list"
    >
      <li
        v-for="(item, itemIndex) in node.items"
        :key="itemIndex"
        class="ai-md__list-item"
      >
        <MarkdownInlineNodes :nodes="item" />
      </li>
    </component>
    <p v-else class="ai-md__paragraph">
      <MarkdownInlineNodes :nodes="node.children" />
    </p>
  </template>
</template>
