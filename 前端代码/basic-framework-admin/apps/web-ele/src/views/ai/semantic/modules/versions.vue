<script lang="ts" setup>
/**
 * 映射版本与条目编辑弹窗（Y02）：草稿 → 登记源键 → 独立发布。
 *
 * 页面把服务端的事实原样展示：`publishable`、`conflictKeys` 与逐条 `problem` 都来自后端，
 * 前端不重算、不隐藏冲突；发布失败（409）按错误码文案提示，由操作员处理冲突后重试。
 */
import type { AiSemanticApi } from '#/api/ai/semantic';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { useVbenForm } from '#/adapter/form';
import {
  createMappingEntry,
  createRevision,
  deleteMappingEntry,
  getRevisionDetail,
  getRevisionPage,
  publishRevision,
} from '#/api/ai/semantic';
import { showRequestError, showSuccessMessage } from '#/utils/feedback';

import {
  BLOCKING_HINT,
  formatWindow,
  PROBLEM_LABELS,
  problemTagType,
  REVISION_STATUS_LABELS,
  useEntryFormSchema,
  useRevisionFormSchema,
  VERSION_PIN_HINT,
} from '../data';

interface EntryFormValues {
  applicationId: number;
  entityType: string;
  matchMethod: AiSemanticApi.MatchMethod;
  sourceKey: string;
  sourceName?: string;
  validFrom: string;
  validTo?: string;
}

interface RevisionFormValues {
  validFrom: string;
  validTo?: string;
}

const masterObject = ref<AiSemanticApi.MasterObject>();
const revisions = ref<AiSemanticApi.Revision[]>([]);
const detail = ref<AiSemanticApi.RevisionDetail>();
const loading = ref(false);

const draftRevision = computed(() =>
  revisions.value.find((revision) => revision.status === 'DRAFT'),
);

const [RevisionForm, revisionFormApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' } },
  layout: 'horizontal',
  schema: useRevisionFormSchema(),
  showDefaultActions: false,
  wrapperClass: 'grid-cols-1',
});

const [EntryForm, entryFormApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' } },
  layout: 'horizontal',
  schema: useEntryFormSchema(),
  showDefaultActions: false,
  wrapperClass: 'grid-cols-1',
});

const [Modal, modalApi] = useVbenModal({
  onOpenChange(isOpen: boolean) {
    if (isOpen) {
      masterObject.value = modalApi.getData<AiSemanticApi.MasterObject>();
      detail.value = undefined;
      modalApi.setState({
        title: `映射版本：${masterObject.value?.objectCode ?? ''}`,
      });
      void loadRevisions();
      return;
    }
    masterObject.value = undefined;
    revisions.value = [];
    detail.value = undefined;
  },
  showConfirmButton: false,
});

async function loadRevisions() {
  if (!masterObject.value?.id) {
    return;
  }
  loading.value = true;
  try {
    const page = await getRevisionPage({
      masterObjectId: masterObject.value.id,
      pageNo: 1,
      pageSize: 20,
    });
    revisions.value = page.list ?? [];
    const latest = revisions.value[0];
    if (latest) {
      await handleSelect(latest.revisionNo);
    }
  } catch (error) {
    showRequestError(error, '映射版本加载失败');
  } finally {
    loading.value = false;
  }
}

async function handleSelect(revisionNo: number) {
  if (!masterObject.value?.id) {
    return;
  }
  try {
    detail.value = await getRevisionDetail(masterObject.value.id, revisionNo);
  } catch (error) {
    showRequestError(error, '版本详情加载失败');
  }
}

async function handleCreateRevision() {
  if (!masterObject.value?.id) {
    return;
  }
  const { valid } = await revisionFormApi.validate();
  if (!valid) {
    return;
  }
  const values = (await revisionFormApi.getValues()) as RevisionFormValues;
  modalApi.lock();
  try {
    const revisionNo = await createRevision({
      masterObjectId: masterObject.value.id,
      validFrom: values.validFrom,
      validTo: values.validTo || undefined,
    });
    showSuccessMessage(`已创建草稿版本 v${revisionNo}，请登记源键`);
    revisionFormApi.resetForm();
    await loadRevisions();
    await handleSelect(revisionNo);
  } catch (error) {
    showRequestError(error, '草稿创建失败');
  } finally {
    modalApi.unlock();
  }
}

async function handleAddEntry() {
  if (!masterObject.value?.id || !draftRevision.value) {
    showRequestError(new Error('请先创建或选中草稿版本'), '登记被拒绝');
    return;
  }
  const { valid } = await entryFormApi.validate();
  if (!valid) {
    return;
  }
  const values = (await entryFormApi.getValues()) as EntryFormValues;
  modalApi.lock();
  try {
    await createMappingEntry({
      applicationId: Number(values.applicationId),
      entityType: values.entityType,
      masterObjectId: masterObject.value.id,
      matchMethod: values.matchMethod,
      revisionNo: draftRevision.value.revisionNo,
      sourceKey: values.sourceKey.trim(),
      sourceName: values.sourceName?.trim(),
      validFrom: values.validFrom,
      validTo: values.validTo || undefined,
    });
    showSuccessMessage('已登记源键（同名不会自动合并）');
    entryFormApi.resetForm();
    await handleSelect(draftRevision.value.revisionNo);
  } catch (error) {
    showRequestError(error, '登记被拒绝');
  } finally {
    modalApi.unlock();
  }
}

async function handleDeleteEntry(entry: AiSemanticApi.MappingEntry) {
  modalApi.lock();
  try {
    await deleteMappingEntry(entry.id, entry.version);
    showSuccessMessage('条目已删除（仅草稿可编辑）');
    if (detail.value) {
      await handleSelect(detail.value.revision.revisionNo);
    }
  } catch (error) {
    showRequestError(error, '删除被拒绝');
  } finally {
    modalApi.unlock();
  }
}

async function handlePublish() {
  if (!masterObject.value?.id || !detail.value) {
    return;
  }
  modalApi.lock();
  try {
    await publishRevision(
      masterObject.value.id,
      detail.value.revision.revisionNo,
      detail.value.revision.version,
    );
    showSuccessMessage('版本已发布：内容与指纹已冻结，判定按该版本解释');
    await loadRevisions();
  } catch (error) {
    showRequestError(error, '发布被拒绝：请先处理冲突/过期映射');
  } finally {
    modalApi.unlock();
  }
}
</script>

<template>
  <Modal :loading="loading">
    <div class="space-y-4">
      <RevisionForm />
      <EntryForm />
      <div class="flex flex-wrap items-center gap-2">
        <button
          class="btn btn-primary"
          data-test="create-revision"
          type="button"
          @click="handleCreateRevision"
        >
          新建草稿版本
        </button>
        <button
          class="btn btn-primary"
          data-test="add-entry"
          type="button"
          @click="handleAddEntry"
        >
          登记源键映射
        </button>
        <button
          class="btn"
          :disabled="!detail || !detail.publishable"
          data-test="publish"
          type="button"
          @click="handlePublish"
        >
          发布选中版本
        </button>
      </div>

      <p class="text-xs text-muted-foreground">{{ VERSION_PIN_HINT }}</p>
      <p class="text-xs text-muted-foreground">{{ BLOCKING_HINT }}</p>

      <ul class="space-y-1" data-test="revision-list">
        <li
          v-for="revision in revisions"
          :key="revision.revisionNo"
          :data-test="`revision-${revision.revisionNo}`"
        >
          <button
            class="link"
            type="button"
            @click="handleSelect(revision.revisionNo)"
          >
            v{{ revision.revisionNo }} ·
            {{ REVISION_STATUS_LABELS[revision.status] }} ·
            {{ formatWindow(revision.validFrom, revision.validTo) }}
          </button>
        </li>
      </ul>

      <div v-if="detail" data-test="revision-detail">
        <p data-test="publishable">
          {{
            detail.publishable
              ? '当前版本可直接发布'
              : '当前版本不可发布（草稿未建/无条目/存在冲突）'
          }}
        </p>
        <p v-if="detail.conflictKeys.length > 0" data-test="conflict-keys">
          冲突键：{{ detail.conflictKeys.join('、') }}
        </p>
        <p data-test="fingerprint">
          冻结指纹：{{
            detail.revision.mappingFingerprint || '（草稿尚未冻结）'
          }}
        </p>
        <ul data-test="entry-list">
          <li
            v-for="entry in detail.entries"
            :key="entry.id"
            :data-test="`entry-${entry.id}`"
          >
            <span>{{ entry.sourceKey }}（{{ entry.entityType }}）</span>
            <span :class="`tag tag-${problemTagType(entry.problem)}`">
              {{ PROBLEM_LABELS[entry.problem] ?? entry.problem }}
            </span>
            <span>{{ formatWindow(entry.validFrom, entry.validTo) }}</span>
            <button
              v-if="detail.revision.status === 'DRAFT'"
              class="link"
              type="button"
              @click="handleDeleteEntry(entry)"
            >
              删除
            </button>
          </li>
        </ul>
        <p v-if="detail.entries.length === 0" data-test="empty-entries">
          该版本还没有登记任何源键：未映射即不关联。
        </p>
      </div>
    </div>
  </Modal>
</template>
