<script lang="ts" setup>
/**
 * Webhook 投递控制台（X10，菜单 4124 对应的页面）。
 *
 * 三件事在一屏内做完：
 *  - **目标维护**：登记"把哪些运行终态事件投给谁"，密钥只提交不回显（只显示是否已配置与版本）；
 *  - **停用即停发**：停用按钮走 `update-status`，停用后不再入队、在途投递按事实收尾；
 *  - **死信处置**：投递按状态过滤（FAILED 即死信），详情给出每一次尝试的结论与稳定原因码，
 *    人工重投需要 `ai:webhook:redeliver`，服务端还会要求目标启用。
 */
import type { AiWebhookApi } from './api';

import { computed, onMounted, ref } from 'vue';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';

import {
  ElAlert,
  ElButton,
  ElCard,
  ElDialog,
  ElEmpty,
  ElInput,
  ElInputNumber,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';

import { getApplicationPage } from '#/api/ai/application';

import {
  createTarget,
  deleteTarget,
  getDeliveryAttempts,
  getDeliveryPage,
  getTargetPage,
  redeliverDelivery,
  rotateTargetSecret,
  updateTargetStatus,
} from './api';
import {
  AI_WEBHOOK_PERMISSIONS,
  buildTargetPayload,
  canRedeliver,
  DELIVERY_STATUS_OPTIONS,
  describeAttemptOutcome,
  describeDeliveryStatus,
  describeEventType,
  describeFailureCode,
  describeTargetStatus,
  secretRotationHint,
  shortenDigest,
  summarizeDeliveries,
  TARGET_STATUS_OPTIONS,
  validateEventTypes,
  validateMaxAttempts,
  validateSecret,
  validateTargetCode,
  validateTargetUrl,
  WEBHOOK_EVENT_OPTIONS,
} from './data';

const { hasAccessByCodes } = useAccess();

const applications = ref<Array<{ id: number; name: string }>>([]);
const applicationId = ref<number>();
const targetStatus = ref<string>();

const targets = ref<AiWebhookApi.Target[]>([]);
const targetTotal = ref(0);
const targetPageNo = ref(1);
const targetPageSize = ref(10);

const deliveries = ref<AiWebhookApi.Delivery[]>([]);
const deliveryTotal = ref(0);
const deliveryPageNo = ref(1);
const deliveryPageSize = ref(10);
const deliveryStatus = ref<string>();
const deliveryEventType = ref<string>();
const deliveryTargetId = ref<number>();

const attempts = ref<AiWebhookApi.DeliveryAttempt[]>([]);
const selectedDelivery = ref<AiWebhookApi.Delivery>();

const loading = ref(false);
const error = ref('');
const notice = ref('');

const createOpen = ref(false);
const createRunning = ref(false);
const form = ref<{
  code: string;
  eventTypes: string[];
  maxAttempts: number;
  name: string;
  secret: string;
  targetUrl: string;
}>({
  code: '',
  eventTypes: ['RUN.SUCCEEDED', 'RUN.FAILED'],
  maxAttempts: 3,
  name: '',
  secret: '',
  targetUrl: '',
});
const formError = ref('');

const rotateOpen = ref(false);
const rotateTarget = ref<AiWebhookApi.Target>();
const rotateSecret = ref('');
const rotateError = ref('');

const summary = computed(() => summarizeDeliveries(deliveries.value));
const canManage = computed(() =>
  hasAccessByCodes([AI_WEBHOOK_PERMISSIONS.manage]),
);
const canRotate = computed(() =>
  hasAccessByCodes([AI_WEBHOOK_PERMISSIONS.rotate]),
);
const canDelete = computed(() =>
  hasAccessByCodes([AI_WEBHOOK_PERMISSIONS.delete]),
);
const canRedeliverDelivery = computed(() =>
  hasAccessByCodes([AI_WEBHOOK_PERMISSIONS.redeliver]),
);

async function loadApplications(): Promise<void> {
  const page = await getApplicationPage({ pageNo: 1, pageSize: 100 });
  applications.value = page.list.map((item) => ({
    id: item.id,
    name: `${item.name}（${item.appCode}）`,
  }));
}

async function loadTargets(): Promise<void> {
  const page = await getTargetPage({
    applicationId: applicationId.value,
    pageNo: targetPageNo.value,
    pageSize: targetPageSize.value,
    status: targetStatus.value,
  });
  targets.value = page.list;
  targetTotal.value = page.total;
}

async function loadDeliveries(): Promise<void> {
  const page = await getDeliveryPage({
    eventType: deliveryEventType.value,
    pageNo: deliveryPageNo.value,
    pageSize: deliveryPageSize.value,
    status: deliveryStatus.value,
    targetId: deliveryTargetId.value,
  });
  deliveries.value = page.list;
  deliveryTotal.value = page.total;
}

async function refresh(): Promise<void> {
  loading.value = true;
  error.value = '';
  try {
    await Promise.all([loadTargets(), loadDeliveries()]);
  } catch {
    error.value = `读取 Webhook 配置与投递失败：请确认当前账号有 ${AI_WEBHOOK_PERMISSIONS.query} 权限`;
  } finally {
    loading.value = false;
  }
}

function submitFormError(): string {
  return (
    validateTargetCode(form.value.code) ||
    validateTargetUrl(form.value.targetUrl) ||
    validateEventTypes(form.value.eventTypes) ||
    validateMaxAttempts(form.value.maxAttempts) ||
    validateSecret(form.value.secret) ||
    (form.value.name.trim().length === 0 ? '目标名称不能为空' : '')
  );
}

async function confirmCreate(): Promise<void> {
  const invalid = submitFormError();
  formError.value = invalid;
  if (invalid || !applicationId.value) {
    if (!applicationId.value) {
      formError.value = '请先选择应用';
    }
    return;
  }
  createRunning.value = true;
  notice.value = '';
  try {
    await createTarget({
      ...buildTargetPayload({
        ...form.value,
        applicationId: applicationId.value,
      }),
    });
    notice.value = '已登记目标：密钥只保存密文，接收端请同步配置同一份密钥';
    createOpen.value = false;
    form.value = {
      code: '',
      eventTypes: ['RUN.SUCCEEDED', 'RUN.FAILED'],
      maxAttempts: 3,
      name: '',
      secret: '',
      targetUrl: '',
    };
    await refresh();
  } catch {
    formError.value =
      '登记被拒绝：标识在同一应用内唯一，地址与事件白名单必须合规';
  } finally {
    createRunning.value = false;
  }
}

async function toggleStatus(row: AiWebhookApi.Target): Promise<void> {
  notice.value = '';
  const enable = row.status !== 'ENABLED';
  try {
    await updateTargetStatus({
      enabled: enable,
      id: row.id,
      version: row.version,
    });
    notice.value = enable
      ? `已启用 ${row.code}：新投递恢复`
      : `已停用 ${row.code}：不再产生投递，在途投递按事实收尾`;
    await refresh();
  } catch {
    error.value = '状态修改被拒绝：目标已被其他操作修改，请刷新后重试';
  }
}

function openRotate(row: AiWebhookApi.Target): void {
  rotateTarget.value = row;
  rotateSecret.value = '';
  rotateError.value = '';
  rotateOpen.value = true;
}

async function confirmRotate(): Promise<void> {
  const target = rotateTarget.value;
  if (!target) {
    return;
  }
  const invalid = validateSecret(rotateSecret.value);
  rotateError.value = invalid;
  if (invalid) {
    return;
  }
  try {
    await rotateTargetSecret({
      id: target.id,
      secret: rotateSecret.value.trim(),
      version: target.version,
    });
    notice.value = `已轮换 ${target.code} 的签名密钥：旧密钥立即作废`;
    rotateOpen.value = false;
    await refresh();
  } catch {
    rotateError.value = '轮换被拒绝：目标已被其他操作修改，请刷新后重试';
  }
}

async function removeTarget(row: AiWebhookApi.Target): Promise<void> {
  notice.value = '';
  try {
    await deleteTarget(row.id, row.version);
    notice.value = `已删除 ${row.code}：在途投递会在发送前复检失败并按事实收尾`;
    await refresh();
  } catch {
    error.value = '删除被拒绝：目标已被其他操作修改，请刷新后重试';
  }
}

async function openDelivery(row: AiWebhookApi.Delivery): Promise<void> {
  selectedDelivery.value = row;
  attempts.value = [];
  try {
    attempts.value = await getDeliveryAttempts(row.id);
  } catch {
    error.value = '读取尝试留痕失败：请确认当前账号有查看权限';
  }
}

async function confirmRedeliver(row: AiWebhookApi.Delivery): Promise<void> {
  notice.value = '';
  error.value = '';
  try {
    await redeliverDelivery(row.id);
    notice.value = `已重投 ${row.deliveryNo}：重置尝试预算并立即入队`;
    await refresh();
  } catch {
    error.value = '重投被拒绝：只有死信可以重投，且目标必须处于启用状态';
  }
}

function filterByTarget(row: AiWebhookApi.Target): void {
  deliveryTargetId.value = row.id;
  deliveryPageNo.value = 1;
  void loadDeliveries();
}

onMounted(async () => {
  try {
    await loadApplications();
    await refresh();
  } catch {
    error.value =
      '读取应用列表失败：请确认当前账号有 ai:application:query 权限';
  }
});
</script>

<template>
  <Page auto-content-height>
    <ElAlert
      v-if="error"
      class="mb-3"
      :closable="false"
      data-testid="ai-webhook-error"
      :title="error"
      type="error"
    />
    <ElAlert
      v-if="notice"
      class="mb-3"
      :closable="false"
      data-testid="ai-webhook-notice"
      :title="notice"
      type="success"
    />

    <ElCard class="mb-4">
      <template #header>
        <div class="flex flex-wrap items-center gap-3">
          <span class="font-medium">投递目标（停用即停发）</span>
          <ElSelect
            v-model="applicationId"
            clearable
            data-testid="ai-webhook-application"
            placeholder="全部应用"
            style="width: 240px"
            @change="refresh"
          >
            <ElOption
              v-for="item in applications"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </ElSelect>
          <ElSelect
            v-model="targetStatus"
            clearable
            data-testid="ai-webhook-target-status"
            placeholder="全部状态"
            style="width: 160px"
            @change="refresh"
          >
            <ElOption
              v-for="item in TARGET_STATUS_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
          <ElButton
            v-if="canManage"
            data-testid="ai-webhook-create"
            type="primary"
            @click="createOpen = true"
          >
            登记目标
          </ElButton>
        </div>
      </template>

      <ElTable :data="targets" data-testid="ai-webhook-targets" size="small">
        <ElTableColumn label="标识" min-width="140" prop="code" />
        <ElTableColumn label="名称" min-width="140" prop="name" />
        <ElTableColumn
          label="投递地址"
          min-width="240"
          prop="targetUrl"
          show-overflow-tooltip
        />
        <ElTableColumn label="事件" min-width="200">
          <template #default="{ row }">
            {{ (row.eventTypes as string[]).map(describeEventType).join('、') }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="110">
          <template #default="{ row }">
            <ElTag :type="row.status === 'ENABLED' ? 'success' : 'info'">
              {{ describeTargetStatus(row.status as string) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="密钥" min-width="180">
          <template #default="{ row }">
            {{ secretRotationHint(row.secretRevision as number) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="尝试上限" width="100" prop="maxAttempts" />
        <ElTableColumn label="操作" width="280">
          <template #default="{ row }">
            <ElButton
              link
              type="primary"
              @click="filterByTarget(row as AiWebhookApi.Target)"
            >
              看投递
            </ElButton>
            <ElButton
              v-if="canManage"
              link
              :data-testid="`ai-webhook-toggle-${row.id}`"
              type="primary"
              @click="toggleStatus(row as AiWebhookApi.Target)"
            >
              {{ row.status === 'ENABLED' ? '停用' : '启用' }}
            </ElButton>
            <ElButton
              v-if="canRotate"
              link
              type="primary"
              @click="openRotate(row as AiWebhookApi.Target)"
            >
              轮换密钥
            </ElButton>
            <ElButton
              v-if="canDelete"
              link
              type="danger"
              @click="removeTarget(row as AiWebhookApi.Target)"
            >
              删除
            </ElButton>
          </template>
        </ElTableColumn>
        <template #empty>
          <ElEmpty description="还没有登记投递目标" />
        </template>
      </ElTable>
      <ElPagination
        v-model:current-page="targetPageNo"
        class="mt-3 justify-end"
        :page-size="targetPageSize"
        :total="targetTotal"
        @current-change="loadTargets"
      />
    </ElCard>

    <ElCard>
      <template #header>
        <div class="flex flex-wrap items-center gap-3">
          <span class="font-medium">投递记录（FAILED 即死信，可人工重投）</span>
          <ElSelect
            v-model="deliveryStatus"
            clearable
            data-testid="ai-webhook-delivery-status"
            placeholder="全部状态"
            style="width: 200px"
            @change="loadDeliveries"
          >
            <ElOption
              v-for="item in DELIVERY_STATUS_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
          <ElSelect
            v-model="deliveryEventType"
            clearable
            placeholder="全部事件"
            style="width: 180px"
            @change="loadDeliveries"
          >
            <ElOption
              v-for="item in WEBHOOK_EVENT_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
          <ElTag type="info">共 {{ summary.total }}</ElTag>
          <ElTag type="success">已送达 {{ summary.delivered }}</ElTag>
          <ElTag type="warning">待投递 {{ summary.pending }}</ElTag>
          <ElTag type="danger">死信 {{ summary.deadLetter }}</ElTag>
        </div>
      </template>

      <ElTable
        :data="deliveries"
        data-testid="ai-webhook-deliveries"
        size="small"
      >
        <ElTableColumn label="投递编号" min-width="220" prop="deliveryNo" />
        <ElTableColumn label="事件" width="120">
          <template #default="{ row }">
            {{ describeEventType(row.eventType as string) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="运行" min-width="140" prop="resourceKey" />
        <ElTableColumn label="状态" width="120">
          <template #default="{ row }">
            {{ describeDeliveryStatus(row.status as string) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="尝试" width="90">
          <template #default="{ row }">
            {{ row.attemptCount }}/{{ row.maxAttempts }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="最近原因" min-width="200">
          <template #default="{ row }">
            {{ describeFailureCode(row.lastErrorCode as string) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="终态失败码" min-width="200">
          <template #default="{ row }">
            {{ describeFailureCode(row.failureCode as string) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="正文摘要" min-width="160">
          <template #default="{ row }">
            {{ shortenDigest(row.payloadDigest as string) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="180">
          <template #default="{ row }">
            <ElButton
              link
              type="primary"
              @click="openDelivery(row as AiWebhookApi.Delivery)"
            >
              详情
            </ElButton>
            <ElButton
              v-if="canRedeliverDelivery && canRedeliver(row.status as string)"
              link
              :data-testid="`ai-webhook-redeliver-${row.id}`"
              type="primary"
              @click="confirmRedeliver(row as AiWebhookApi.Delivery)"
            >
              人工重投
            </ElButton>
          </template>
        </ElTableColumn>
        <template #empty>
          <ElEmpty description="还没有投递记录" />
        </template>
      </ElTable>
      <ElPagination
        v-model:current-page="deliveryPageNo"
        class="mt-3 justify-end"
        :page-size="deliveryPageSize"
        :total="deliveryTotal"
        @current-change="loadDeliveries"
      />
    </ElCard>

    <ElDialog v-model="createOpen" title="登记投递目标" width="620px">
      <ElAlert
        v-if="formError"
        class="mb-3"
        :closable="false"
        :title="formError"
        type="error"
      />
      <div class="flex flex-col gap-3">
        <ElInput
          v-model="form.code"
          data-testid="ai-webhook-form-code"
          placeholder="目标标识（小写字母数字与连字符）"
        />
        <ElInput v-model="form.name" placeholder="目标名称" />
        <ElInput
          v-model="form.targetUrl"
          data-testid="ai-webhook-form-url"
          placeholder="投递地址（http/https）"
        />
        <ElSelect
          v-model="form.eventTypes"
          multiple
          placeholder="订阅事件（只支持运行终态）"
        >
          <ElOption
            v-for="item in WEBHOOK_EVENT_OPTIONS"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </ElSelect>
        <ElInput
          v-model="form.secret"
          data-testid="ai-webhook-form-secret"
          placeholder="签名密钥（16-128 位；只保存密文，不回显）"
          show-password
        />
        <div class="flex items-center gap-2">
          <span>单次投递尝试上限</span>
          <ElInputNumber v-model="form.maxAttempts" :max="10" :min="1" />
        </div>
      </div>
      <template #footer>
        <ElButton @click="createOpen = false">取消</ElButton>
        <ElButton
          :loading="createRunning"
          type="primary"
          @click="confirmCreate"
        >
          提交
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog v-model="rotateOpen" title="轮换签名密钥" width="560px">
      <ElAlert
        v-if="rotateError"
        class="mb-3"
        :closable="false"
        :title="rotateError"
        type="error"
      />
      <p class="mb-3 text-sm text-gray-500">
        轮换后旧密钥立即作废：接收端必须先换用新密钥，否则验签会失败（投递按确定失败收尾）。
      </p>
      <ElInput
        v-model="rotateSecret"
        data-testid="ai-webhook-rotate-secret"
        placeholder="新签名密钥（16-128 位）"
        show-password
      />
      <template #footer>
        <ElButton @click="rotateOpen = false">取消</ElButton>
        <ElButton type="primary" @click="confirmRotate">轮换</ElButton>
      </template>
    </ElDialog>

    <ElDialog
      :model-value="selectedDelivery !== undefined"
      title="投递详情"
      width="720px"
      @update:model-value="selectedDelivery = undefined"
    >
      <template v-if="selectedDelivery">
        <p class="mb-2 text-sm">
          投递编号 {{ selectedDelivery.deliveryNo }}｜状态
          {{ describeDeliveryStatus(selectedDelivery.status) }}｜尝试
          {{ selectedDelivery.attemptCount }}/{{ selectedDelivery.maxAttempts }}
        </p>
        <p class="mb-3 text-sm text-gray-500">
          正文摘要
          {{
            selectedDelivery.payloadDigest
          }}（正文不返回：只有事件类型、资源引用与运行状态）
        </p>
        <ElTable
          :data="attempts"
          data-testid="ai-webhook-attempts"
          size="small"
        >
          <ElTableColumn label="第几次" prop="attemptNo" width="90" />
          <ElTableColumn label="结论" width="110">
            <template #default="{ row }">
              {{ describeAttemptOutcome(row.outcome as string) }}
            </template>
          </ElTableColumn>
          <ElTableColumn label="HTTP" prop="httpStatus" width="90" />
          <ElTableColumn label="原因码" min-width="200">
            <template #default="{ row }">
              {{ describeFailureCode(row.errorCode as string) }}
            </template>
          </ElTableColumn>
          <ElTableColumn label="耗时(ms)" prop="durationMs" width="110" />
        </ElTable>
      </template>
    </ElDialog>
  </Page>
</template>
