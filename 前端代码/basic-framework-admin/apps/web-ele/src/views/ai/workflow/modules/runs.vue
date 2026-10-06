<script lang="ts" setup>
/**
 * 受控运行与运行留痕（X08）：受理即固定最新已发布版本并**同步**执行。
 *
 * 受理响应自带逐节点事实，运行查询接口的 nodes 为空是契约如此——所以这里把两处事实
 * 合并展示。失败永远显示稳定原因码；需要人工确认的工具节点会以
 * AI_TOOL_CONFIRMATION_REQUIRED 受控结束，那不是执行失败，文案必须分开说。
 */
import type { AiWorkflowApi, AiWorkflowRunApi } from '#/api/ai/workflow';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { useVbenForm } from '#/adapter/form';
import {
  acceptWorkflowRun,
  getWorkflowRunNodeList,
  getWorkflowRunPage,
} from '#/api/ai/workflow';
import { extractErrorMessage, showSuccessMessage } from '#/utils/feedback';

import {
  describeRun,
  formatDuration,
  formatNodeProgress,
  formatNodeStatus,
  formatNodeType,
  formatRunStatus,
  useRunAcceptFormSchema,
  WORKFLOW_RUN_STATUS_OPTIONS,
} from '../data';

const PAGE_SIZE = 20;

const workflow = ref<AiWorkflowApi.Workflow>();
const acceptVisible = ref(false);
const runs = ref<AiWorkflowRunApi.Run[]>([]);
const nodes = ref<AiWorkflowRunApi.RunNode[]>([]);
const selectedRunId = ref<number>();
const status = ref('');
const pageNo = ref(1);
const total = ref(0);
const errorMessage = ref('');

const [Form, formApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' }, labelWidth: 140 },
  layout: 'horizontal',
  schema: useRunAcceptFormSchema(),
  showDefaultActions: false,
  wrapperClass: 'grid-cols-1',
});

async function loadRuns() {
  if (!workflow.value) {
    return;
  }
  const page = await getWorkflowRunPage({
    pageNo: pageNo.value,
    pageSize: PAGE_SIZE,
    status: status.value || undefined,
    workflowId: workflow.value.id,
  });
  runs.value = page.list ?? [];
  total.value = page.total ?? 0;
}

async function handleAccept() {
  if (!workflow.value) {
    return;
  }
  const { valid } = await formApi.validate();
  if (!valid) {
    return;
  }
  const values = await formApi.getValues();
  try {
    const run = await acceptWorkflowRun({
      dataLevel: String(values.dataLevel),
      idempotencyKey: String(values.idempotencyKey),
      inputText: values.inputText ? String(values.inputText) : undefined,
      maxDurationMillis:
        values.maxDurationMillis === undefined ||
        values.maxDurationMillis === null
          ? undefined
          : Number(values.maxDurationMillis),
      maxSteps:
        values.maxSteps === undefined || values.maxSteps === null
          ? undefined
          : Number(values.maxSteps),
      workflowId: workflow.value.id,
    });
    errorMessage.value = '';
    // 受理响应带节点事实：直接展示，省掉一次回查
    if (run.nodes?.length) {
      selectedRunId.value = run.id;
      nodes.value = run.nodes;
    }
    showSuccessMessage(describeRun(run));
  } catch (error) {
    errorMessage.value = extractErrorMessage(error, '受理运行失败');
  }
  await loadRuns();
}

/** 节点留痕：受理响应之外的运行，走独立的 node-list 接口 */
async function handleNodes(run: AiWorkflowRunApi.Run) {
  selectedRunId.value = run.id;
  errorMessage.value = '';
  try {
    nodes.value = await getWorkflowRunNodeList(run.id);
  } catch (error) {
    // 与上面的 handleAccept 同一套口径。少了这层捕获，失败会变成
    // 没有用户可见消息的未捕获拒绝，同时把上一个运行的留痕留在页面上冒充本次的。
    nodes.value = [];
    errorMessage.value = extractErrorMessage(error, '读取节点留痕失败');
  }
}

async function handleFilter(value: string) {
  status.value = value;
  pageNo.value = 1;
  await loadRuns();
}

async function handlePrev() {
  if (pageNo.value <= 1) {
    return;
  }
  pageNo.value -= 1;
  await loadRuns();
}

async function handleNext() {
  if (pageNo.value * PAGE_SIZE >= total.value) {
    return;
  }
  pageNo.value += 1;
  await loadRuns();
}

const [Modal, modalApi] = useVbenModal({
  async onOpenChange(isOpen) {
    if (!isOpen) {
      return;
    }
    const data = modalApi.getData<{
      accept?: boolean;
      workflow: AiWorkflowApi.Workflow;
    }>();
    workflow.value = data?.workflow;
    acceptVisible.value = Boolean(data?.accept);
    nodes.value = [];
    selectedRunId.value = undefined;
    status.value = '';
    pageNo.value = 1;
    errorMessage.value = '';
    formApi.resetForm();
    await loadRuns();
  },
});
</script>

<template>
  <Modal
    :title="workflow ? `运行记录 · ${workflow.name}` : '运行记录'"
    class="w-[900px]"
  >
    <div class="flex flex-col gap-3">
      <div v-if="acceptVisible" class="rounded border border-border p-3">
        <p class="mb-2 text-sm font-medium">受理运行</p>
        <Form />
        <p class="mb-1 text-xs text-muted-foreground">
          受理会固定当前最新已发布版本并同步执行（预算有界）；需要人工确认的工具节点
          在流程里不会执行，运行以稳定错误码受控结束。幂等键重复受理会返回首次运行。
        </p>
        <button
          class="mt-1 text-sm text-blue-600"
          type="button"
          @click="handleAccept"
        >
          受理并执行
        </button>
      </div>

      <div class="flex items-center gap-2">
        <select
          class="rounded border border-border px-2 py-1 text-xs"
          :value="status"
          @change="handleFilter(($event.target as HTMLSelectElement).value)"
        >
          <option value="">全部状态</option>
          <option
            v-for="option in WORKFLOW_RUN_STATUS_OPTIONS"
            :key="option.value"
            :value="option.value"
          >
            {{ option.label }}
          </option>
        </select>
        <span class="text-xs text-muted-foreground">共 {{ total }} 条</span>
        <button
          class="ml-auto text-xs text-blue-600 disabled:text-gray-400"
          :disabled="pageNo <= 1"
          type="button"
          @click="handlePrev"
        >
          上一页
        </button>
        <span class="text-xs">第 {{ pageNo }} 页</span>
        <button
          class="text-xs text-blue-600 disabled:text-gray-400"
          :disabled="pageNo * PAGE_SIZE >= total"
          type="button"
          @click="handleNext"
        >
          下一页
        </button>
      </div>

      <table class="w-full text-xs">
        <thead>
          <tr class="text-left">
            <th>运行编号</th>
            <th>状态</th>
            <th>节点进度</th>
            <th>当前节点</th>
            <th>耗时</th>
            <th>结论</th>
            <th>命令</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="run in runs" :key="run.id">
            <td>#{{ run.id }}</td>
            <td>{{ formatRunStatus(run.status) }}</td>
            <td>{{ formatNodeProgress(run) }}</td>
            <td>{{ run.currentNodeKey || '—' }}</td>
            <td>{{ formatDuration(run.durationMs) }}</td>
            <td class="max-w-48 truncate">{{ describeRun(run) }}</td>
            <td>
              <button
                class="text-blue-600"
                type="button"
                @click="handleNodes(run)"
              >
                节点留痕
              </button>
            </td>
          </tr>
          <tr v-if="runs.length === 0">
            <td class="py-2 text-muted-foreground" colspan="7">
              还没有运行记录；流程停用或没有已发布版本时不会受理新运行。
            </td>
          </tr>
        </tbody>
      </table>

      <div v-if="selectedRunId">
        <p class="mb-1 text-sm font-medium">
          节点留痕 · 运行 #{{ selectedRunId }}（按执行顺序）
        </p>
        <table class="w-full text-xs">
          <thead>
            <tr class="text-left">
              <th>节点键</th>
              <th>类型</th>
              <th>状态</th>
              <th>输出摘要</th>
              <th>原因码</th>
              <th>耗时</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="node in nodes" :key="`${node.nodeKey}-${node.status}`">
              <td>{{ node.nodeKey }}</td>
              <td>{{ formatNodeType(node.nodeType) }}</td>
              <td>{{ formatNodeStatus(node.status) }}</td>
              <td class="max-w-56 truncate">{{ node.outputText || '—' }}</td>
              <td>{{ node.errorCode || '—' }}</td>
              <td>{{ formatDuration(node.durationMs) }}</td>
            </tr>
            <tr v-if="nodes.length === 0">
              <td class="py-2 text-muted-foreground" colspan="6">
                该运行没有节点留痕（运行未开始执行即受控结束）。
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <p v-if="errorMessage" class="text-xs text-red-600">{{ errorMessage }}</p>
    </div>
  </Modal>
</template>
