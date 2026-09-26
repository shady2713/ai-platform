<script lang="ts" setup>
import type { PanelFeedback } from '../data';

/**
 * 评测样例面板（Q05 套件管理下半段）：按 caseKey 列出样例，草稿套件内可增删改。
 *
 * `checksJson` 是 JSON **数组**：提交前本地解析并规范化，非法时给出可读错误且不提交
 * （kind 的合法取值由服务端判定，前端不拦，避免拦住后端新增的规则类型）；
 * 套件冻结（FROZEN）后本面板只读，调整必须回套件表创建新修订。
 */
import type { AiEvalApi } from '#/api/ai/evaluation';

import { computed, ref, watch } from 'vue';

import {
  ElAlert,
  ElButton,
  ElCard,
  ElDialog,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';

import {
  createCase,
  deleteCase,
  listCases,
  updateCase,
} from '#/api/ai/evaluation';

import { parseChecks, sortByCaseKey } from '../analysis';
import {
  AI_EVAL_PERMISSIONS,
  CHECKS_PLACEHOLDER,
  DEFAULT_CASE_FORM,
  describeSeverity,
  isSuiteEditable,
  SEVERITY_OPTIONS,
  severityTagType,
  trimmedOrUndefined,
  truncateText,
} from '../data';

const props = defineProps<{
  canManage: boolean;
  refreshKey: number;
  suite?: AiEvalApi.Suite;
}>();

const emit = defineEmits<{
  (e: 'changed' | 'clearFeedback'): void;
  (e: 'feedback', payload: PanelFeedback): void;
}>();

const cases = ref<AiEvalApi.Case[]>([]);
const caseOpen = ref(false);
const caseEditing = ref(false);
const caseEditId = ref<number>();
const caseEditVersion = ref<number>();
const caseSubmitting = ref(false);
const caseForm = ref({ ...DEFAULT_CASE_FORM });
const caseJsonError = ref('');

const sortedCases = computed(() => sortByCaseKey(cases.value));
const editable = computed(
  () => props.canManage && isSuiteEditable(props.suite?.status),
);
const caseTitle = computed(() => {
  const suite = props.suite;
  if (!suite) {
    return '评测样例：请先选择套件';
  }
  return `评测样例：${suite.code}（修订 ${suite.revision ?? '未知'}，共 ${
    sortedCases.value.length
  } 例）`;
});

async function refreshCases(): Promise<void> {
  const suiteId = props.suite?.id;
  if (suiteId === undefined) {
    cases.value = [];
    return;
  }
  try {
    cases.value = await listCases(suiteId);
  } catch {
    emit('feedback', {
      kind: 'error',
      message: `读取样例失败：请确认套件存在且当前账号有 ${AI_EVAL_PERMISSIONS.query} 权限`,
    });
    cases.value = [];
  }
}

// 套件切换或父页面要求刷新（冻结/新修订/复核等）时重新读取；只看这一个信号，避免重复请求
watch(
  () => [props.suite?.id, props.refreshKey],
  () => {
    void refreshCases();
  },
  { immediate: true },
);

function openCaseDialog(item?: AiEvalApi.Case): void {
  emit('clearFeedback');
  caseJsonError.value = '';
  caseEditId.value = item?.id;
  caseEditVersion.value = item?.version;
  if (item === undefined) {
    caseEditing.value = false;
    caseForm.value = { ...DEFAULT_CASE_FORM };
  } else {
    caseEditing.value = true;
    caseForm.value = {
      caseKey: item.caseKey,
      checksJson: item.checksJson,
      expectVersion: item.expectVersion ?? '',
      needsReview: item.needsReview === true,
      question: item.question,
      severity: item.severity,
      title: item.title,
    };
  }
  caseOpen.value = true;
}

async function submitCase(): Promise<void> {
  const suiteId = props.suite?.id;
  if (suiteId === undefined) {
    emit('feedback', { kind: 'error', message: '请先选择套件' });
    return;
  }
  const parsed = parseChecks(caseForm.value.checksJson);
  if (!parsed.ok) {
    caseJsonError.value = parsed.message;
    return;
  }
  caseJsonError.value = '';
  emit('clearFeedback');
  caseSubmitting.value = true;
  try {
    const form = caseForm.value;
    const caseKey = form.caseKey.trim();
    if (
      caseKey === '' ||
      form.title.trim() === '' ||
      form.question.trim() === ''
    ) {
      emit('feedback', {
        kind: 'error',
        message: '样例标识、标题与合成问题都必须填写',
      });
      return;
    }
    const editId = caseEditId.value;
    const editVersion = caseEditVersion.value;
    const payload: AiEvalApi.CaseSaveReq = {
      caseKey,
      checksJson: parsed.value,
      expectVersion: trimmedOrUndefined(form.expectVersion),
      needsReview: form.needsReview,
      question: form.question.trim(),
      severity: form.severity,
      suiteId,
      title: form.title.trim(),
    };
    if (
      caseEditing.value &&
      editId !== undefined &&
      editVersion !== undefined
    ) {
      payload.id = editId;
      payload.version = editVersion;
      await updateCase(payload);
      emit('feedback', {
        kind: 'notice',
        message: `样例 ${caseKey} 已更新（期望规则已按数组规范化后提交）`,
      });
    } else {
      await createCase(payload);
      emit('feedback', {
        kind: 'notice',
        message: `样例 ${caseKey} 已新增：冻结后它才进入运行快照`,
      });
    }
    caseOpen.value = false;
    await refreshCases();
    // 套件行的冻结样例数（caseCount）由父页面按同一接口重新读取
    emit('changed');
  } catch {
    emit('feedback', {
      kind: 'error',
      message: `保存样例失败：期望规则必须是合规的 JSON 数组（最多 32 条），且需要 ${AI_EVAL_PERMISSIONS.manage} 权限`,
    });
  } finally {
    caseSubmitting.value = false;
  }
}

async function removeCase(item: AiEvalApi.Case): Promise<void> {
  emit('clearFeedback');
  try {
    await deleteCase({ id: item.id, version: item.version });
    emit('feedback', {
      kind: 'notice',
      message: `样例 ${item.caseKey} 已删除（历史运行仍保留当时的快照）`,
    });
    await refreshCases();
    emit('changed');
  } catch {
    emit('feedback', {
      kind: 'error',
      message: `删除样例失败：只有草稿套件可删除，且需要 ${AI_EVAL_PERMISSIONS.manage} 权限`,
    });
  }
}
</script>

<template>
  <ElCard class="mb-4" :header="caseTitle">
    <ElEmpty v-if="suite === undefined" description="请先在上方选择套件" />
    <template v-else>
      <div class="mb-3 flex flex-wrap items-center gap-3">
        <ElButton
          v-if="editable"
          data-testid="ai-eval-case-create"
          type="primary"
          @click="openCaseDialog()"
        >
          新增样例
        </ElButton>
        <ElTag
          v-if="!isSuiteEditable(suite.status)"
          data-testid="ai-eval-suite-frozen-hint"
          type="info"
        >
          套件已冻结（修订
          {{ suite.revision }}）：样例不可增删改，请先创建新修订
        </ElTag>
      </div>
      <ElEmpty v-if="sortedCases.length === 0" description="该套件还没有样例" />
      <ElTable
        v-else
        data-testid="ai-eval-case-table"
        :data="sortedCases"
        size="small"
      >
        <ElTableColumn label="样例标识" prop="caseKey" min-width="140" />
        <ElTableColumn label="标题" prop="title" min-width="160" />
        <ElTableColumn label="级别" min-width="130">
          <template #default="{ row }">
            <ElTag size="small" :type="severityTagType(row.severity)">
              {{ describeSeverity(row.severity) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="需人工复核" width="110">
          <template #default="{ row }">
            {{ row.needsReview ? '需要' : '不需要' }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="期望版本" min-width="130">
          <template #default="{ row }">
            {{ row.expectVersion ?? '未指定' }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="期望规则（JSON 数组）" min-width="240">
          <template #default="{ row }">
            {{ truncateText(row.checksJson, 60) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="行版本" prop="version" width="80" />
        <ElTableColumn v-if="canManage" fixed="right" label="操作" width="140">
          <template #default="{ row }">
            <ElButton
              v-if="isSuiteEditable(suite.status)"
              link
              type="primary"
              @click="openCaseDialog(row)"
            >
              编辑
            </ElButton>
            <ElButton
              v-if="isSuiteEditable(suite.status)"
              link
              type="danger"
              @click="removeCase(row)"
            >
              删除
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
    </template>

    <ElDialog
      v-model="caseOpen"
      :title="caseEditing ? '编辑样例（仅草稿套件）' : '新增样例（草稿套件）'"
      width="640px"
    >
      <ElForm label-width="110px">
        <ElFormItem label="样例标识">
          <ElInput
            v-model="caseForm.caseKey"
            data-testid="ai-eval-case-key"
            placeholder="套件内唯一，执行顺序按它升序"
          />
        </ElFormItem>
        <ElFormItem label="标题">
          <ElInput v-model="caseForm.title" data-testid="ai-eval-case-title" />
        </ElFormItem>
        <ElFormItem label="严重级别">
          <ElSelect
            v-model="caseForm.severity"
            data-testid="ai-eval-case-severity"
            style="width: 100%"
          >
            <ElOption
              v-for="item in SEVERITY_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="合成问题">
          <ElInput
            v-model="caseForm.question"
            data-testid="ai-eval-case-question"
            placeholder="禁止真实客户数据"
            type="textarea"
          />
        </ElFormItem>
        <ElFormItem label="期望版本">
          <ElInput
            v-model="caseForm.expectVersion"
            data-testid="ai-eval-case-expect-version"
            placeholder="可空；VERSION 规则也会核验它"
          />
        </ElFormItem>
        <ElFormItem label="期望规则">
          <ElInput
            v-model="caseForm.checksJson"
            data-testid="ai-eval-case-checks"
            :placeholder="CHECKS_PLACEHOLDER"
            :rows="5"
            type="textarea"
          />
        </ElFormItem>
        <ElFormItem label="人工复核">
          <ElSelect
            v-model="caseForm.needsReview"
            data-testid="ai-eval-case-needs-review"
            style="width: 100%"
          >
            <ElOption label="不需要（规则全通过即通过）" :value="false" />
            <ElOption label="需要（先进入等待复核）" :value="true" />
          </ElSelect>
        </ElFormItem>
      </ElForm>
      <ElAlert
        v-if="caseJsonError"
        data-testid="ai-eval-case-error"
        :closable="false"
        :title="caseJsonError"
        type="error"
      />
      <template #footer>
        <ElButton @click="caseOpen = false">取消</ElButton>
        <ElButton
          data-testid="ai-eval-case-submit"
          :loading="caseSubmitting"
          type="primary"
          @click="submitCase"
        >
          {{ caseEditing ? '保存样例' : '新增样例' }}
        </ElButton>
      </template>
    </ElDialog>
  </ElCard>
</template>
