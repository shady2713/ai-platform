<script setup lang="ts">
/**
 * 追问块（C03）：把"需要用户补充"做成**明确交互**，而不是伪装成结果。
 *
 * <p>设计契约要求"用户回答后形成新输入，不伪装成功结果"（`04-api-chat-integration.md` §5）：
 * 提交只 emit 一个字符串给宿主（宿主据此发起**新的 run**），本组件不显示任何"已完成/已生成"的结论，
 * 只如实说明"已提交、等待新的结果"。候选项与自由输入并存：有候选项时点一下即可，
 * 声明的必填字段以提示文本列出（不把用户的自由文本硬塞进结构化字段）。
 */
import type { ClarificationBlock } from './blocks';

import { computed, ref } from 'vue';

const props = defineProps<{ block: ClarificationBlock }>();

const emit = defineEmits<{ answer: [value: string] }>();

const draft = ref('');
const answered = ref(false);

const fieldHint = computed(() => {
  const fields = props.block.fields ?? [];
  if (fields.length === 0) {
    return '';
  }
  return fields
    .map((field) => (field.required ? `${field.label}（必填）` : field.label))
    .join('、');
});

function submit(value: string): void {
  const text = value.trim();
  if (answered.value || text.length === 0) {
    return;
  }
  answered.value = true;
  emit('answer', text);
}
</script>

<template>
  <section class="ai-clarification" data-testid="ai-message-clarification">
    <p
      class="ai-clarification__question"
      data-testid="ai-clarification-question"
    >
      {{ block.question }}
    </p>
    <p
      v-if="fieldHint"
      class="ai-clarification__fields"
      data-testid="ai-clarification-fields"
    >
      需要补充：{{ fieldHint }}
    </p>
    <ul v-if="block.options.length > 0" class="ai-clarification__options">
      <li
        v-for="(option, index) in block.options"
        :key="index"
        class="ai-clarification__option"
      >
        <button
          :disabled="answered"
          data-testid="ai-clarification-option"
          type="button"
          @click="submit(option)"
        >
          {{ option }}
        </button>
      </li>
    </ul>
    <form
      v-if="!answered"
      class="ai-clarification__form"
      data-testid="ai-clarification-form"
      @submit.prevent="submit(draft)"
    >
      <input
        v-model="draft"
        aria-label="补充信息"
        class="ai-clarification__input"
        data-testid="ai-clarification-input"
        type="text"
      />
      <button
        :disabled="draft.trim().length === 0"
        data-testid="ai-clarification-submit"
        type="submit"
      >
        提交
      </button>
    </form>
    <p
      v-else
      class="ai-clarification__answered"
      data-testid="ai-clarification-answered"
    >
      已提交，等待新的结果（本次回答不会改变已有结论）
    </p>
  </section>
</template>
