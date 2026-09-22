<script lang="ts" setup>
/**
 * 知识文档（K09）：上传入库、状态/版本查看、失败重试、删除、原文预览。
 *
 * 上传是"文件 + 幂等键 + 标题"三件套：幂等键决定复用还是新版本（后端按指纹判断），
 * 界面不提供"强制覆盖"旁路；失败状态给出可读原因码并允许人工重试（仅失败/结果未知）。
 */
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { AiKnowledgeApi } from '#/api/ai/knowledge';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteDocument,
  getDocumentPage,
  getDocumentVersionPage,
  getIngestionTaskPage,
  readDocumentContent,
  retryIngestionTask,
  uploadDocument,
} from '#/api/ai/knowledge';
import { $t } from '#/locales';
import { showSuccessMessage } from '#/utils/feedback';

import {
  AI_KNOWLEDGE_PERMISSIONS,
  describeFailureReason,
  needsAttention,
  useDocumentGridColumns,
  useDocumentGridFormSchema,
} from '../data';

const knowledgeBaseId = ref<number>();
const uploading = ref(false);
const uploadForm = ref<{ file?: File; sourceKey: string; title: string }>({
  sourceKey: '',
  title: '',
});
const versions = ref<AiKnowledgeApi.DocumentVersion[]>([]);
const tasks = ref<AiKnowledgeApi.IngestionTask[]>([]);

const [Modal, modalApi] = useVbenModal({
  footer: false,
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      knowledgeBaseId.value = undefined;
      versions.value = [];
      tasks.value = [];
      uploadForm.value = { sourceKey: '', title: '' };
      return;
    }
    // 归属来自父页面传入的知识库行：文档只能挂在它下面
    knowledgeBaseId.value =
      modalApi.getData<AiKnowledgeApi.KnowledgeBase>()?.id;
    gridApi.query();
  },
});

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: { schema: useDocumentGridFormSchema() },
  gridOptions: {
    columns: useDocumentGridColumns(),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          return await getDocumentPage({
            knowledgeBaseId: knowledgeBaseId.value,
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
      },
    },
    rowConfig: { keyField: 'id' },
    rowClassName: ({ row }) => (needsAttention(row) ? 'text-amber-600' : ''),
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<AiKnowledgeApi.Document>,
});

/** 选择待上传文件（文件输入与拖拽共用同一入口）。 */
function setUploadFile(file?: File) {
  uploadForm.value.file = file;
}

function handleUpload() {
  const file = uploadForm.value.file;
  if (!file || !knowledgeBaseId.value) {
    return;
  }
  uploading.value = true;
  uploadDocument({
    file,
    knowledgeBaseId: knowledgeBaseId.value,
    sourceKey: uploadForm.value.sourceKey || file.name,
    title: uploadForm.value.title || file.name,
  })
    .then((result) => {
      showSuccessMessage(
        result.createdVersion
          ? `已入库并排队处理（版本 v${result.versionNo}）`
          : `内容未变化，复用既有版本（v${result.versionNo}）`,
      );
      uploadForm.value = { sourceKey: '', title: '' };
      gridApi.query();
    })
    .finally(() => {
      uploading.value = false;
    });
}

async function handleVersions(row: AiKnowledgeApi.Document) {
  const page = await getDocumentVersionPage({
    documentId: row.id,
    pageNo: 1,
    pageSize: 20,
  });
  versions.value = page.list;
}

async function handleTasks(row: AiKnowledgeApi.Document) {
  const page = await getIngestionTaskPage({
    knowledgeBaseId: knowledgeBaseId.value,
    pageNo: 1,
    pageSize: 20,
  });
  tasks.value = page.list.filter((task) => task.documentId === row.id);
}

async function handleRetry(row: AiKnowledgeApi.Document) {
  const page = await getIngestionTaskPage({
    knowledgeBaseId: knowledgeBaseId.value,
    pageNo: 1,
    pageSize: 20,
  });
  const task = page.list.find(
    (item) => item.documentId === row.id && item.status !== 'SUCCEEDED',
  );
  if (!task) {
    showSuccessMessage('没有可重试的任务');
    return;
  }
  await retryIngestionTask(task.id, task.version);
  showSuccessMessage('已重新排队');
  gridApi.query();
}

async function handleDelete(row: AiKnowledgeApi.Document) {
  await deleteDocument(row.id, row.version);
  showSuccessMessage('已撤销可见性，后台回收中');
  gridApi.query();
}

async function handlePreview(row: AiKnowledgeApi.Document) {
  const blob = await readDocumentContent(row.id);
  const url = URL.createObjectURL(blob);
  window.open(url, '_blank');
}

defineExpose({
  handleDelete,
  setUploadFile,
  handlePreview,
  handleRetry,
  handleTasks,
  handleUpload,
  handleVersions,
  tasks,
  uploadForm,
  versions,
});

modalApi.setState({ title: '知识文档' });
</script>

<template>
  <Modal class="w-[1000px]">
    <div class="mb-4 rounded border border-dashed p-3">
      <div class="mb-2 font-medium">上传入库（幂等键决定复用或新版本）</div>
      <div class="flex flex-wrap items-center gap-2">
        <input
          class="rounded border px-2 py-1"
          placeholder="来源幂等键（默认取文件名）"
          v-model="uploadForm.sourceKey"
        />
        <input
          class="rounded border px-2 py-1"
          placeholder="标题（默认取文件名）"
          v-model="uploadForm.title"
        />
        <input
          type="file"
          @change="
            (event) =>
              setUploadFile((event.target as HTMLInputElement).files?.[0])
          "
        />
        <TableAction
          :actions="[
            {
              label: uploading ? '上传中…' : '上传并入库',
              type: 'primary',
              icon: ACTION_ICON.UPLOAD,
              auth: [AI_KNOWLEDGE_PERMISSIONS.ingest],
              disabled: uploading,
              onClick: handleUpload,
            },
          ]"
        />
      </div>
      <div class="mt-1 text-xs text-gray-500">
        支持 TXT / Markdown / 文本型 PDF / DOCX；扫描件会提示需要
        OCR（首期不支持）。
      </div>
    </div>
    <Grid table-title="文档">
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '版本',
              icon: ACTION_ICON.VIEW,
              auth: [AI_KNOWLEDGE_PERMISSIONS.version],
              onClick: handleVersions.bind(null, row),
            },
            {
              label: '任务',
              icon: ACTION_ICON.VIEW,
              auth: [AI_KNOWLEDGE_PERMISSIONS.query],
              onClick: handleTasks.bind(null, row),
            },
            {
              label: '重试',
              icon: ACTION_ICON.REFRESH,
              auth: [AI_KNOWLEDGE_PERMISSIONS.ingest],
              ifShow: () => true,
              onClick: handleRetry.bind(null, row),
            },
            {
              label: '预览原文',
              icon: ACTION_ICON.VIEW,
              auth: [AI_KNOWLEDGE_PERMISSIONS.query],
              onClick: handlePreview.bind(null, row),
            },
          ]"
          :drop-down-actions="[
            {
              label: $t('ui.actionTitle.delete'),
              icon: ACTION_ICON.DELETE,
              auth: [AI_KNOWLEDGE_PERMISSIONS.delete],
              popConfirm: {
                title: '删除后检索立即不可见，确认？',
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>
    <div v-if="versions.length > 0" class="mt-4">
      <div class="mb-1 font-medium">版本</div>
      <ul class="text-sm">
        <li v-for="version in versions" :key="version.id">
          v{{ version.versionNo }} · {{ version.status }} · 切片
          {{ version.chunkCount ?? 0 }} · 索引代
          {{ version.indexGeneration ?? '-' }} ·
          {{ describeFailureReason(version.failureReason) }}
        </li>
      </ul>
    </div>
    <div v-if="tasks.length > 0" class="mt-4">
      <div class="mb-1 font-medium">入库任务</div>
      <ul class="text-sm">
        <li v-for="task in tasks" :key="task.id">
          {{ task.taskKind }} · {{ task.status }} · 尝试
          {{ task.attemptCount }}/{{ task.maxAttempts }} ·
          {{ describeFailureReason(task.lastErrorCode) }}
        </li>
      </ul>
    </div>
  </Modal>
</template>
