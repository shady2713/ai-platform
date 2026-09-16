<script setup lang="ts">
import type { PropType } from 'vue';

import type { ActionItem } from './typing';

import { computed, toRaw } from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { $t } from '@vben/locales';

import {
  ElButton,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElPopconfirm,
  ElSpace,
  ElTooltip,
} from 'element-plus';

import {
  getButtonProps,
  getPopConfirmProps,
  getTooltipProps,
  invokeDropdownAction,
  resolveActions,
  resolveDropdownActions,
} from './actions';

const props = defineProps({
  actions: {
    type: Array as PropType<ActionItem[]>,
    default() {
      return [];
    },
  },
  dropDownActions: {
    type: Array as PropType<ActionItem[]>,
    default() {
      return [];
    },
  },
  divider: {
    type: Boolean,
    default: true,
  },
});

const { hasAccessByCodes } = useAccess();

const getActions = computed(() =>
  resolveActions(toRaw(props.actions), hasAccessByCodes),
);

const getDropdownList = computed(() =>
  resolveDropdownActions(
    toRaw(props.dropDownActions),
    props.divider,
    hasAccessByCodes,
  ),
);

function handleMenuClick(command: number | string) {
  invokeDropdownAction(getDropdownList.value[Number(command)]);
}
</script>

<template>
  <div class="table-actions">
    <ElSpace
      :size="
        getActions?.some((item: ActionItem) => item.type === 'text') ? 0 : 8
      "
    >
      <template v-for="(action, index) in getActions" :key="index">
        <ElPopconfirm
          v-if="action.popConfirm"
          v-bind="getPopConfirmProps(action.popConfirm)"
        >
          <template v-if="action.popConfirm.icon" #icon>
            <IconifyIcon :icon="action.popConfirm.icon" />
          </template>
          <template #reference>
            <ElTooltip
              v-if="getTooltipProps(action.tooltip)"
              v-bind="getTooltipProps(action.tooltip)"
            >
              <ElButton v-bind="getButtonProps(action)">
                <template v-if="action.icon">
                  <IconifyIcon :icon="action.icon" class="mr-1" />
                </template>
                {{ action.label }}
              </ElButton>
            </ElTooltip>
            <ElButton v-else v-bind="getButtonProps(action)">
              <template v-if="action.icon">
                <IconifyIcon :icon="action.icon" class="mr-1" />
              </template>
              {{ action.label }}
            </ElButton>
          </template>
        </ElPopconfirm>
        <ElTooltip
          v-else-if="getTooltipProps(action.tooltip)"
          v-bind="getTooltipProps(action.tooltip)"
        >
          <ElButton v-bind="getButtonProps(action)" @click="action.onClick">
            <template v-if="action.icon">
              <IconifyIcon :icon="action.icon" class="mr-1" />
            </template>
            {{ action.label }}
          </ElButton>
        </ElTooltip>
        <ElButton
          v-else
          v-bind="getButtonProps(action)"
          @click="action.onClick"
        >
          <template v-if="action.icon">
            <IconifyIcon :icon="action.icon" class="mr-1" />
          </template>
          {{ action.label }}
        </ElButton>
      </template>
    </ElSpace>

    <ElDropdown v-if="getDropdownList.length > 0" @command="handleMenuClick">
      <slot name="more">
        <ElButton :type="getDropdownList[0]?.type" link>
          {{ $t('page.action.more') }}
          <IconifyIcon icon="lucide:ellipsis-vertical" class="ml-1" />
        </ElButton>
      </slot>
      <template #dropdown>
        <ElDropdownMenu>
          <ElDropdownItem
            v-for="(action, index) in getDropdownList"
            :key="index"
            :command="index"
            :disabled="action.disabled"
          >
            <template v-if="action.popConfirm">
              <ElPopconfirm v-bind="getPopConfirmProps(action.popConfirm)">
                <template v-if="action.popConfirm.icon" #icon>
                  <IconifyIcon :icon="action.popConfirm.icon" />
                </template>
                <template #reference>
                  <div>
                    <IconifyIcon v-if="action.icon" :icon="action.icon" />
                    <span :class="action.icon ? 'ml-1' : ''">
                      {{ action.text }}
                    </span>
                  </div>
                </template>
              </ElPopconfirm>
            </template>
            <template v-else>
              <div>
                <IconifyIcon v-if="action.icon" :icon="action.icon" />
                {{ action.label }}
              </div>
            </template>
          </ElDropdownItem>
        </ElDropdownMenu>
      </template>
    </ElDropdown>
  </div>
</template>
<style lang="scss">
.table-actions {
  .el-button--text {
    padding: 4px;
    margin-left: 0;
  }

  .el-button .iconify + span,
  .el-button span + .iconify {
    margin-inline-start: 4px;
  }

  .iconify {
    display: inline-flex;
    align-items: center;
    width: 1em;
    height: 1em;
    font-style: normal;
    line-height: 0;
    vertical-align: -0.125em;
    color: inherit;
    text-align: center;
    text-transform: none;
    text-rendering: optimizelegibility;
    -webkit-font-smoothing: antialiased;
    -moz-osx-font-smoothing: grayscale;
  }
}

.el-popconfirm {
  .el-popconfirm__action {
    .el-button {
      margin-left: 8px !important;
    }
  }
}
</style>
