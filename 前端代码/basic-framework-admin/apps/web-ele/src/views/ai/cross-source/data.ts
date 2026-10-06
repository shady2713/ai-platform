import type { AiCrossSourceApi } from '#/api/ai/cross-source';

/**
 * 跨源合并结果页面（V97 菜单 4133/4134）的展示口径。
 *
 * 本文件承担本卡最要紧的一件事：**把"能不能渲染数字"做成可测的纯函数**，
 * 而不是埋进模板里的 `v-if`。fail-closed 承诺一旦只剩模板条件，
 * 就没法用测试证明，而没法证明的承诺等于没有承诺。
 */

/**
 * 页面权限码，与 V97 迁移的 `system_menu` 种子、以及
 * `AiCrossSourceMergeController` 的两个 `@PreAuthorize` 逐字对应。
 * 两个端点各受一个权限码保护，界面也按同一组码提示，不另造。
 */
export const AI_CROSS_SOURCE_PERMISSIONS = {
  integrity: 'ai:cross-source:integrity',
  query: 'ai:cross-source:query',
} as const;

/**
 * 跨源角色闭集（后端 `CrossSourceCallerRole` 的三个枚举值，逐字一致）。
 *
 * 界面只给这三个可选项不是保守，而是必需：`AiCrossSourceMergeController.toQuery`
 * 对认不出的角色名一律 `parse` 失败后丢弃，**不静默降级到最低角色**——
 * 界面让用户自由输入等于把一次拼写错误变成一句莫名其妙的"未出具"。
 */
export const CROSS_SOURCE_CALLER_ROLE_OPTIONS = [
  { label: 'ANALYST（分析员：聚合值 + 业务维度）', value: 'ANALYST' },
  { label: 'DATA_STEWARD（数据管理员：全字段）', value: 'DATA_STEWARD' },
  { label: 'AGGREGATE_READER（只读聚合值）', value: 'AGGREGATE_READER' },
];

/** 主体类型闭集（后端 `@RequestParam("subjectType")` 取 APP / USER） */
export const CROSS_SOURCE_SUBJECT_TYPE_OPTIONS = [
  { label: 'USER（外部用户）', value: 'USER' },
  { label: 'APP（应用主体）', value: 'APP' },
];

/**
 * 必填查询字段：后端 5 个 `required = true` 的 `@RequestParam`。
 * 少任何一个都是 400「请求参数缺失」，因此页面在提交前逐项校验。
 */
export const CROSS_SOURCE_REQUIRED_FIELDS = [
  'executionKey',
  'applicationId',
  'subjectType',
  'externalUserId',
  'callerRoles',
] as const;

/** 页面查询条件（未填状态允许空值：表单一开始就是空的） */
export interface CrossSourceQueryForm {
  applicationId?: number;
  callerRoles: string[];
  executionKey: string;
  externalUserId: string;
  previouslySeenRoles: string[];
  subjectType: string;
}

/** 授权完整性状态的中文说明（只说结论，不解释被禁来源） */
const INTEGRITY_STATE_TEXT: Record<string, string> = {
  COMPLETE: '完整（全部来源在授权范围内）',
  PARTIAL: '部分（部分来源不可出具，仅出合计）',
  WITHHELD: '未出具（整份结果不可看）',
};

/** 口径缺失时的规定状态字面量：绝不用 COMPLETE 顶替 */
export const MISSING_INTEGRITY_CODE = 'MISSING';

/**
 * 这次响应是否允许渲染**任何**数字。
 *
 * 只有服务端明确宣告 `COMPLETE` / `PARTIAL` 才为真；口径缺失、为 null、
 * 或是任何未知字面量，一律为假。这是本文件的承重函数：
 *
 * - `WITHHELD` 为假 —— 服务端根本没序列化数字，界面也不该凭空造一个；
 * - **缺失为假** —— 由前端替后端宣布"你有权看"与 fail-closed 正面冲突。
 *   把它默认成放行，等于把一次代码缺陷变成一次真实的越权披露。
 *
 * 与 `packages/ai-chat-ui` 的 `rendersNothing` 刻意不同：那边判"是否什么都不渲染"，
 * 这边判"是否显式放行"。页面必须对**未知状态**也 fail-closed，
 * 而"未渲染"这个说法在状态未知时区分不出"服务端没发"和"我们没读"。
 */
export function shouldRenderAmounts(
  integrity?: AiCrossSourceApi.Integrity | null,
): boolean {
  return integrity?.state === 'COMPLETE' || integrity?.state === 'PARTIAL';
}

/**
 * 是否可以去取数字（「先问口径再取数」的闸门）。
 *
 * 只认 `COMPLETE`。这正是后端单独开一个只读口径端点的原因：
 * 界面在经手任何金额之前先问一句"这份结果能不能给你看"。
 */
export function canFetchAmounts(
  integrity?: AiCrossSourceApi.Integrity | null,
): boolean {
  return integrity?.state === 'COMPLETE';
}

/** 口径字面量原样展示：排障时需要看到后端真的发了什么，而不是界面翻译后的说法 */
export function integrityStateCode(
  integrity?: AiCrossSourceApi.Integrity | null,
): string {
  return integrity?.state ?? MISSING_INTEGRITY_CODE;
}

/** 口径中文说明；缺失与未知字面量都归到"未出具"，不回显成看起来完整 */
export function integrityStateText(
  integrity?: AiCrossSourceApi.Integrity | null,
): string {
  const code = integrity?.state;
  if (!code) {
    return '未出具（服务端未返回授权完整性口径）';
  }
  return INTEGRITY_STATE_TEXT[code] ?? `${code}（未知口径，按未出具处理）`;
}

/** 口径理由：原样透传，不回显被禁来源的角色名与规模（提示本身也是一条信道） */
export function integrityReasonText(
  integrity?: AiCrossSourceApi.Integrity | null,
): string {
  if (!integrity) {
    return '服务端未返回授权完整性口径，按未出具处理。';
  }
  // COMPLETE 变体在类型上就没有 reason 可填：放行没有理由可编。
  if (integrity.state === 'COMPLETE') {
    return '';
  }
  return integrity.reason;
}

/** 口径标签配色：只有 COMPLETE 是绿的，未知与缺失一律红 */
export function integrityTone(
  integrity?: AiCrossSourceApi.Integrity | null,
): 'danger' | 'success' | 'warning' {
  if (integrity?.state === 'COMPLETE') {
    return 'success';
  }
  if (integrity?.state === 'PARTIAL') {
    return 'warning';
  }
  return 'danger';
}

/**
 * 不可出具时页面给出的唯一说明。
 *
 * 刻意**整段不含任何数字**——不是"顺便没有"，而是这条文案本身就不该出现
 * 一个可以被拿来当规模推测的量（"还差 2 个来源"就是一条信道）。
 */
export function withheldNotice(
  integrity?: AiCrossSourceApi.Integrity | null,
): string {
  return [
    `${integrityStateText(integrity)}：${integrityReasonText(integrity)}`,
    '服务端未序列化任何金额与来源条数，界面也不渲染任何数字；这不是临时故障，重试不会改变结果，请联系管理员开通授权。',
  ].join('。');
}

/**
 * 金额展示：null 一律显示「未出具」，**绝不用 0 顶替**。
 *
 * 把"不知道"显示成 0 会凭空造出"服务端算出来了只是不给看"的错觉，
 * 而这两件事的处置动作完全不同：前者是授权问题，重试无用。
 *
 * 刻意不做千分位与四舍五入：管理端要能一眼对回接口原值，
 * 展示层替数字做舍入，会把"后端多给了一分"这类问题藏起来。
 */
export function formatAmount(value?: null | number | string): string {
  if (value === null || value === undefined || value === '') {
    return '未出具';
  }
  return String(value);
}

/** 时间偏移展示：如实给数字，让用户自己判断各来源还具不具可比性 */
export function formatSkew(value?: null | number): string {
  if (value === null || value === undefined) {
    return '未出具';
  }
  return `${value} 毫秒`;
}

/** 口径时间点展示（后端 `LocalDateTime` 不带时区后缀，只把分隔符换成空格） */
export function formatConsistencyAsOf(value?: null | string): string {
  if (!value) {
    return '未出具';
  }
  return value.replace('T', ' ');
}

/**
 * 分来源明细行。
 *
 * 口径不放行时返回空数组，而不是"响应里有什么就显示什么"：
 * 万一某次响应在 `WITHHELD` 下仍带回了明细，页面也不跟着渲染。
 * 这条冗余是故意的——fail-closed 不该依赖"服务端永远不发"。
 */
export function sourceRows(
  result?: AiCrossSourceApi.MergeResult | null,
): AiCrossSourceApi.SourceAmount[] {
  if (!result || !shouldRenderAmounts(result.integrity)) {
    return [];
  }
  return result.sources ?? [];
}

/**
 * 提交前必填校验，返回空串表示通过。
 *
 * 刻意不在这里"就近补一个默认角色"：`callerRoles` 为空时若照发，
 * 服务端会把全部角色名丢弃并 fail-closed 拒绝，回来的 400 只会说"参数缺失"，
 * 比在这里说清"角色不能为空"难排查得多。少一次往返，也少一个看起来像故障的 400。
 */
export function validateQuery(form: CrossSourceQueryForm): string {
  if (!form.executionKey.trim()) {
    return '请输入跨源执行幂等键';
  }
  if (!form.applicationId || form.applicationId < 1) {
    return '请输入应用编号（正整数）';
  }
  if (!form.subjectType) {
    return '请选择主体类型';
  }
  if (!form.externalUserId.trim()) {
    return '请输入可信外部用户标识';
  }
  if (form.callerRoles.length === 0) {
    return '请至少选择一个调用方角色（角色为空会被服务端按未授权拒绝）';
  }
  return '';
}

/**
 * 把表单收窄成可直接发请求的查询条件，校验不通过时返回 `undefined`。
 *
 * <p>存在的理由是**类型**，不是"多此一举的封装"：表单里 `applicationId` 一开始就是
 * `undefined`（页面是空的），而 {@link AiCrossSourceApi.MergeQuery} 要求它是 `number`。
 * 页面先调 `validateQuery` 再发请求，逻辑上是对的，但 TypeScript 看不到这条控制流，
 * 于是只能靠 `as` 强转把缺口糊过去——那等于把"这里必须已经校验过"这个不变量
 * 从代码里删掉。
 *
 * <p>改成收窄函数后：不变量写在类型里（编不过就是真缺校验），
 * 而且它本身可被直接测到，不依赖页面是否记得先校验。
 */
export function toMergeQuery(
  form: CrossSourceQueryForm,
): AiCrossSourceApi.MergeQuery | undefined {
  if (validateQuery(form) !== '') {
    return undefined;
  }
  // 走到这里 `validateQuery` 已经保证 applicationId 是 >= 1 的正整数，
  // 但它返回的是字符串、不带类型收窄信息，所以这里必须自己判一次。
  const applicationId = form.applicationId;
  if (applicationId === undefined) {
    return undefined;
  }
  return {
    applicationId,
    callerRoles: [...form.callerRoles],
    executionKey: form.executionKey.trim(),
    externalUserId: form.externalUserId.trim(),
    previouslySeenRoles:
      form.previouslySeenRoles.length > 0
        ? [...form.previouslySeenRoles]
        : undefined,
    subjectType: form.subjectType,
  };
}
