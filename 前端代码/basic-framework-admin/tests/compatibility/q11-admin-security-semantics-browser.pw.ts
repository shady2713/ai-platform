import type { Page } from '@playwright/test';

/**
 * Q11 管理端页面浏览器结构验收 · 第一批（安全语义优先）。
 *
 * <p>为什么必须有浏览器这一层：这两页的"谁能看见什么"由**页面模板**（`v-if`、插槽名、
 * prop 名）、**页面 `data.ts`**（权限码与词表）和**真实 `TableAction`**（`auth` 可见性）
 * 三处共同决定。单测把这三处都 mock 掉了——`views/ai/semantic/index.test.ts:130` 给
 * `TableAction` 垫的假组件甚至声明了一个真实组件没有的 `drops` prop，于是
 * "维护入口在无权时消失"在单测里恒为真。真浏览器里挂的是**真实** `TableAction`，
 * prop 名对不上就会当场暴露。
 *
 * <p>每条用例都有对照：同一份夹具行、同一段生产代码，**只改权限码**，DOM 必须跟着变。
 * 没有对照的"看不见"是恒真断言，本卡不接受。
 *
 * <p>断言口径沿用 Q06/Q08：只做结构断言（元素出没、文案逐位、行数、列数、控件可用性），
 * 不做像素级视觉回归（ADR 0046 决策 2）。
 */
import { expect, test } from '@playwright/test';

/** 授权页权限码（与 `views/ai/authorization/data.ts:7` 的 V52 种子一致）。 */
const GRANT_CODES = {
  create: 'ai:grant:create',
  query: 'ai:grant:query',
  revoke: 'ai:grant:revoke',
  update: 'ai:grant:update',
} as const;

/** 语义页权限码（与 `views/ai/semantic/data.ts:9` 的 V93 种子一致）。 */
const SEMANTIC_CODES = {
  manage: 'ai:semantic:manage',
  query: 'ai:semantic:query',
} as const;

/** 两条授权：一活跃、一已撤销。形状对齐 `AiGrantApi.Grant`。 */
const GRANT_ROWS = [
  {
    actions: ['READ', 'EXPORT'],
    applicationId: 7,
    authzRevision: 4,
    externalUserId: 'alice',
    id: 1,
    resourceKey: 'report-1',
    resourceType: 'REPORT',
    status: 'ACTIVE',
    subjectType: 'USER',
    version: 3,
  },
  {
    actions: ['READ'],
    applicationId: 7,
    authzRevision: 1,
    externalUserId: 'bob',
    id: 2,
    resourceKey: 'report-2',
    resourceType: 'REPORT',
    status: 'REVOKED',
    subjectType: 'USER',
    version: 1,
  },
] as const;

/** 两条统一对象：形状对齐 `AiSemanticApi.MasterObject`。 */
const OBJECT_ROWS = [
  {
    currentRevision: 2,
    description: 'CRM/ERP 客户统一对象',
    id: 5,
    objectCode: 'md_cloud_qi',
    objectName: '云启科技（统一客户）',
    objectType: 'CUSTOMER',
    status: 'ACTIVE',
    version: 3,
  },
] as const;

interface MountOptions {
  codes: string[];
  rows: ReadonlyArray<Record<string, unknown>>;
}

/** 打开探针页，等 `__q11` 就绪（真实 `entry.ts` 现场构建的产物）。 */
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

/** 挂载页面并等首屏列表查询完成（不靠 sleep）。 */
async function mount(
  page: Page,
  name: string,
  options: MountOptions,
): Promise<void> {
  await page.evaluate(
    async (input) => {
      await (
        globalThis as unknown as {
          __q11: {
            mountPage: (
              pageName: string,
              opts: { codes: string[]; rows: Array<Record<string, unknown>> },
            ) => Promise<void>;
          };
        }
      ).__q11.mountPage(input.name, {
        codes: input.options.codes,
        rows: input.options.rows as Array<Record<string, unknown>>,
      });
    },
    { name, options: { codes: options.codes, rows: options.rows } },
  );
  // 挂载失败必须当场报出：bridge 返回的 Promise 不接住的话，错误会退化成后面的等待超时。
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

test.describe('Q11 资源授权页（Y05 授权矩阵）在真实 Chromium 中的渲染', () => {
  test('持全部授权码时新增/修改/撤销入口都在，列头与动作白名单逐位可读', async ({
    page,
  }) => {
    const pageErrors = await openProbe(page);
    await mount(page, 'authorization', {
      codes: Object.values(GRANT_CODES),
      rows: GRANT_ROWS,
    });

    // 列头逐位：8 个业务列 + 操作列，取自页面 data.ts 的 useGridColumns()
    await expect(page.locator('#admin-page thead th')).toHaveText([
      '应用编号',
      '主体类型',
      '外部用户',
      '资源类型',
      '资源标识',
      '动作白名单',
      '状态',
      '授权版本',
      '操作',
    ]);

    // 动作白名单列走生产 formatter：数组以「、」连接，空值渲染空串
    await expect(
      page.locator('#admin-page td[data-field="actions"]'),
    ).toHaveText(['READ、EXPORT', 'READ']);

    // 两条夹具行都在，行数本身就是可观测事实
    await expect(page.locator('#admin-page tbody tr')).toHaveCount(2);

    // 工具栏入口文案来自真实词表 $t('ui.actionTitle.create', ['授权']) → 新增授权
    await expect(
      page.locator('#admin-page [data-testid="probe-grid-toolbar"] button'),
    ).toHaveText(['新增授权']);

    // 行内两个写入口（真实 TableAction 渲染）
    const firstRow = page.locator('#admin-page tbody tr').first();
    await expect(firstRow.getByRole('button')).toHaveText(['修改动作', '撤销']);

    expect(pageErrors, '授权页渲染不得产生未捕获错误').toEqual([]);
  });

  test('（对照）只读权限码时三个写入口一个都不出——证明上一条不是恒真', async ({
    page,
  }) => {
    await openProbe(page);
    await mount(page, 'authorization', {
      codes: [GRANT_CODES.query],
      rows: GRANT_ROWS,
    });

    // 对照成立的条件：页面、夹具行、列头全都一样，只有权限码从 4 个变成 1 个。
    // 因此"入口消失"只能来自生产 actions.ts 的 isActionVisible 判定。
    await expect(
      page.locator('#admin-page [data-testid="probe-grid-toolbar"] button'),
    ).toHaveCount(0);
    await expect(page.getByRole('button', { name: '修改动作' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '撤销' })).toHaveCount(0);

    // 行与列仍在：不是整页没渲染
    await expect(page.locator('#admin-page tbody tr')).toHaveCount(2);
    await expect(page.locator('#admin-page thead th')).toHaveCount(9);

    // 真实词表兜底：只读也要能看见状态与授权版本（授权是否生效是用户必须知道的事实）
    await expect(
      page.locator('#admin-page td[data-field="status"]'),
    ).toHaveText(['ACTIVE', 'REVOKED']);
  });

  test('已撤销的行：修改与撤销都不可点，活跃行仍可点', async ({ page }) => {
    await openProbe(page);
    await mount(page, 'authorization', {
      codes: Object.values(GRANT_CODES),
      rows: GRANT_ROWS,
    });

    // 生产 `authorization/index.vue:87` 的 `disabled: row.status !== 'ACTIVE'`
    const activeRow = page.locator('#admin-page tbody tr').nth(0);
    const revokedRow = page.locator('#admin-page tbody tr').nth(1);

    await expect(
      activeRow.getByRole('button', { name: '修改动作' }),
    ).toBeEnabled();
    await expect(activeRow.getByRole('button', { name: '撤销' })).toBeEnabled();
    await expect(
      revokedRow.getByRole('button', { name: '修改动作' }),
    ).toBeDisabled();
    await expect(
      revokedRow.getByRole('button', { name: '撤销' }),
    ).toBeDisabled();
  });

  test('撤销提交携带乐观锁版本，确认框逐位说明作用范围', async ({ page }) => {
    await openProbe(page);
    await mount(page, 'authorization', {
      codes: Object.values(GRANT_CODES),
      rows: GRANT_ROWS,
    });

    await page
      .locator('#admin-page tbody tr')
      .first()
      .getByRole('button', { name: '撤销' })
      .click();

    // 二次确认文案是"撤销会立刻影响哪些事"的唯一用户可见承诺，必须逐位钉住
    await expect(page.getByText(/确认撤销？/)).toBeVisible();
    await expect(
      page.getByText(
        '撤销后 report-1 的新请求、后续工具步骤与产物读取都会被拒绝，确认撤销？',
      ),
    ).toBeVisible();

    await page.getByRole('button', { name: '确定' }).click();
    await expect
      .poll(async () => {
        const state = await snapshot(page);
        return state.apiCalls.find((call) => call.name === 'revokeGrant')?.args;
      })
      // 生产 handleRevoke 传的是 (row.id, row.version)：缺版本就撤销不了别人改过的授权
      .toEqual({ id: 1, version: 3 });
  });
});

test.describe('Q11 主数据映射页（Y02/Y07）在真实 Chromium 中的维护入口显隐', () => {
  test('持 ai:semantic:manage 时工具栏出现新建入口，页面常驻语义提示逐位可读', async ({
    page,
  }) => {
    const pageErrors = await openProbe(page);
    await mount(page, 'semantic', {
      codes: [SEMANTIC_CODES.manage, SEMANTIC_CODES.query],
      rows: OBJECT_ROWS,
    });

    await expect(
      page.getByRole('button', { name: '新建统一对象' }),
    ).toBeVisible();

    // 两条常驻提示逐位：Y02 的"不按同名合并"与"冲突/过期阻断"不得被前端悄悄改写
    const page_ = page.locator('#admin-page');
    await expect(page_).toContainText('同名不会合并');
    await expect(page_).toContainText('平台不会替你挑一个');

    // 列头取自页面 data.ts 的 useGridColumns()，逐位比对
    await expect(page.locator('#admin-page thead th')).toHaveText([
      '对象标识',
      '对象名称',
      '对象类型',
      '当前映射版本',
      '状态',
      '乐观锁版本',
      '操作',
    ]);

    // 三列都走生产 formatter：词表映射（客户）、版本前缀（v2）、状态文案（启用）
    await expect(
      page.locator('#admin-page td[data-field="objectType"]'),
    ).toHaveText(['客户']);
    await expect(
      page.locator('#admin-page td[data-field="currentRevision"]'),
    ).toHaveText(['v2']);
    await expect(
      page.locator('#admin-page td[data-field="status"]'),
    ).toHaveText(['启用']);

    expect(pageErrors, '语义页渲染不得产生未捕获错误').toEqual([]);
  });

  test('（对照）无 ai:semantic:manage 时新建入口消失，只读入口仍在', async ({
    page,
  }) => {
    await openProbe(page);
    await mount(page, 'semantic', {
      codes: [SEMANTIC_CODES.query],
      rows: OBJECT_ROWS,
    });

    // 对照：同页面同夹具，只改权限码
    await expect(
      page.getByRole('button', { name: '新建统一对象' }),
    ).toHaveCount(0);

    // 只读入口必须仍在：判定与目录是无权用户也要用的
    await expect(
      page.getByRole('button', { name: '版本与映射' }),
    ).toBeVisible();
    await expect(
      page.getByRole('button', { name: '判定与目录' }),
    ).toBeVisible();
  });

  test('维护权的编辑/停用必须出现在行内下拉里', async ({ page }) => {
    // 本用例当初是**红灯**，抓到了真实生产缺陷：`views/ai/semantic/index.vue:215`
    // 传的是 `:drops`，而真实组件只声明 `dropDownActions`
    // （`components/table-action/table-action.vue:38`）。`drops` 落到 fallthrough attr 上，
    // `getDropdownList` 恒空 → 下拉根本不渲染 → 「编辑 / 停用」两个维护入口永不出现。
    // 仓库其余 20+ 页面一律用 `dropDownActions`。单测抓不到：假组件照抄了 `drops`，断言恒真。
    // 生产已改（`:drops` → `:dropDownActions`），本用例转绿。
    //
    // 装置保真度：`更多` 是 `$t('page.action.more')` 的**真实词表**取值
    // （`apps/web-ele/src/locales/langs/zh-CN/page.json`）。断言写死「更多」而不是裸 key，
    // 等于顺带钉住探针的 i18n 装配：glob 写错时该按钮会渲染成 `page.action.more`，
    // 这条用例会立刻红，不会把装置缺陷伪装成产品缺陷。
    await openProbe(page);
    await mount(page, 'semantic', {
      codes: [SEMANTIC_CODES.manage, SEMANTIC_CODES.query],
      rows: OBJECT_ROWS,
    });

    // 夹具行是 ACTIVE，所以生产 `semantic/index.vue:223` 给出的标签是「停用」
    const row = page.locator('#admin-page tbody tr').first();
    const trigger = row.getByRole('button', { name: '更多', exact: true });
    // 可访问名必须逐字是「更多」：裸 key（`page.action.more`）不算通过
    await expect(trigger).toHaveText('更多');
    await trigger.click();
    await expect(page.getByText('编辑', { exact: true })).toBeVisible();
    await expect(page.getByText('停用', { exact: true })).toBeVisible();
  });
});
