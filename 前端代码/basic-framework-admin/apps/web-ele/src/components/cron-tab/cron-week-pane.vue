<script lang="ts" setup>
import type { CronValue, WeekOption } from './types';

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

defineOptions({ name: 'CronWeekPane' });

defineProps<{
  options: WeekOption[];
  value: string;
}>();

const item = defineModel<CronValue['week']>('item', { required: true });
</script>

<template>
  <ElTabPane>
    <template #label>
      <CronTabLabel label="周" :value="value" />
    </template>

    <ElForm>
      <ElFormItem label="类型">
        <ElRadioGroup v-model="item.type">
          <ElRadioButton value="0">任意值</ElRadioButton>
          <ElRadioButton value="1">范围</ElRadioButton>
          <ElRadioButton value="2">月内序号</ElRadioButton>
          <ElRadioButton value="3">指定</ElRadioButton>
          <ElRadioButton value="4">月内最后一个星期几</ElRadioButton>
          <ElRadioButton value="5">不指定</ElRadioButton>
        </ElRadioGroup>
      </ElFormItem>

      <ElFormItem v-if="item.type === '1'" label="范围">
        <ElSelect v-model="item.range.start">
          <ElOption
            v-for="option in options"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </ElSelect>
        <span class="range-separator">-</span>
        <ElSelect v-model="item.range.end">
          <ElOption
            v-for="option in options"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </ElSelect>
      </ElFormItem>

      <ElFormItem v-if="item.type === '2'" label="月内序号">
        每月第
        <ElInputNumber
          v-model="item.loop.start"
          :max="5"
          :min="1"
          controls-position="right"
        />
        个
        <ElSelect v-model="item.loop.end">
          <ElOption
            v-for="option in options"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </ElSelect>
        执行一次
      </ElFormItem>

      <ElFormItem v-if="item.type === '3'" label="指定">
        <ElSelect v-model="item.appoint" multiple class="field-select">
          <ElOption
            v-for="option in options"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </ElSelect>
      </ElFormItem>

      <ElFormItem v-if="item.type === '4'" label="最后星期几">
        <ElSelect v-model="item.last">
          <ElOption
            v-for="option in options"
            :key="option.value"
            :label="option.label"
            :value="option.value"
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
