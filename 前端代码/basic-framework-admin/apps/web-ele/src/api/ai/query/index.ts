import { requestClient } from '#/api/request';

/**
 * AI 查询规划 API（D05，菜单 4070/4071）。
 *
 * 只有两个端点、**没有列表接口**：计划是一次性调用（服务层不落库，前端拿不到历史计划），
 * 摘要按数据集实时生成。所以页面只能是"填条件 → 提交 → 看结果"的操作页，
 * 任何按分页列表去包装这个域的做法都会向用户承诺后端并不存在的能力。
 *
 * 契约要点：入参只有自然语言问题与授权字段，**没有任何 SQL 字段**；
 * 模型输出 SQL 片段在服务层被直接拒绝，页面因此也不提供任何 SQL 编辑面。
 */
export namespace AiQueryApi {
  /** 澄清候选：只来自本次授权目录，页面按只读选项展示 */
  export interface PlanCandidate {
    code: string;
    label: string;
  }

  /**
   * 计划结果：只有两种**正常**结果，由 kind 区分。
   * PLAN 带 planHash/planJson；CLARIFICATION 带 question/reason/candidates。
   * 两种都不含上游数据行、物理表名、权限码与凭据。
   */
  export interface PlanResult {
    /** 结果类型（PLAN/CLARIFICATION） */
    kind: string;
    attempts?: number;
    /** 澄清候选（CLARIFICATION 时存在） */
    candidates?: PlanCandidate[];
    datasetCode?: string;
    datasetId?: number;
    datasetVersionId?: number;
    datasetVersionNo?: number;
    /** 已校验计划（规范化 JSON 文本；PLAN 时存在） */
    planJson?: string;
    /** 计划中的数据集标识（dset_ 前缀） */
    planDatasetId?: string;
    /** 计划内容哈希（同一份计划永远同一哈希） */
    planHash?: string;
    /** 澄清追问（CLARIFICATION 时存在） */
    question?: string;
    /** 澄清原因（AMBIGUOUS、UNSUPPORTED、OUT_OF_SCOPE 三者之一） */
    reason?: string;
    /** 定义内容哈希 */
    schemaHash?: string;
  }

  /**
   * 生成计划请求。
   * question 必填（后端 @NotEmpty @Size(max = 2000)），datasetVersionId 缺省取最新已发布版本，
   * allowedFieldCodes 缺省为数据集定义的全部字段。
   */
  export interface PlanReq {
    allowedFieldCodes?: string[];
    datasetId: number;
    datasetVersionId?: number;
    endpointId: number;
    /** 允许的修复次数（后端夹取到 0~2） */
    maxRepairs?: number;
    question: string;
  }

  /** 摘要结果：模型能看到的全部信息，用于自检信息边界 */
  export interface SummaryResult {
    datasetId?: number;
    summaryJson?: string;
  }

  /** 摘要请求：只接受数据集维度的参数（与 plan 端点是两套契约） */
  export interface SummaryParams {
    allowedFieldCodes?: string[];
    datasetId: number;
    datasetVersionId?: number;
  }
}

/** 生成查询计划（或返回澄清追问；不接受 SQL） */
export async function createQueryPlan(data: AiQueryApi.PlanReq) {
  return await requestClient.post<AiQueryApi.PlanResult>(
    '/ai/query/plan',
    data,
  );
}

/**
 * 查看模型可见的数据集摘要（只含授权字段与语义元数据）。
 * 数组参数按 repeat 序列化（requestClient 默认），后端按 List<String> 绑定。
 */
export async function getQueryDatasetSummary(params: AiQueryApi.SummaryParams) {
  return await requestClient.get<AiQueryApi.SummaryResult>(
    '/ai/query/summary',
    {
      params,
    },
  );
}
