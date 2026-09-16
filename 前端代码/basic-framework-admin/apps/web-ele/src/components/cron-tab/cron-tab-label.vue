<script lang="ts" setup>
defineOptions({ name: 'CronTabLabel' });

defineProps<{
  label: string;
  value: string;
}>();
</script>

<template>
  <div class="sc-cron-num">
    <h2>{{ label }}</h2>
    <h4>{{ value }}</h4>
  </div>
</template>

<style scoped>
.sc-cron-num {
  width: 100%;
  margin-bottom: 15px;
  text-align: center;
}

.sc-cron-num h2 {
  margin-bottom: 15px;
  font-size: 12px;
  font-weight: normal;
}

.sc-cron-num h4 {
  display: block;
  width: 100%;
  height: 32px;
  padding: 0 15px;
  font-size: 12px;
  line-height: 30px;
  background: var(--el-color-primary-light-9);
  border-radius: 4px;
}

:global(.el-tabs__item.is-active) .sc-cron-num h4 {
  color: #fff;
  background: var(--el-color-primary);
}

:global([data-theme='dark']) .sc-cron-num h4 {
  background: var(--el-color-white);
}
</style>
