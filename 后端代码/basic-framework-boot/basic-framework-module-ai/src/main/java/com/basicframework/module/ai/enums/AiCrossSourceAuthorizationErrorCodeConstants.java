package com.basicframework.module.ai.enums;

import com.basicframework.framework.common.exception.ErrorCode;

/**
 * 跨系统授权、撤销与完整性错误码（Y05，{@code 1_003_018_xxx}）。
 *
 * <p>从 {@link AiErrorCodeConstants} 拆出是**文件行数**的硬约束：{@code check-source-quality}
 * 对任何源文件强制 800 行上限，而 {@code AiErrorCodeConstants} 在 Y04 交付后已**正好 800 行、零余量**。
 * 与其再往主登记册里塞一个常量，不如按能力域新起一份——与 Y03（{@code 1_003_016_xxx}）、
 * Y04（{@code 1_003_017_xxx}）的既有先例一致。
 *
 * <p>{@link AiErrorCodeConstants} 继承本接口，因此 {@code AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_*}
 * 的引用面与其它域保持一致；全库唯一性与 HTTP 派生（{@code GlobalExceptionHandler.resolveHttpStatus}）
 * 按 {@code enums/} 目录下的 {@code ErrorCodeConstants} 通配 + {@code getDeclaredFields()}
 * 扫描，本接口被独立发现，编号只计一次。
 *
 * <p><b>命名是承重的</b>：HTTP 状态由常量名派生——后缀 {@code _NOT_EXISTS} → 404，
 * 名称含 {@code CONFLICT}/{@code EXISTS}/{@code DUPLICATE} → 409，其余 422（ADR 0003）。
 * 本区间的语义是"**无权就拒绝**"（403 类），但派生规则不产出 403，因此本域一律用
 * {@code _NOT_AUTHORIZED} 这类**明确点名越权对象**的名字承载"无权"，让调用方能区分
 * "资源不存在"与"你无权"；不要为了凑 409 而给授权结论加 {@code _CONFLICT} 后缀。
 *
 * <p>本区间的共同主题是"**拒绝不可区分**"：无权与不存在返回同一种语义，
 * 且拒绝路径不得附带任何关于被拒对象存在性、规模或取值的信息——否则拒绝本身就成了枚举通道。
 */
public interface AiCrossSourceAuthorizationErrorCodeConstants {

    /**
     * 跨源执行键不存在（404）。
     *
     * <p>承重命名：{@code GlobalExceptionHandler.resolveHttpStatus} 只认常量名后缀
     * {@code _NOT_EXISTS} → 404。
     */
    ErrorCode AI_CROSS_SOURCE_AUTHZ_EXECUTION_NOT_EXISTS = new ErrorCode(1_003_018_000, "跨源授权执行记录不存在");

    /**
     * 主体对某个来源系统无权（422）：来源在执行范围内，但该主体对它没有读取授权。
     *
     * <p>与"来源不存在"**分开的编号**是本卡专项二的判定基础：调用方需要知道
     * "是这条来源被拒"才能去申请对应授权，而不是以为整个指标都不存在。
     * 但拒绝路径**不携带**该来源的任何规模、行数或取值。
     */
    ErrorCode AI_CROSS_SOURCE_AUTHZ_SOURCE_NOT_AUTHORIZED = new ErrorCode(1_003_018_001, "当前主体无权访问该跨源来源，拒绝参与合并");

    /**
     * 主体对实体映射无权（422）：**查询计划合法也不放行**。
     *
     * <p>映射（源键↔统一实体）本身就是一份跨系统事实：知道"C-001 在系统 A 与系统 B
     * 是同一实体"就已经泄露了两个系统之间的对应关系。因此"我只是在做关联"不构成豁免理由，
     * 映射必须与来源**同级**授权。
     */
    ErrorCode AI_CROSS_SOURCE_AUTHZ_MAPPING_NOT_AUTHORIZED = new ErrorCode(1_003_018_002, "当前主体无权访问该实体映射，拒绝跨系统关联");

    /**
     * 合计会暴露被禁止明细（422）：有权来源的合计与已发布口径联立后可解出被禁来源的取值。
     *
     * <p>本卡专项一的**核心编号**。多个有权来源求和本身不越权，但"总额 = 有权部分 + 被禁部分"
     * 意味着调用方只要再拿到有权部分就能做减法。因此当被禁来源参与过同口径的既往发布时，
     * 平台**拒绝出具该合计**，而不是照常给一个能被反推的数字。
     */
    ErrorCode AI_CROSS_SOURCE_AUTHZ_TOTAL_EXPOSES_FORBIDDEN_DETAIL =
            new ErrorCode(1_003_018_003, "该合计可反推出无权来源的明细，拒绝出具");

    /**
     * 组合结果的来源计数会暴露被禁来源（422）：差额不可解但**条数可数**。
     *
     * <p>把"参与合并的来源数"或"被排除的来源数"原样回传，调用方就能数出有几个来源被拒，
     * 进而按角色推断被禁来源的存在性与规模。本卡专项一要求**金额与计数两侧同时**阻断。
     */
    ErrorCode AI_CROSS_SOURCE_AUTHZ_SOURCE_COUNT_LEAKS_FORBIDDEN =
            new ErrorCode(1_003_018_004, "来源计数会暴露无权来源，拒绝出具可数的结果");

    /**
     * 模型输入捕获缺失授权（422）：捕获里含有当前主体已无权的数据。
     *
     * <p>本卡专项三的编号。捕获是**已经落到产物里**的旧数据，因此判定不是"再过滤一次"，
     * 而是"确认它当时就在授权范围内、现在也仍在"；两者都成立才允许读取。
     */
    ErrorCode AI_CROSS_SOURCE_AUTHZ_CAPTURE_NOT_AUTHORIZED = new ErrorCode(1_003_018_005, "模型输入捕获包含已失权数据，拒绝读取");

    /**
     * 授权判定输入不合法（422）：主体上下文、来源清单或映射版本缺失。
     *
     * <p>fail-closed：判定要素缺失时**拒绝**而不是"按默认放行"——默认放行会让
     * 一个拼错的调用静默获得全量。
     */
    ErrorCode AI_CROSS_SOURCE_AUTHZ_REQUEST_INVALID = new ErrorCode(1_003_018_006, "跨源授权判定入参不合法");

    /**
     * 角色无权（422）：该主体在本应用下不具备参与跨源读取的角色。
     */
    ErrorCode AI_CROSS_SOURCE_AUTHZ_ROLE_NOT_AUTHORIZED = new ErrorCode(1_003_018_007, "当前主体角色无权参与跨源读取");
}
