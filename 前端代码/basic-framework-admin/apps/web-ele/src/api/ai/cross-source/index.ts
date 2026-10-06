import { requestClient } from '#/api/request';

/**
 * 跨源合并结果对外契约（V97 菜单 4133/4134 对应的后端
 * `AiCrossSourceMergeController` 及其三个 VO）。
 *
 * 字段一律照抄 VO，**不猜**：后端 `@JsonInclude(ALWAYS)` 只保证键恒在，
 * 不保证值非空——`WITHHELD` 时合计、来源计数、分来源明细、口径时间点与时间偏移
 * 全部是 null/空。因此本模块里所有数字字段都是可选且可为 null 的。
 *
 * 模块里**没有**分页/列表接口：后端只提供两个按执行键查询的端点。
 * 前端不会为了"页面看起来完整"去拼一个并不存在的列表。
 */

/**
 * 授权完整性口径的**排他联合**，与 `packages/ai-chat-ui` 的 `CrossSourceIntegrity`
 * 以及后端 `CrossSourceIntegrity` 三个字面量逐字一致，不得另造状态名。
 *
 * 为什么用排他联合而不是 `state?: string`：两侧各写一套名字会让前端在解析期
 * 把合法响应整块判成"契约漂移"而不渲染——明明有权看却什么都看不到，比泄露更糟。
 * `COMPLETE` 变体刻意**没有** reason 可填：放行没有理由可编，
 * 拼装代码无法为一个已放行的结果编造解释。
 */
export type AiCrossSourceIntegrity =
  | { reason: string; state: 'PARTIAL' }
  | { reason: string; state: 'WITHHELD' }
  | { reason?: undefined; state: 'COMPLETE' };

export namespace AiCrossSourceApi {
  export type Integrity = AiCrossSourceIntegrity;

  /**
   * 单个来源在本次合并中贡献的金额。
   *
   * 只含角色与金额：数据集编号、来源系统标识与实体键各自都是一份跨系统事实，
   * 把它们放进响应等于把"你无权的那部分"换个字段名再送一次，所以这里没有它们。
   */
  export interface SourceAmount {
    amount: number;
    role: string;
  }

  /**
   * 跨源合并响应。
   *
   * `crossSource` 与 `integrity` 是**两个**字段而不是一个：前者回答"这是不是跨源结果"，
   * 后者回答"有没有来源你无权看"。合成一个字段的话，"口径缺失"就与
   * "单系统响应"无法区分，fail-open 缺口会以另一种形式回来。
   */
  export interface MergeResult {
    /** 技术完整性：本次执行是否产出完整结果。与授权口径是两个维度，不可互相替代。 */
    complete?: boolean | null;
    /** 跨源口径时间点：各来源数据时间的最小值。`WITHHELD` 时为 null。 */
    consistencyAsOf?: null | string;
    /** 跨源标记：本响应恒为 true。 */
    crossSource?: boolean | null;
    currency?: null | string;
    executionKey?: null | string;
    integrity?: Integrity | null;
    /** 各来源数据时间偏移（毫秒）。`WITHHELD` 时为 null。 */
    maxSkewMillis?: null | number;
    metricCode?: null | string;
    /** 参与合并的来源数。`WITHHELD` 时为 null——条数本身就是一条信道。 */
    sourceCount?: null | number;
    sources?: null | SourceAmount[];
    /** 合计金额。`WITHHELD` 时为 null。 */
    totalAmount?: null | number;
  }

  /**
   * 查询参数：后端 5 个 `required = true` 的 `@RequestParam` 加一个可选的
   * `previouslySeenRoles`。角色名认不出会被服务端丢弃并最终 fail-closed 拒绝，
   * 所以这里只声明类型，合法值由页面从闭集里选。
   */
  export interface MergeQuery {
    applicationId: number;
    /** 可重复参数（`List<String>`）：ANALYST / DATA_STEWARD / AGGREGATE_READER */
    callerRoles: string[];
    executionKey: string;
    externalUserId: string;
    previouslySeenRoles?: string[];
    subjectType: string;
  }
}

/**
 * 查询串。
 *
 * **`executionKey` 同时是路径变量和 `@RequestParam`，两处都必须带**：
 * 后端 `result(executionKey, ...)` 的签名是 `@NotBlank @RequestParam("executionKey")`，
 * 只把它写进路径会被 Spring 判成「请求参数缺失:executionKey」（400）。
 * 这是实测踩过的坑，所以单独成函数并注释，不让调用方各自拼串。
 *
 * 角色数组交给 axios 默认的 `paramsSerializer: 'repeat'` 序列化，
 * 得到 `callerRoles=A&callerRoles=B`——正是 `@RequestParam List<String>` 要的形状；
 * 空数组不写进查询串（qs 会整体省略该键），由页面侧的必填校验提前拦下，
 * 而不是让用户收到一句"参数缺失"。
 */
function toQueryParams(query: AiCrossSourceApi.MergeQuery) {
  return {
    applicationId: query.applicationId,
    callerRoles: [...query.callerRoles],
    executionKey: query.executionKey,
    externalUserId: query.externalUserId,
    previouslySeenRoles:
      query.previouslySeenRoles && query.previouslySeenRoles.length > 0
        ? [...query.previouslySeenRoles]
        : undefined,
    subjectType: query.subjectType,
  };
}

/** 结果端点路径。执行键按路径变量转义，避免其中的斜杠或空格改变路由匹配。 */
function resultsPath(executionKey: string, integrityOnly: boolean): string {
  const base = `/ai/cross-source/results/${encodeURIComponent(executionKey)}`;
  return integrityOnly ? `${base}/integrity` : base;
}

/**
 * 读取一次跨源执行的合并结果（权限 `ai:cross-source:query`）。
 *
 * 响应恒带 `crossSource` 与 `integrity`；口径为 `WITHHELD` 时响应里没有任何数字。
 */
export async function getCrossSourceResult(query: AiCrossSourceApi.MergeQuery) {
  return await requestClient.get<AiCrossSourceApi.MergeResult>(
    resultsPath(query.executionKey, false),
    { params: toQueryParams(query) },
  );
}

/**
 * 只读授权完整性口径（权限 `ai:cross-source:integrity`）。
 *
 * 无论判定结论如何，本端点的响应里只可能出现 `state` 与 `reason` 两个字符串。
 * 页面据此决定要不要去取数字：口径为 `WITHHELD` 时，
 * 数字在服务端就从未被序列化过，而不是"发出去了又藏起来"。
 */
export async function getCrossSourceIntegrity(
  query: AiCrossSourceApi.MergeQuery,
) {
  return await requestClient.get<AiCrossSourceApi.Integrity>(
    resultsPath(query.executionKey, true),
    { params: toQueryParams(query) },
  );
}
