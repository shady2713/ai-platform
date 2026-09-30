package com.basicframework.module.ai.enums;

import com.basicframework.framework.common.exception.ErrorCode;

/**
 * AI 中台错误码（区间与 HTTP 映射见 {@link AiErrorCodeRanges}）。
 *
 * <p>当前只登记协议与授权边界上已冻结、可被其它任务直接复用的错误码；各能力域实现时在所属子区间
 * 追加编号，禁止改动既有编号语义（错误码是长期协议的一部分）。
 */
/**
 * 跨源指标口径错误码（Y03，`1_003_016_xxx`）。
 *
 * <p>从 {@link AiErrorCodeConstants} 拆出是**文件行数**的硬约束：{@code check-source-quality}
 * 对任何源文件强制 800 行上限，而 Y02 交付时 {@code AiErrorCodeConstants} 已经正好 800 行。
 * 与其把一个登记册继续堆成巨型类，不如按能力域切开——语义与 Y02 的"一个区间一份登记册"一致。
 *
 * <p>{@link AiErrorCodeConstants} 继承本接口，因此
 * {@code AiErrorCodeConstants.AI_METRIC_*} 的引用面保持不变；全库唯一性与 HTTP 派生
 * （{@code GlobalExceptionHandler.resolveHttpStatus}）按 {@code getDeclaredFields()} 扫描
 * {@code enums/*ErrorCodeConstants}，本接口被独立扫描，编号只计一次。
 *
 * <p><b>命名是承重的</b>：HTTP 状态由常量名派生——后缀 {@code _NOT_EXISTS} → 404，
 * 名称含 {@code CONFLICT}/{@code EXISTS}/{@code DUPLICATE} → 409，其余 422。
 */
public interface AiMetricSemanticsErrorCodeConstants {

    /**
     * 指标口径不存在（404）：口径标识或版本号无效、已删除，统一同语义。
     *
     * <p>承重命名：{@code GlobalExceptionHandler.resolveHttpStatus} 只认常量名后缀
     * {@code _NOT_EXISTS} → 404（ADR 0003）。
     */
    ErrorCode AI_METRIC_SEMANTICS_NOT_EXISTS = new ErrorCode(1_003_016_000, "跨源指标口径不存在");

    /** 口径标识重复（409）：口径标识全局唯一且不可修改（它是跨源聚合的锚点）。 */
    ErrorCode AI_METRIC_SEMANTICS_CODE_DUPLICATE = new ErrorCode(1_003_016_001, "该跨源指标口径标识已存在");

    /** 口径已停用（409）：停用后一切跨源聚合阻断，不得静默当作可用。 */
    ErrorCode AI_METRIC_SEMANTICS_DISABLED_CONFLICT = new ErrorCode(1_003_016_002, "跨源指标口径已停用，不能用于跨源聚合");

    /**
     * 口径版本不存在（404）：版本号无效或不属于该口径，统一同语义。
     *
     * <p>承重命名：{@code GlobalExceptionHandler.resolveHttpStatus} 只认常量名后缀
     * {@code _NOT_EXISTS} → 404（ADR 0003）。
     */
    ErrorCode AI_METRIC_SEMANTICS_REVISION_NOT_EXISTS = new ErrorCode(1_003_016_003, "跨源指标口径版本不存在");

    /** 口径版本尚未发布（409）：草稿不是可核验事实，不能用于聚合或作为报表依据。 */
    ErrorCode AI_METRIC_SEMANTICS_REVISION_NOT_PUBLISHED_CONFLICT =
            new ErrorCode(1_003_016_004, "跨源指标口径版本尚未发布，不能用于跨源聚合");

    /** 口径版本已发布（409）：已发布版本不可变，改口径必须新建版本（换版本不改旧结果）。 */
    ErrorCode AI_METRIC_SEMANTICS_REVISION_PUBLISHED_CONFLICT =
            new ErrorCode(1_003_016_005, "跨源指标口径版本已发布，不能修改或删除其来源声明");

    /** 口径版本有效期已过（409）：聚合时刻不在版本有效期内，阻断而不是回退到最新版本。 */
    ErrorCode AI_METRIC_SEMANTICS_REVISION_EXPIRED_CONFLICT = new ErrorCode(1_003_016_006, "跨源指标口径版本有效期已过，不能用于该时刻的聚合");

    /** 口径版本内容指纹不符（409）：冻结指纹与重算结果不一致（内容被版本外改动），聚合阻断。 */
    ErrorCode AI_METRIC_SEMANTICS_FINGERPRINT_CONFLICT = new ErrorCode(1_003_016_007, "跨源指标口径版本内容指纹不符，版本内容已被改动");

    /** 来源声明不合法（422）：角色、粒度键、时区或聚合顺序不合规（未知取值一律拒绝）。 */
    ErrorCode AI_METRIC_SOURCE_INVALID = new ErrorCode(1_003_016_008, "跨源指标来源声明不合法");

    /** 来源声明重复（409）：同一版本内同一数据集版本只能声明一次。 */
    ErrorCode AI_METRIC_SOURCE_DUPLICATE = new ErrorCode(1_003_016_009, "该数据集版本在本口径版本中已声明");

    /**
     * 查询计划未显式选择数据集或映射版本（422）：跨源聚合必须逐个来源显式钉住
     * {@code datasetVersion} 与 {@code mappingRevision}，不接受"取当前版本"的省略写法。
     */
    ErrorCode AI_METRIC_PLAN_SELECTION_REQUIRED = new ErrorCode(1_003_016_010, "跨源查询计划必须为每个来源显式选择数据集版本与映射版本");

    /** 查询计划选择了口径未声明的来源（422）：该来源不在本口径版本的来源声明内，不得凭空参与聚合。 */
    ErrorCode AI_METRIC_PLAN_SOURCE_NOT_DECLARED = new ErrorCode(1_003_016_011, "查询计划选择的来源不在该口径版本的来源声明内");

    /**
     * 不安全扇出关联（409）：同一事实经多对多路径重复参与聚合（缺少先按各自主键粒度预聚合的声明），
     * 会重复计算，必须阻断而不是让模型自己"注意别重复"。
     */
    ErrorCode AI_METRIC_FANOUT_UNSAFE_CONFLICT = new ErrorCode(1_003_016_012, "跨源聚合存在不安全扇出：来源未按各自主键粒度预聚合，同一事实会被重复计算");

    /**
     * 币种不一致且无换算规则（409）：不同币种在没有显式换算规则时**禁止相加**，
     * 绝不静默按数值直接求和。
     */
    ErrorCode AI_METRIC_CURRENCY_CONVERSION_MISSING_CONFLICT = new ErrorCode(1_003_016_013, "来源币种不一致且未声明换算规则，禁止跨币种求和");

    /** 换算规则本身冲突（409）：换算规则引用的目标币种与目标口径不一致，或重复声明。 */
    ErrorCode AI_METRIC_CONVERSION_RULE_CONFLICT = new ErrorCode(1_003_016_014, "跨源换算规则与目标口径冲突");

    /**
     * 口径不一致（409）：来源的单位/时区/时间窗口/主键粒度与口径声明不一致，
     * 必须显式解决，**绝不**让模型自己推断一个"看起来合理"的口径。
     */
    ErrorCode AI_METRIC_CALIBER_CONFLICT = new ErrorCode(1_003_016_015, "来源口径与跨源指标口径声明冲突，必须显式解决");

    /** 口径缺失（409）：来源缺少口径声明的必需项（单位/时区/粒度/币种），不允许按默认值补全。 */
    ErrorCode AI_METRIC_CALIBER_MISSING_CONFLICT = new ErrorCode(1_003_016_016, "来源缺少必需的跨源指标口径声明，不允许推断补全");

    /** 聚合顺序冲突（409）：聚合顺序未把每个来源放在"先按各自粒度聚合、再关联"的位置。 */
    ErrorCode AI_METRIC_AGGREGATION_ORDER_CONFLICT = new ErrorCode(1_003_016_017, "聚合顺序不满足先按各自主键粒度聚合再关联的要求");

    /** 缺口需要澄清（422）：来源数据存在缺口且未声明完整性策略，必须追问而不是按 0 静默补齐。 */
    ErrorCode AI_METRIC_GAP_CLARIFICATION_REQUIRED = new ErrorCode(1_003_016_018, "跨源聚合存在数据缺口且未声明完整性策略，必须澄清后继续");

    /** 发布人冲突（409）：独立审核要求发布人不同于草稿创建人。 */
    ErrorCode AI_METRIC_SEMANTICS_PUBLISHER_CONFLICT = new ErrorCode(1_003_016_019, "跨源指标口径版本必须由草稿创建人之外的审核人发布");
}
