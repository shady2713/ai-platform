<script lang="ts" setup>
import type { Arrayable } from '@vueuse/core';
import type { FlattenedItem, TreeItemEmits } from 'reka-ui';

import type { ClassType } from '@vben-core/typings';

import type { FlattenedTreeNode } from './model';
import type { TreeKey, TreeNode, TreeProps } from './types';

import { computed, ref, watch, watchEffect } from 'vue';

import { ChevronRight, IconifyIcon } from '@vben-core/icons';
import { cn, get } from '@vben-core/shared/utils';

import { TreeItem, TreeRoot } from 'reka-ui';

import { Checkbox } from '../checkbox';
import { flattenTree, getTreeNodeChildren, getTreeNodeKey } from './model';
import { getEnabledParentKeys } from './selection';
import { treePropsDefaults } from './types';

const props = withDefaults(defineProps<TreeProps>(), treePropsDefaults());

const emits = defineEmits<{
  expand: [value: FlattenedItem<TreeNode>];
  select: [value: FlattenedItem<TreeNode>];
}>();

type TreeSelectEvent = TreeItemEmits<TreeNode>['select'][0];
type TreeToggleEvent = TreeItemEmits<TreeNode>['toggle'][0];

function getNodeKey(item: TreeNode): TreeKey {
  return getTreeNodeKey(item, props.valueField);
}

function getNodeChildren(item: TreeNode): TreeNode[] | undefined {
  return getTreeNodeChildren(item, props.childrenField);
}

function getNodeIcon(item: TreeNode): string | undefined {
  const icon = get(item, props.iconField);
  return typeof icon === 'string' ? icon : undefined;
}

const flattenData = ref<FlattenedTreeNode[]>([]);
const modelValue = defineModel<Arrayable<TreeKey>>();
const normalizedDefaultExpandedKeys = computed(() =>
  (props.defaultExpandedKeys ?? []).map(String),
);
const expanded = ref<string[]>(normalizedDefaultExpandedKeys.value);

// 监听 defaultExpandedKeys 变化，支持外部动态控制展开
watch(
  () => props.defaultExpandedKeys,
  () => {
    expanded.value = normalizedDefaultExpandedKeys.value;
  },
  { deep: true },
);

const treeValue = ref<Arrayable<TreeNode>>();

watchEffect(() => {
  flattenData.value = flattenTree(props.treeData, {
    childrenField: props.childrenField,
    valueField: props.valueField,
  });
  updateTreeValue();
});

watch(
  [() => props.treeData, () => props.defaultExpandedLevel],
  () => {
    if (
      props.defaultExpandedLevel !== undefined &&
      props.defaultExpandedLevel > 0
    ) {
      expandToLevel(props.defaultExpandedLevel);
    }
  },
  { deep: true, immediate: true },
);

function getItemByValue(value: TreeKey) {
  return flattenData.value.find((item) => getNodeKey(item.value) === value)
    ?.value;
}

function updateTreeValue() {
  const val = modelValue.value;
  if (val === undefined) {
    treeValue.value = props.multiple ? [] : undefined;
  } else if (Array.isArray(val)) {
    if (val.length === 0) {
      treeValue.value = [];
    } else {
      const filteredValues = val.filter((v) => {
        const item = getItemByValue(v);
        return item && !get(item, props.disabledField);
      });
      treeValue.value = filteredValues
        .map((v) => getItemByValue(v))
        .filter((item): item is TreeNode => item !== undefined);

      if (filteredValues.length !== val.length) {
        modelValue.value = filteredValues;
      }
    }
  } else {
    const item = getItemByValue(val);
    if (item && !get(item, props.disabledField)) {
      treeValue.value = item;
    } else {
      treeValue.value = props.multiple ? [] : undefined;
      modelValue.value = props.multiple ? [] : undefined;
    }
  }
}

function updateModelValue(val: Arrayable<TreeNode>) {
  if (Array.isArray(val)) {
    const filteredVal = val.filter((v) => !get(v, props.disabledField));
    modelValue.value = filteredVal.map((item) => getNodeKey(item));
  } else {
    if (val && !get(val, props.disabledField)) {
      modelValue.value = getNodeKey(val);
    }
  }
}

function expandToLevel(level: number) {
  const keys: string[] = [];
  flattenData.value.forEach((item) => {
    if (item.level <= level - 1) {
      keys.push(String(getNodeKey(item.value)));
    }
  });
  expanded.value = keys;
}

function collapseNodes(value: Arrayable<TreeKey>) {
  const keys = new Set((Array.isArray(value) ? value : [value]).map(String));
  expanded.value = expanded.value.filter((key) => !keys.has(key));
}

function expandNodes(value: Arrayable<TreeKey>) {
  const keys = [...(Array.isArray(value) ? value : [value])];
  keys.forEach((key) => {
    const expandedKey = String(key);
    if (expanded.value.includes(expandedKey)) return;
    const item = getItemByValue(key);
    if (item) {
      expanded.value.push(expandedKey);
    }
  });
}

function expandAll() {
  expanded.value = flattenData.value
    .filter((item) => item.hasChildren)
    .map((item) => String(getNodeKey(item.value)));
}

function collapseAll() {
  expanded.value = [];
}

function checkAll() {
  if (!props.multiple) return;
  modelValue.value = [
    ...new Set(
      flattenData.value
        .filter((item) => !get(item.value, props.disabledField))
        .map((item) => getNodeKey(item.value)),
    ),
  ];
  updateTreeValue();
}

function unCheckAll() {
  if (!props.multiple) return;
  modelValue.value = [];
  updateTreeValue();
}

function isNodeDisabled(item: FlattenedItem<TreeNode>) {
  return props.disabled || Boolean(get(item.value, props.disabledField));
}

function onToggle(item: FlattenedItem<TreeNode>) {
  emits('expand', item);
}
function onSelect(item: FlattenedItem<TreeNode>, isSelected: boolean) {
  if (isNodeDisabled(item)) {
    return;
  }

  if (
    !props.checkStrictly &&
    props.multiple &&
    props.autoCheckParent &&
    isSelected
  ) {
    const parents = flattenData.value.find(
      (entry) => entry.id === getNodeKey(item.value),
    )?.parents;
    getEnabledParentKeys(
      parents ?? [],
      flattenData.value,
      props.disabledField,
    ).forEach((p) => {
      if (Array.isArray(modelValue.value) && !modelValue.value.includes(p)) {
        modelValue.value.push(p);
      }
    });
  }
  if (
    !props.checkStrictly &&
    props.multiple &&
    props.autoCheckParent &&
    !isSelected
  ) {
    const parents = flattenData.value.find(
      (entry) => entry.id === getNodeKey(item.value),
    )?.parents;
    getEnabledParentKeys(parents ?? [], flattenData.value, props.disabledField)
      .toReversed()
      .forEach((p) => {
        const children = flattenData.value.filter((i) => {
          return (
            i.parents.length > 0 &&
            i.parents.includes(p) &&
            i.id !== getNodeKey(item.value) &&
            i.parentId === p
          );
        });
        const selectedKeys = modelValue.value;
        if (Array.isArray(selectedKeys)) {
          const hasSelectedChild = children.some((child) =>
            selectedKeys.includes(getNodeKey(child.value)),
          );
          if (!hasSelectedChild) {
            const index = selectedKeys.indexOf(p);
            if (index !== -1) {
              selectedKeys.splice(index, 1);
            }
          }
        }
      });
  }
  updateTreeValue();
  emits('select', item);
}

defineExpose({
  collapseAll,
  collapseNodes,
  expandAll,
  expandNodes,
  checkAll,
  unCheckAll,
  expandToLevel,
  getItemByValue,
});
</script>
<template>
  <TreeRoot
    :get-key="(item) => String(getNodeKey(item))"
    :get-children="getNodeChildren"
    :items="treeData"
    :model-value="treeValue"
    v-model:expanded="expanded"
    :default-expanded="normalizedDefaultExpandedKeys"
    :propagate-select="!checkStrictly"
    :multiple="multiple"
    :disabled="disabled"
    :selection-behavior="allowClear || multiple ? 'toggle' : 'replace'"
    @update:model-value="updateModelValue"
    v-slot="{ flattenItems }"
    :class="
      cn(
        'text-blackA11 container select-none list-none rounded-lg text-sm font-medium',
        $attrs.class as unknown as ClassType,
        bordered ? 'border' : '',
      )
    "
  >
    <div
      :class="
        cn('my-0.5 flex w-full items-center p-1', bordered ? 'border-b' : '')
      "
      v-if="$slots.header"
    >
      <slot name="header"> </slot>
    </div>
    <div
      :class="
        cn('my-0.5 flex w-full items-center p-1', bordered ? 'border-b' : '')
      "
      v-if="treeData.length > 0"
    >
      <div
        class="flex size-5 flex-1 cursor-pointer items-center"
        @click="() => (expanded?.length > 0 ? collapseAll() : expandAll())"
      >
        <ChevronRight
          :class="{ 'rotate-90': expanded?.length > 0 }"
          class="size-4 cursor-pointer text-foreground/80 transition hover:text-foreground"
        />
        <Checkbox
          v-if="multiple"
          @click.stop
          @update:model-value="
            (checked: boolean | 'indeterminate') =>
              checked === true ? checkAll() : unCheckAll()
          "
        />
      </div>
    </div>
    <TransitionGroup :name="transition ? 'fade' : ''">
      <TreeItem
        v-for="item in flattenItems"
        v-slot="{
          isExpanded,
          isSelected,
          isIndeterminate,
          handleSelect,
          handleToggle,
        }"
        :key="item._id"
        :style="{ 'margin-left': `${item.level - 1}rem` }"
        :class="
          cn('cursor-pointer', getNodeClass?.(item), {
            'data-[selected]:bg-accent': !multiple,
            'cursor-not-allowed text-foreground/50': isNodeDisabled(item),
          })
        "
        v-bind="
          Object.assign(item.bind, {
            onfocus: isNodeDisabled(item) ? 'this.blur()' : undefined,
            disabled: isNodeDisabled(item),
          })
        "
        @select="
          (event: TreeSelectEvent) => {
            if (isNodeDisabled(item)) {
              event.preventDefault();
              event.stopPropagation();
              return;
            }
            if (event.detail.originalEvent.type === 'click') {
              event.preventDefault();
            }
            onSelect(item, event.detail.isSelected);
          }
        "
        @toggle="
          (event: TreeToggleEvent) => {
            if (event.detail.originalEvent.type === 'click') {
              event.preventDefault();
            }
            !isNodeDisabled(item) && onToggle(item);
          }
        "
        class="tree-node focus:ring-grass8 my-0.5 flex items-center rounded p-1 outline-none focus:ring-2"
      >
        <ChevronRight
          v-if="item.hasChildren"
          class="size-4 cursor-pointer text-foreground/80 transition hover:text-foreground"
          :class="{ 'rotate-90': isExpanded }"
          @click.stop="
            () => {
              handleToggle();
              onToggle(item);
            }
          "
        />
        <div v-else class="h-4 w-4"></div>
        <div class="flex items-center gap-1">
          <Checkbox
            v-if="multiple"
            :model-value="isSelected && !isNodeDisabled(item)"
            :disabled="isNodeDisabled(item)"
            :indeterminate="isIndeterminate && !isNodeDisabled(item)"
            @click="
              (event: MouseEvent) => {
                if (isNodeDisabled(item)) {
                  event.preventDefault();
                  event.stopPropagation();
                  return;
                }
                handleSelect();
              }
            "
          />
          <div
            class="flex items-center gap-1"
            @click="
              (event: MouseEvent) => {
                if (isNodeDisabled(item)) {
                  event.preventDefault();
                  event.stopPropagation();
                  return;
                }
                handleSelect();
              }
            "
          >
            <slot name="node" v-bind="item">
              <IconifyIcon
                class="size-4"
                v-if="showIcon && getNodeIcon(item.value)"
                :icon="getNodeIcon(item.value) || ''"
              />
              {{ get(item.value, labelField) }}
            </slot>
          </div>
        </div>
        <div class="h-4 w-4"></div>
      </TreeItem>
    </TransitionGroup>
    <div
      :class="
        cn('my-0.5 flex w-full items-center p-1', bordered ? 'border-t' : '')
      "
      v-if="$slots.footer"
    >
      <slot name="footer"> </slot>
    </div>
  </TreeRoot>
</template>
<style lang="scss" scoped>
.container {
  position: relative;
  padding: 0;
  list-style-type: none;
}

.item {
  box-sizing: border-box;
  width: 100%;
  height: 30px;
  background-color: #f3f3f3;
  border: 1px solid #666;
}

/* 1. 声明过渡效果 */
.fade-move,
.fade-enter-active,
.fade-leave-active {
  transition: all 0.5s cubic-bezier(0.55, 0, 0.1, 1);
}

/* 2. 声明进入和离开的状态 */
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
  transform: scaleY(0.01) translate(30px, 0);
}

/* 3. 确保离开的项目被移除出了布局流
      以便正确地计算移动时的动画效果。 */
.fade-leave-active {
  position: absolute;
}
</style>
