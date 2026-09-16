<script lang="ts" setup>
import type { CronItem } from './types';

import { computed } from 'vue';

import {
  ElForm,
  ElFormItem,
  ElInputNumber,
  ElOption,
  ElRadioButton,
  ElRadioGroup,
  ElSelect,
  ElTabPane,
} from 'element-plus';

import CronTabLabel from './cron-tab-label.vue';

interface Props {
  allowIgnore?: boolean;
  allowLast?: boolean;
  allowUnspecified?: boolean;
  intervalPrefix: string;
  label: string;
  max: number;
  min: number;
  options: string[];
  unit: string;
  value: string;
}

defineOptions({ name: 'CronFieldPane' });

const props = withDefaults(defineProps<Props>(), {
  allowIgnore: false,
  allowLast: false,
  allowUnspecified: false,
});

const item = defineModel<CronItem>('item', { required: true });

const intervalMaximum = computed(() => props.max - props.min + 1);
</script>

<template>
  <ElTabPane>
    <template #label>
      <CronTabLabel :label="label" :value="value" />
    </template>

    <ElForm>
      <ElFormItem label="类型">
        <ElRadioGroup v-model="item.type">
          <ElRadioButton v-if="allowIgnore" value="-1">忽略</ElRadioButton>
          <ElRadioButton value="0">任意值</ElRadioButton>
          <ElRadioButton value="1">范围</ElRadioButton>
          <ElRadioButton value="2">间隔</ElRadioButton>
          <ElRadioButton value="3">指定</ElRadioButton>
          <ElRadioButton v-if="allowLast" value="4">
            本月最后一天
          </ElRadioButton>
          <ElRadioButton v-if="allowUnspecified" value="5">
            不指定
          </ElRadioButton>
        </ElRadioGroup>
      </ElFormItem>

      <ElFormItem v-if="item.type === '1'" label="范围">
        <ElInputNumber
          v-model="item.range.start"
          :max="max"
          :min="min"
          controls-position="right"
        />
        <span class="range-separator">-</span>
        <ElInputNumber
          v-model="item.range.end"
          :max="max"
          :min="min"
          controls-position="right"
        />
      </ElFormItem>

      <ElFormItem v-if="item.type === '2'" label="间隔">
        <ElInputNumber
          v-model="item.loop.start"
          :max="max"
          :min="min"
          controls-position="right"
        />
        {{ intervalPrefix }}
        <ElInputNumber
          v-model="item.loop.end"
          :max="intervalMaximum"
          :min="1"
          controls-position="right"
        />
        {{ unit }}执行一次
      </ElFormItem>

      <ElFormItem v-if="item.type === '3'" label="指定">
        <ElSelect v-model="item.appoint" multiple class="field-select">
          <ElOption
            v-for="option in options"
            :key="option"
            :label="option"
            :value="option"
          />
        </ElSelect>
      </ElFormItem>
    </ElForm>
  </ElTabPane>
</template>

<style scoped>
.range-separator {
  padding: 0 15px;
}

.field-select {
  width: 100%;
}
</style>
