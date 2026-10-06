<script lang="ts" setup>
/**
 * 流程版本管理（X08）：草稿 → 发布 / 废弃。
 *
 * 版本是不可变快照：只有 DRAFT 可编辑，发布之后任何修改都必须新建草稿。
 * 草稿图是**声明式 JSON**（nodes/edges 受控契约），不是 SQL 也不是脚本——页面只提供
 * 一个 JSON 文本面，形状预检在 checkGraphJson 里做，拓扑判定交给发布期服务端。
 */
import type { AiWorkflowApi, AiWorkflowVersionApi } from '#/api/ai/workflow';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import {
  createWorkflowDraft,
  discardWorkflowDraft,
  getOpenWorkflowDraft,
  getWorkflowVersion,
  getWorkflowVersionPage,
  publishWorkflowVersion,
  updateWorkflowDraft,
} from '#/api/ai/workflow';
import { extractErrorMessage, showSuccessMessage } from '#/utils/feedback';

import {
  checkGraphJson,
  formatVersionStatus,
  WORKFLOW_GRAPH_EXAMPLE,
} from '../data';

const workflow = ref<AiWorkflowApi.Workflow>();
const versions = ref<AiWorkflowVersionApi.WorkflowVersion[]>([]);
const openDraft = ref<AiWorkflowVersionApi.WorkflowVersion>();
const graphJson = ref(WORKFLOW_GRAPH_EXAMPLE);
const viewJson = ref('');
const viewTitle = ref('');
const message = ref('');
const errorMessage = ref('');

const graphCheck = computed(() => checkGraphJson(graphJson.value));
/** 有打开的草稿时按钮变成"保存草稿"，避免用户重复建草稿撞上单开约束 */
const isSavingDraft = computed(() => Boolean(openDraft.value?.id));

async function loadVersions() {
  if (!workflow.value) {
    return;
  }
  const page = await getWorkflowVersionPage({
    pageNo: 1,
    pageSize: 20,
    workflowId: workflow.value.id,
  });
  versions.value = page.list ?? [];
}

async function loadOpenDraft() {
  if (!workflow.value) {
    return;
  }
  openDraft.value =
    (await getOpenWorkflowDraft(workflow.value.id)) ?? undefined;
  graphJson.value = openDraft.value?.graphJson ?? WORKFLOW_GRAPH_EXAMPLE;
}

/** 新建或保存草稿：服务端对"同一流程只允许一个打开的草稿"有强约束，这里对齐它 */
async function handleSaveDraft() {
  if (!workflow.value) {
    return;
  }
  if (!graphCheck.value.ok) {
    errorMessage.value = graphCheck.value.message;
    return;
  }
  errorMessage.value = '';
  const draft = openDraft.value;
  try {
    if (draft?.id) {
      await updateWorkflowDraft({
        graphJson: graphJson.value,
        version: draft.version,
        versionId: draft.id,
        workflowId: workflow.value.id,
      });
      showSuccessMessage('已保存草稿图');
    } else {
      await createWorkflowDraft({
        graphJson: graphJson.value,
        workflowId: workflow.value.id,
      });
      showSuccessMessage('已创建草稿');
    }
  } catch (error) {
    errorMessage.value = extractErrorMessage(error, '保存草稿失败');
    return;
  }
  message.value = '';
  await Promise.all([loadOpenDraft(), loadVersions()]);
}

async function handlePublish(version: AiWorkflowVersionApi.WorkflowVersion) {
  try {
    const versionNo = await publishWorkflowVersion({
      id: version.id,
      version: version.version,
    });
    message.value = `已发布版本 v${versionNo}`;
    errorMessage.value = '';
  } catch (error) {
    // 发布失败一定带稳定原因（环/无出口/类型不匹配/引用不存在），原样回显
    errorMessage.value = extractErrorMessage(error, '发布失败');
  }
  await loadVersions();
}

async function handleDiscard(version: AiWorkflowVersionApi.WorkflowVersion) {
  try {
    await discardWorkflowDraft({ id: version.id, version: version.version });
    showSuccessMessage('已废弃草稿');
    errorMessage.value = '';
  } catch (error) {
    errorMessage.value = extractErrorMessage(error, '废弃失败');
  }
  await Promise.all([loadOpenDraft(), loadVersions()]);
}

/** 把某个版本的图载入编辑区：只有草稿可写，已发布版本只能只读查看 */
async function handleEdit(version: AiWorkflowVersionApi.WorkflowVersion) {
  if (version.status !== 'DRAFT') {
    await handleView(version);
    return;
  }
  errorMessage.value = '';
  try {
    const detail = await getWorkflowVersion(version.id);
    openDraft.value = detail;
    graphJson.value = detail.graphJson ?? '';
    viewJson.value = '';
    viewTitle.value = '';
  } catch (error) {
    // 与 handleSaveDraft/handlePublish/handleDiscard 同一套口径：失败永远显示稳定原因码。
    // 不加这层捕获时，一次失败请求会变成没有用户可见消息的未捕获拒绝。
    errorMessage.value = extractErrorMessage(error, '载入草稿失败');
  }
}

async function handleView(version: AiWorkflowVersionApi.WorkflowVersion) {
  errorMessage.value = '';
  try {
    const detail = await getWorkflowVersion(version.id);
    viewTitle.value = `v${detail.versionNo}（${formatVersionStatus(detail.status)}）`;
    viewJson.value = detail.graphJson ?? '';
  } catch (error) {
    errorMessage.value = extractErrorMessage(error, '查看版本失败');
  }
}

const [Modal, modalApi] = useVbenModal({
  async onOpenChange(isOpen) {
    if (!isOpen) {
      return;
    }
    workflow.value = modalApi.getData<AiWorkflowApi.Workflow>();
    message.value = '';
    errorMessage.value = '';
    viewJson.value = '';
    viewTitle.value = '';
    await Promise.all([loadOpenDraft(), loadVersions()]);
  },
});
</script>

<template>
  <Modal
    :title="workflow ? `版本管理 · ${workflow.name}` : '版本管理'"
    class="w-[880px]"
  >
    <div class="flex flex-col gap-3">
      <div>
        <p class="mb-1 text-sm font-medium">
          草稿图（{{ isSavingDraft ? '编辑打开中的草稿' : '新建草稿' }}）
        </p>
        <textarea
          v-model="graphJson"
          class="h-32 w-full rounded border border-border p-2 text-xs"
        ></textarea>
        <p class="text-xs text-muted-foreground">
          声明式 JSON：nodes（key/type/name/config，type 限
          START/MODEL/KNOWLEDGE_RETRIEVAL/DATA_QUERY/TOOL/CONDITION/END）+
          edges（from/to，条件节点分支限 TRUE/FALSE）。
        </p>
        <p class="text-xs text-red-600">
          这是受控契约，不是
          SQL，也不是可执行脚本；页面不提供任何"执行任意脚本"入口。
        </p>
        <p
          class="mt-1 text-xs"
          :class="graphCheck.ok ? 'text-green-700' : 'text-red-600'"
        >
          {{ graphCheck.message }}
        </p>
        <button
          class="mt-1 text-sm text-blue-600"
          type="button"
          @click="handleSaveDraft"
        >
          {{ isSavingDraft ? '保存草稿图' : '创建草稿' }}
        </button>
      </div>

      <div v-if="viewTitle">
        <p class="mb-1 text-sm font-medium">只读查看：{{ viewTitle }}</p>
        <textarea
          class="h-28 w-full rounded border border-border bg-muted p-2 text-xs"
          readonly
          :value="viewJson"
        ></textarea>
      </div>

      <table class="w-full text-xs">
        <thead>
          <tr class="text-left">
            <th>版本</th>
            <th>状态</th>
            <th>节点/边</th>
            <th>图摘要</th>
            <th>发布时间</th>
            <th>命令</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="version in versions" :key="version.id">
            <td>v{{ version.versionNo }}</td>
            <td>{{ formatVersionStatus(version.status) }}</td>
            <td>{{ version.nodeCount ?? 0 }}/{{ version.edgeCount ?? 0 }}</td>
            <td class="max-w-28 truncate">
              {{ version.graphHash?.slice(0, 12) || '—' }}
            </td>
            <td>{{ version.publishedAt || '—' }}</td>
            <td class="whitespace-nowrap">
              <button
                class="mr-2 text-blue-600"
                type="button"
                @click="handleView(version)"
              >
                查看
              </button>
              <button
                v-if="version.status === 'DRAFT'"
                class="mr-2 text-blue-600"
                type="button"
                @click="handleEdit(version)"
              >
                编辑
              </button>
              <button
                v-if="version.status === 'DRAFT'"
                class="mr-2 text-blue-600 disabled:text-gray-400"
                type="button"
                @click="handlePublish(version)"
              >
                发布
              </button>
              <button
                v-if="version.status === 'DRAFT'"
                class="text-red-600"
                type="button"
                @click="handleDiscard(version)"
              >
                废弃
              </button>
            </td>
          </tr>
          <tr v-if="versions.length === 0">
            <td class="py-2 text-muted-foreground" colspan="6">
              还没有版本；先创建草稿并发布后才能被受理运行。
            </td>
          </tr>
        </tbody>
      </table>

      <p v-if="message" class="text-xs text-green-700">{{ message }}</p>
      <p v-if="errorMessage" class="text-xs text-red-600">{{ errorMessage }}</p>
    </div>
  </Modal>
</template>
