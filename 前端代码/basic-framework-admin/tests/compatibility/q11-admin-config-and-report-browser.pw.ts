import type { Page } from '@playwright/test';

/**
 * Q11 管理端页面浏览器结构验收 · 第二批（配置面与报表页）。
 *
 * <p>与第一批同一个探针、同一套桥接、同一批真实组件（页面 `.vue` + 页面 `data.ts` +
 * 真实 `TableAction`）。这批盯三件事：
 *  1. **命令按权限码分流**：四个配置页给每条动作单独配了 `auth`，因此"只读主体只剩查询入口"
 *     是可以在真浏览器里做对照的；
 *  2. **秘密不可见**：连接器/模型端点的列表列只渲染 `credentialConfigured`（已配置/未配置），
 *     界面拿不到凭据明文；
 *  3. **失败与失权如实呈现**：报表页换票失败、刷新失权都必须显示稳定文案，不许假装成功。
 *
 * <p>每条"看不见"都配对照：同一页面、同一夹具行，只改权限码或只改失败注入，DOM 必须跟着变。
 */
import { expect, test } from '@playwright/test';

/** 三个配置页的权限码（与各自 `data.ts` 的种子登记一致）。 */
const DATASET_CODES = {
  create: 'ai:dataset:create',
  delete: 'ai:dataset:delete',
  query: 'ai:dataset:query',
  update: 'ai:dataset:update',
} as const;

const ENDPOINT_CODES = {
  create: 'ai:model-endpoint:create',
  delete: 'ai:model-endpoint:delete',
  probe: 'ai:model-endpoint:probe',
  query: 'ai:model-endpoint:query',
  update: 'ai:model-endpoint:update',
} as const;

const CONNECTOR_CODES = {
  create: 'ai:connector:create',
  delete: 'ai:connector:delete',
  import: 'ai:connector:import',
  operation: 'ai:connector:operation',
  probe: 'ai:connector:probe',
  query: 'ai:connector:query',
  update: 'ai:connector:update',
} as const;

const DATASET_ROWS = [
  {
    code: 'crm_orders',
    connectorId: 3,
    id: 1,
    latestVersionNo: 2,
    name: 'CRM 订单',
    publishedVersionNo: 2,
    sourceObject: 'crm.orders',
    status: 'ENABLED',
    version: 4,
  },
  {
    code: 'erp_invoice',
    connectorId: 3,
    id: 2,
    latestVersionNo: 0,
    name: 'ERP 发票',
    publishedVersionNo: 0,
    sourceObject: 'erp.invoice',
    status: 'DISABLED',
    version: 1,
  },
] as const;

const ENDPOINT_ROWS = [
  {
    baseUrl: 'https://llm.example.com/v1',
    capabilities: ['CHAT', 'TOOLS'],
    configRevision: 5,
    credentialConfigured: true,
    enabled: true,
    id: 1,
    modelId: 'qwen-max',
    name: '主力问答端点',
    provider: 'ALIYUN',
    version: 6,
  },
  {
    baseUrl: 'https://llm-backup.example.com/v1',
    capabilities: ['CHAT'],
    configRevision: 1,
    credentialConfigured: false,
    enabled: false,
    id: 2,
    modelId: 'qwen-plus',
    name: '备用端点',
    provider: 'ALIYUN',
    version: 1,
  },
] as const;

const CONNECTOR_ROWS = [
  {
    code: 'crm',
    configJson: '{"baseUrl":"https://crm.example.com"}',
    connectorType: 'HTTP',
    createTime: '2026-09-01T10:00:00',
    credentialConfigured: true,
    id: 1,
    name: 'CRM 只读连接器',
    status: 'ENABLED',
    version: 2,
  },
] as const;

interface MountOptions {
  codes: string[];
  reportFailures?: { refresh?: string; ticket?: string };
  rows: ReadonlyArray<Record<string, unknown>>;
}

async function openProbe(page: Page): Promise<string[]> {
  const pageErrors: string[] = [];
  page.on('pageerror', (error) => pageErrors.push(String(error)));
  await page.goto('http://127.0.0.1:5399/');
  await page.waitForFunction(() => {
    const bridge = (
      globalThis as unknown as { __q11?: { mountPage?: unknown } }
    ).__q11;
    return typeof bridge?.mountPage === 'function';
  });
  return pageErrors;
}

/** 挂载页面；只有走网格的页面才有首屏列表查询，报表页靠 `waitForMounted` 判就绪。 */
async function mount(
  page: Page,
  name: string,
  options: MountOptions,
  { waitForGrid = true }: { waitForGrid?: boolean } = {},
): Promise<void> {
  await page.evaluate(
    async (input) => {
      await (
        globalThis as unknown as {
          __q11: {
            mountPage: (
              pageName: string,
              opts: {
                codes: string[];
                reportFailures?: { refresh?: string; ticket?: string };
                rows: Array<Record<string, unknown>>;
              },
            ) => Promise<void>;
          };
        }
      ).__q11.mountPage(input.name, {
        codes: input.options.codes,
        reportFailures: input.options.reportFailures,
        rows: input.options.rows as Array<Record<string, unknown>>,
      });
    },
    {
      name,
      options: {
        codes: options.codes,
        reportFailures: options.reportFailures,
        rows: options.rows,
      },
    },
  );
  if (waitForGrid) {
    await page.waitForFunction(
      () => {
        const bridge = (
          globalThis as unknown as {
            __q11?: { snapshot?: () => { gridLoads: number } };
          }
        ).__q11;
        return (bridge?.snapshot?.().gridLoads ?? 0) > 0;
      },
      undefined,
      { timeout: 15_000 },
    );
  }
  // 页面根节点出现即视为挂载完成（报表页不经过网格桥接）
  await page
    .locator(
      '#admin-page [data-testid="report-issue-ticket"], #admin-page [data-testid="probe-page"]',
    )
    .first()
    .waitFor();
}

function snapshot(page: Page) {
  return page.evaluate(() => {
    const bridge = (
      globalThis as unknown as {
        __q11: {
          snapshot: () => {
            apiCalls: Array<{ args: unknown; name: string }>;
            errors: string[];
            feedback: Array<{ kind: string; message: string }>;
          };
        };
      }
    ).__q11;
    return bridge.snapshot();
  });
}

test.describe('Q11 数据集页（D10）命令按权限码分流', () => {
  test('持全部权限码时四个命令都在，列头与状态文案逐位可读', async ({
    page,
  }) => {
    const pageErrors = await openProbe(page);
    await mount(page, 'dataset', {
      codes: Object.values(DATASET_CODES),
      rows: DATASET_ROWS,
    });

    await expect(page.locator('#admin-page thead th')).toHaveText([
      '标识',
      '名称',
      '来源对象',
      '连接器',
      '状态',
      '最新版本',
      '已发布版本',
      '版本',
      '操作',
    ]);

    // 状态列走生产 formatter：ENABLED→启用，其余→停用
    await expect(
      page.locator('#admin-page td[data-field="status"]'),
    ).toHaveText(['启用', '停用']);

    await expect(
      page.locator('#admin-page [data-testid="probe-grid-toolbar"] button'),
    ).toHaveText(['新增数据集']);

    // 行内四个命令。注意标签取自真实词表：编辑动作用的是 `$t('common.edit')` = "修改"
    // （不是"编辑"）；按钮带图标时文本有前导空格，故用可访问名而非文本逐位比对。
    const row = page.locator('#admin-page tbody tr').first();
    for (const label of ['语义版本', '停用', '修改', '删除']) {
      await expect(row.getByRole('button', { name: label })).toHaveCount(1);
    }

    expect(pageErrors, '数据集页渲染不得产生未捕获错误').toEqual([]);
  });

  test('（对照）只读权限码时只剩语义版本，四个写命令一个都不出', async ({
    page,
  }) => {
    await openProbe(page);
    await mount(page, 'dataset', {
      codes: [DATASET_CODES.query],
      rows: DATASET_ROWS,
    });

    // 对照：同页面同夹具，只把权限码从 7 个减到 1 个
    await expect(
      page.locator('#admin-page [data-testid="probe-grid-toolbar"] button'),
    ).toHaveCount(0);
    await expect(page.getByRole('button', { name: '修改' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '删除' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '停用' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '启用' })).toHaveCount(0);

    // 查询入口仍在，行也仍在：不是整页没渲染
    await expect(page.getByRole('button', { name: '语义版本' })).toHaveCount(2);
    await expect(page.locator('#admin-page tbody tr')).toHaveCount(2);
  });
});

test.describe('Q11 模型端点页：凭据只暴露"是否已配置"', () => {
  test('凭据列只渲染已配置/未配置，界面拿不到任何凭据明文', async ({
    page,
  }) => {
    const pageErrors = await openProbe(page);
    await mount(page, 'model-endpoint', {
      codes: Object.values(ENDPOINT_CODES),
      rows: ENDPOINT_ROWS,
    });

    // 生产 formatter：`credentialConfigured ? '已配置' : '未配置'`
    await expect(
      page.locator('#admin-page td[data-field="credentialConfigured"]'),
    ).toHaveText(['已配置', '未配置']);
    await expect(
      page.locator('#admin-page td[data-field="enabled"]'),
    ).toHaveText(['启用', '停用']);
    await expect(
      page.locator('#admin-page td[data-field="configRevision"]'),
    ).toHaveText(['v5', 'v1']);
    // 能力列走数组 join
    await expect(
      page.locator('#admin-page td[data-field="capabilities"]'),
    ).toHaveText(['CHAT、TOOLS', 'CHAT']);

    // 夹具里根本没有凭据字段，界面也不该凭空出现"apiKey"/"密钥值"之类的字样
    const admin = page.locator('#admin-page');
    await expect(admin).not.toContainText('apiKey');
    await expect(admin).not.toContainText('sk-');

    expect(pageErrors, '模型端点页渲染不得产生未捕获错误').toEqual([]);
  });

  test('（对照）只读权限码时凭据/状态列仍可见，但探测/轮换/编辑/删除入口消失', async ({
    page,
  }) => {
    await openProbe(page);
    await mount(page, 'model-endpoint', {
      codes: [ENDPOINT_CODES.query],
      rows: ENDPOINT_ROWS,
    });

    // 对照组：可见性列不随权限码变化（读权限就该看得到状态），
    // 变化的是命令入口——这证明"入口消失"来自 auth 判定而不是"整页没渲染"
    await expect(
      page.locator('#admin-page td[data-field="credentialConfigured"]'),
    ).toHaveText(['已配置', '未配置']);

    for (const label of ['探测', '修改', '删除']) {
      await expect(page.getByRole('button', { name: label })).toHaveCount(0);
    }
    await expect(
      page.locator('#admin-page [data-testid="probe-grid-toolbar"] button'),
    ).toHaveCount(0);
  });
});

test.describe('Q11 连接器页：秘密列与接口管理入口', () => {
  test('持全部权限码时秘密列只显示已配置，接口管理入口可见', async ({
    page,
  }) => {
    const pageErrors = await openProbe(page);
    await mount(page, 'connector', {
      codes: Object.values(CONNECTOR_CODES),
      rows: CONNECTOR_ROWS,
    });

    await expect(
      page.locator('#admin-page td[data-field="credentialConfigured"]'),
    ).toHaveText(['已配置']);
    await expect(
      page.locator('#admin-page td[data-field="status"]'),
    ).toHaveText(['启用']);

    const row = page.locator('#admin-page tbody tr').first();
    for (const label of ['连接测试', '接口管理', '停用', '修改', '删除']) {
      await expect(row.getByRole('button', { name: label })).toHaveCount(1);
    }

    expect(pageErrors, '连接器页渲染不得产生未捕获错误').toEqual([]);
  });

  test('（对照）无 probe 权限时连接测试消失，接口管理（import 权限）仍在', async ({
    page,
  }) => {
    await openProbe(page);
    await mount(page, 'connector', {
      codes: [CONNECTOR_CODES.query, CONNECTOR_CODES.import],
      rows: CONNECTOR_ROWS,
    });

    // 精确对照：只去掉 probe 一个码，同一行上"连接测试"消失而"接口管理"留下。
    // 一条 fixture 同时钉住两个方向，避免"按钮都没渲染"这种假对照。
    const row = page.locator('#admin-page tbody tr').first();
    await expect(row.getByRole('button', { name: '连接测试' })).toHaveCount(0);
    await expect(row.getByRole('button', { name: '接口管理' })).toBeVisible();
  });
});

test.describe('Q11 个人报表页（R07）：票据、失权与"未实现的能力不放按钮"', () => {
  test('换票失败时页面显示稳定失败文案，且不出现任何报表数据', async ({
    page,
  }) => {
    await openProbe(page);
    await mount(
      page,
      'report',
      {
        codes: [],
        reportFailures: { ticket: '客户端凭据无效（1_004_002_001）' },
        rows: [],
      },
      { waitForGrid: false },
    );

    await page.locator('[data-testid="report-app-code"]').fill('demo-app');
    await page
      .locator('[data-testid="report-app-secret"]')
      .fill('s3cr3t-value');
    await page.getByRole('button', { name: '换取票据并加载' }).click();

    // 失败文案逐位：这是"换票失败"唯一的用户可见承诺
    const failure = page.locator('[data-testid="report-ticket-failure"]');
    await expect(failure).toBeVisible();
    await expect(failure).toHaveText('客户端凭据无效（1_004_002_001）');

    // 失败时不得出现报表行：没换到票就不该有任何数据
    await expect(
      page.locator('[data-testid="report-table"] tbody tr'),
    ).toHaveCount(0);

    // 密钥只提交、不回显：探针记录的入参里只有"是否提交"。
    // `externalUserId` 为 `undefined` 而非空串：生产 `index.vue:114` 写的是
    // `externalUserId.value || undefined`——留空就不把这个字段发出去。
    const state = await snapshot(page);
    const issued = state.apiCalls.find(
      (call) => call.name === 'issueDebugTicket',
    );
    expect(issued?.args).toEqual({
      appCode: 'demo-app',
      appSecretProvided: true,
      externalUserId: undefined,
      subjectType: 'USER',
    });
  });

  test('换票成功后按权限展示命令：可刷新报表有刷新，快照报表没有', async ({
    page,
  }) => {
    const pageErrors = await openProbe(page);
    await mount(
      page,
      'report',
      { codes: [], rows: [] },
      { waitForGrid: false },
    );

    await page.locator('[data-testid="report-app-code"]').fill('demo-app');
    await page
      .locator('[data-testid="report-app-secret"]')
      .fill('s3cr3t-value');
    await page.getByRole('button', { name: '换取票据并加载' }).click();

    // 两条报表都在，模式文案逐位（REPORT_MODE_TEXT）
    const rows = page.locator('[data-testid="report-table"] tbody tr');
    await expect(rows).toHaveCount(2);
    await expect(rows.first()).toContainText('华东客户净销售额');
    await expect(rows.first()).toContainText('可刷新');
    await expect(rows.nth(1)).toContainText('月末应收快照');
    await expect(rows.nth(1)).toContainText('快照');

    // 打开可刷新报表 → 有刷新按钮
    await rows.first().getByRole('button', { name: '预览' }).click();
    await expect(page.locator('[data-testid="report-refresh"]')).toBeVisible();

    // 打开快照报表 → 没有刷新按钮（canRefresh 只对 REFRESHABLE 为真）
    await rows.nth(1).getByRole('button', { name: '预览' }).click();
    await expect(page.locator('[data-testid="report-refresh"]')).toHaveCount(0);
    await expect(
      page.locator('[data-testid="report-revise-open"]'),
    ).toBeVisible();

    // 首期私人报表：分享/发布未实现，页面上不得出现这两个按钮
    await expect(page.getByRole('button', { name: '分享' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '发布' })).toHaveCount(0);

    // 报表归属由服务端会话决定：页面不提供归属/行范围输入
    await expect(page.getByText('归属')).toHaveCount(0);
    await expect(page.getByText('行范围')).toHaveCount(0);

    expect(pageErrors, '报表页渲染不得产生未捕获错误').toEqual([]);
  });

  test('刷新失权时如实显示失败原因，不假装刷新成功', async ({ page }) => {
    await openProbe(page);
    await mount(
      page,
      'report',
      {
        codes: [],
        reportFailures: {
          refresh: '授权已撤销，新请求与产物读取都会被拒绝（1_003_006_032）',
        },
        rows: [],
      },
      { waitForGrid: false },
    );

    await page.locator('[data-testid="report-app-code"]').fill('demo-app');
    await page
      .locator('[data-testid="report-app-secret"]')
      .fill('s3cr3t-value');
    await page.getByRole('button', { name: '换取票据并加载' }).click();

    const rows = page.locator('[data-testid="report-table"] tbody tr');
    await rows.first().getByRole('button', { name: '预览' }).click();
    await page.locator('[data-testid="report-refresh"]').click();

    // 生产 `index.vue:175`：catch 分支把后端原因原样留在页面上
    const message = page.locator('[data-testid="report-refresh-message"]');
    await expect(message).toBeVisible();
    await expect(message).toHaveText(
      '授权已撤销，新请求与产物读取都会被拒绝（1_003_006_032）',
    );
    // 不得同时出现成功文案
    await expect(message).not.toContainText('刷新成功');
  });
});
