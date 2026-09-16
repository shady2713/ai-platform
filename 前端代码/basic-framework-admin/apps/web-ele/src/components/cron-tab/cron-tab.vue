<script lang="ts" setup>
import type { CronValue, ShortcutsType } from './types';

import { computed, reactive, ref, watch } from 'vue';

import {
  ElButton,
  ElDialog,
  ElInput,
  ElOption,
  ElSelect,
  ElTabs,
} from 'element-plus';

import { showWarningMessage } from '#/utils/feedback';

import CronFieldPane from './cron-field-pane.vue';
import {
  formatCronExpression,
  formatCronField,
  parseCronExpression,
} from './cron-utils';
import CronWeekPane from './cron-week-pane.vue';
import { createDefaultCronValue, CronDataDefault } from './types';

interface Props {
  modelValue?: string;
  shortcuts?: ShortcutsType[];
}

defineOptions({ name: 'CronTab' });

const props = withDefaults(defineProps<Props>(), {
  modelValue: '* * * * * ?',
  shortcuts: () => [],
});

const emit = defineEmits<{
  'update:modelValue': [value: string];
}>();

const defaultValue = ref(props.modelValue);
const dialogVisible = ref(false);
const select = ref<string>();
const cronValue = reactive<CronValue>(createDefaultCronValue());
const data = CronDataDefault;

const valueSecond = computed(() => formatCronField('second', cronValue.second));
const valueMinute = computed(() => formatCronField('minute', cronValue.minute));
const valueHour = computed(() => formatCronField('hour', cronValue.hour));
const valueDay = computed(() => formatCronField('day', cronValue.day));
const valueMonth = computed(() => formatCronField('month', cronValue.month));
const valueWeek = computed(() => formatCronField('week', cronValue.week));
const valueYear = computed(() => formatCronField('year', cronValue.year));

watch(
  () => cronValue.week.type,
  (value) => {
    if (value !== '5') cronValue.day.type = '5';
  },
);

watch(
  () => cronValue.day.type,
  (value) => {
    if (value !== '5') cronValue.week.type = '5';
  },
);

watch(
  () => props.modelValue,
  (value) => {
    defaultValue.value = value;
  },
);

watch(select, (value) => {
  if (!value) return;

  select.value = undefined;
  if (value === 'custom') {
    open();
    return;
  }

  defaultValue.value = value;
  emit('update:modelValue', value);
});

function open() {
  loadCronValue();
  dialogVisible.value = true;
}

function loadCronValue() {
  defaultValue.value = props.modelValue;
  const parsed = parseCronExpression(props.modelValue);
  if (!parsed) {
    showWarningMessage('Cron 表达式错误，已转换为默认表达式');
    Object.assign(cronValue, createDefaultCronValue());
    return;
  }
  Object.assign(cronValue, parsed);
}

function submit() {
  const expression = formatCronExpression(cronValue);
  if (!parseCronExpression(expression)) {
    showWarningMessage('Cron 表达式不完整，请检查后重试');
    return;
  }
  defaultValue.value = expression;
  emit('update:modelValue', expression);
  dialogVisible.value = false;
}

function inputChange() {
  emit('update:modelValue', defaultValue.value);
}
</script>

<template>
  <ElInput
    v-model="defaultValue"
    class="input-with-select"
    v-bind="$attrs"
    @input="inputChange"
  >
    <template #append>
      <ElSelect v-model="select" placeholder="生成器" class="generator-select">
        <ElOption label="每分钟" value="0 * * * * ?" />
        <ElOption label="每小时" value="0 0 * * * ?" />
        <ElOption label="每天零点" value="0 0 0 * * ?" />
        <ElOption label="每月一号零点" value="0 0 0 1 * ?" />
        <ElOption label="每月最后一天零点" value="0 0 0 L * ?" />
        <ElOption label="每周星期日零点" value="0 0 0 ? * 1" />
        <ElOption
          v-for="(item, index) in shortcuts"
          :key="`${item.value}-${index}`"
          :label="item.text"
          :value="item.value"
        />
        <ElOption label="自定义" value="custom" />
      </ElSelect>
    </template>
  </ElInput>

  <ElDialog
    v-model="dialogVisible"
    append-to-body
    destroy-on-close
    title="Cron 规则生成器"
    width="min(580px, calc(100vw - 32px))"
  >
    <div class="sc-cron">
      <ElTabs>
        <CronFieldPane
          v-model:item="cronValue.second"
          :max="59"
          :min="0"
          :options="data.second"
          :value="valueSecond"
          interval-prefix="秒开始，每"
          label="秒"
          unit="秒"
        />
        <CronFieldPane
          v-model:item="cronValue.minute"
          :max="59"
          :min="0"
          :options="data.minute"
          :value="valueMinute"
          interval-prefix="分钟开始，每"
          label="分钟"
          unit="分钟"
        />
        <CronFieldPane
          v-model:item="cronValue.hour"
          :max="23"
          :min="0"
          :options="data.hour"
          :value="valueHour"
          interval-prefix="小时开始，每"
          label="小时"
          unit="小时"
        />
        <CronFieldPane
          v-model:item="cronValue.day"
          :max="31"
          :min="1"
          :options="data.day"
          :value="valueDay"
          allow-last
          allow-unspecified
          interval-prefix="号开始，每"
          label="日"
          unit="天"
        />
        <CronFieldPane
          v-model:item="cronValue.month"
          :max="12"
          :min="1"
          :options="data.month"
          :value="valueMonth"
          interval-prefix="月开始，每"
          label="月"
          unit="月"
        />
        <CronWeekPane
          v-model:item="cronValue.week"
          :options="data.week"
          :value="valueWeek"
        />
        <CronFieldPane
          v-model:item="cronValue.year"
          :max="2199"
          :min="1970"
          :options="data.year"
          :value="valueYear"
          allow-ignore
          interval-prefix="年开始，每"
          label="年"
          unit="年"
        />
      </ElTabs>
    </div>

    <template #footer>
      <ElButton @click="dialogVisible = false">取消</ElButton>
      <ElButton type="primary" @click="submit">确认</ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.sc-cron:deep(.el-tabs__item) {
  height: auto;
  padding: 0 7px;
  line-height: 1;
  vertical-align: bottom;
}

.generator-select {
  width: 115px;
}

.input-with-select:deep(.el-input-group__append) {
  background-color: var(--el-fill-color-blank);
}
</style>
