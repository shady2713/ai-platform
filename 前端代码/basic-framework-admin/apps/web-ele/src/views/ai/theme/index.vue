<script lang="ts" setup>
/**
 * 主题管理页（C09，菜单 4105 对应的控制面页面）。
 *
 * 页面只提供真实存在的能力：查询修订、新建修订、发布/回退、查看有效主题与字体白名单。
 * 修订发布后**不可修改**（服务端强约束），因此界面不提供"编辑已发布修订"，而是引导新建修订；
 * 已被取代的修订可以重新发布，即回退（同一服务端动作）。
 */
import type { AiThemeApi } from '#/api/ai/chat';

import { computed, onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  ElAlert,
  ElButton,
  ElCard,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';

import { getApplicationPage } from '#/api/ai/application';
import {
  createTheme,
  getEffectiveTheme,
  getThemeFonts,
  getThemePage,
  publishTheme,
} from '#/api/ai/chat';

import {
  canPublish,
  DEFAULT_THEME_FORM,
  layoutSummary,
  publicationText,
  publishActionText,
  tokenSummary,
  toTokensJson,
} from './data';

const applications = ref<Array<{ appCode: string; id: number; name: string }>>(
  [],
);
const applicationId = ref<number>();
const revisions = ref<AiThemeApi.Theme[]>([]);
const effective = ref<AiThemeApi.EffectiveTheme>();
const fonts = ref<string[]>([]);
const form = ref({ ...DEFAULT_THEME_FORM, colorScheme: 'light' as string });
const loading = ref(false);
const error = ref('');
const notice = ref('');

const darkSupported = computed(() => form.value.colorScheme === 'dark');

async function loadApplications(): Promise<void> {
  const page = await getApplicationPage({ pageNo: 1, pageSize: 100 });
  applications.value = page.list.map((item) => ({
    appCode: item.appCode,
    id: item.id,
    name: item.name,
  }));
  applicationId.value ??= applications.value[0]?.id;
}

async function refresh(): Promise<void> {
  if (applicationId.value === undefined) {
    return;
  }
  loading.value = true;
  error.value = '';
  try {
    const [page, resolved] = await Promise.all([
      getThemePage({
        applicationId: applicationId.value,
        pageNo: 1,
        pageSize: 50,
      }),
      getEffectiveTheme(applicationId.value),
    ]);
    revisions.value = page.list;
    effective.value = resolved;
  } catch {
    // 失败不伪装成功：给出明确提示，界面保持上一次的数据
    error.value =
      '读取主题失败：请确认应用存在且当前账号有 ai:theme:query 权限';
  } finally {
    loading.value = false;
  }
}

async function submitRevision(): Promise<void> {
  if (applicationId.value === undefined) {
    return;
  }
  error.value = '';
  notice.value = '';
  try {
    await createTheme({
      applicationId: applicationId.value,
      tokensJson: toTokensJson({
        colorScheme: form.value.colorScheme,
        fontFamily: form.value.fontFamily,
        primaryColor: form.value.primaryColor,
        radius: form.value.radius,
      }),
    });
    notice.value =
      '已创建新修订（草稿）。发布后内容不可修改，调整请再建新修订。';
    await refresh();
  } catch {
    error.value =
      '创建修订失败：色值/半径/字体必须符合主题契约（字体必须是白名单内的自托管字体栈）';
  }
}

async function publish(row: AiThemeApi.Theme): Promise<void> {
  error.value = '';
  notice.value = '';
  try {
    await publishTheme({ id: row.id, version: row.version });
    notice.value = `修订 ${row.revision} 已发布；此前的生效修订自动转为"已被取代"。`;
    await refresh();
  } catch {
    error.value = '发布失败：可能已被其他操作变更，请刷新后重试';
  }
}

onMounted(async () => {
  await loadApplications();
  fonts.value = await getThemeFonts().catch(() => []);
  await refresh();
});
</script>

<template>
  <Page title="AI 主题" description="应用品牌 token 与布局选项的版本化发布">
    <ElAlert
      :closable="false"
      class="mb-4"
      show-icon
      title="主题按修订发布：发布后内容不可修改，任何调整都新建修订；回退 = 把历史修订重新发布。"
      type="info"
    />

    <ElAlert
      v-if="error"
      :closable="false"
      class="mb-4"
      :title="error"
      type="error"
    />
    <ElAlert
      v-if="notice"
      :closable="false"
      class="mb-4"
      :title="notice"
      type="success"
    />

    <ElCard class="mb-4" header="应用与有效主题">
      <ElForm inline>
        <ElFormItem label="应用">
          <ElSelect v-model="applicationId" class="w-60" @change="refresh">
            <ElOption
              v-for="application in applications"
              :key="application.id"
              :label="`${application.name}（${application.appCode}）`"
              :value="application.id"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem>
          <ElButton :loading="loading" @click="refresh">刷新</ElButton>
        </ElFormItem>
      </ElForm>
      <div v-if="effective" class="text-sm">
        <p>
          来源：<ElTag size="small">{{ effective.source }}</ElTag>
          <span v-if="effective.revision !== undefined">
            · 修订 {{ effective.revision }} · 指纹
            {{ effective.fingerprint.slice(0, 12) }}…
          </span>
        </p>
        <p>Token：{{ tokenSummary(effective.tokensJson) }}</p>
        <p>布局：{{ layoutSummary(effective.layoutJson) }}</p>
      </div>
    </ElCard>

    <ElCard class="mb-4" header="新建修订（草稿）">
      <ElForm label-width="96px">
        <ElFormItem label="主色">
          <ElInput v-model="form.primaryColor" placeholder="#1677ff" />
        </ElFormItem>
        <ElFormItem label="圆角">
          <ElInputNumber v-model="form.radius" :max="24" :min="0" />
        </ElFormItem>
        <ElFormItem label="字体">
          <ElSelect v-model="form.fontFamily" class="w-96">
            <ElOption
              v-for="font in fonts"
              :key="font"
              :label="font"
              :value="font"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="深浅色">
          <ElSelect v-model="form.colorScheme" class="w-40">
            <ElOption label="浅色" value="light" />
            <ElOption label="深色" value="dark" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem>
          <ElButton type="primary" @click="submitRevision">创建修订</ElButton>
        </ElFormItem>
      </ElForm>
      <p class="text-xs text-muted-foreground">
        预览以深色为
        {{ darkSupported ? '深色' : '浅色' }}：主色
        {{ form.primaryColor }}、圆角 {{ form.radius }}px；
        实际预览请打开应用嵌入页（由应用端读取已发布主题）。
      </p>
    </ElCard>

    <ElCard header="修订历史">
      <ElTable :data="revisions" empty-text="该应用暂无主题修订">
        <ElTableColumn label="修订" prop="revision" width="80" />
        <ElTableColumn label="状态" width="120">
          <template #default="{ row }">
            <ElTag size="small">
              {{ publicationText(row.publicationState) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="Token" min-width="220">
          <template #default="{ row }">
            {{ tokenSummary(row.tokensJson) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="布局" min-width="220">
          <template #default="{ row }">
            {{ layoutSummary(row.layoutJson) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="140">
          <template #default="{ row }">
            <ElButton
              v-if="canPublish(row.publicationState)"
              link
              type="primary"
              @click="publish(row)"
            >
              {{ publishActionText(row.publicationState) }}
            </ElButton>
            <span v-else class="text-xs text-muted-foreground">当前生效</span>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>
  </Page>
</template>
