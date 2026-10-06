package com.basicframework.module.ai.enums;

import com.basicframework.framework.common.exception.ErrorCode;

/**
 * 跨源有界执行错误码（Y04，`1_003_017_xxx`）。
 *
 * <p>从 {@link AiErrorCodeConstants} 拆出是**文件行数**的硬约束：{@code check-source-quality}
 * 对任何源文件强制 800 行上限，而 {@code AiErrorCodeConstants} 在 Y03 交付后已接近上限。
 * 与其把一个登记册继续堆成巨型类，不如按能力域切开——与 Y03 的"一个区间一份登记册"一致。
 *
 * <p>{@link AiErrorCodeConstants} 继承本接口，因此 {@code AiErrorCodeConstants.AI_CROSS_SOURCE_*}
 * 的引用面与其它域保持一致；全库唯一性与 HTTP 派生（{@code GlobalExceptionHandler.resolveHttpStatus}）
 * 按 {@code getDeclaredFields()} 扫描 {@code enums/*ErrorCodeConstants}，本接口被独立扫描，编号只计一次。
 *
 * <p><b>命名是承重的</b>：HTTP 状态由常量名派生——后缀 {@code _NOT_EXISTS} → 404，
 * 名称含 {@code CONFLICT}/{@code EXISTS}/{@code DUPLICATE} → 409，其余 422（ADR 0003）。
 * 去掉 {@code _CONFLICT} 会静默把 409 变成 422。
 *
 * <p>本区间的共同主题是"**受控结束**"：预算超限、来源截断、时间点偏移、超容量都不是
 * 静默降级的理由，每一种都有独立编号，让调用方能区分"为什么没算出来"。
 */
public interface AiCrossSourceExecutionErrorCodeConstants {

    /**
     * 跨源执行记录不存在（404）：执行键无效或已删除，统一同语义。
     *
     * <p>承重命名：{@code GlobalExceptionHandler.resolveHttpStatus} 只认常量名后缀
     * {@code _NOT_EXISTS} → 404。
     */
    ErrorCode AI_CROSS_SOURCE_EXECUTION_NOT_EXISTS = new ErrorCode(1_003_017_000, "跨源执行记录不存在");

    /** 执行幂等键冲突（409）：同一执行键提交了不同的计划指纹（同一键只能对应同一份计算）。 */
    ErrorCode AI_CROSS_SOURCE_EXECUTION_KEY_CONFLICT = new ErrorCode(1_003_017_001, "跨源执行键已用于不同的查询计划");

    /**
     * 计划来源缺少取数规格（422）：计划里选了某个来源，但没有为它提供可执行的取数请求。
     *
     * <p>不"就近取一个数据集"：缺规格说明计划与数据之间没有事实关联。
     */
    ErrorCode AI_CROSS_SOURCE_PLAN_SOURCE_NOT_DECLARED = new ErrorCode(1_003_017_002, "跨源计划缺少该来源的取数规格");

    /** 来源执行超时（409）：单源在自身超时预算内未完成，该源按部分失败策略处理。 */
    ErrorCode AI_CROSS_SOURCE_SOURCE_TIMEOUT_CONFLICT = new ErrorCode(1_003_017_003, "跨源取数超时");

    /**
     * 必需来源失败（409）：来源失败且未在口径里声明为可选，跨源结果不得在缺它的情况下出具。
     *
     * <p>"没有回款记录"与"回款金额是 0"在报表上是两件事，因此必需来源失败一律阻断而不是按 0 补齐。
     */
    ErrorCode AI_CROSS_SOURCE_SOURCE_FAILED_CONFLICT = new ErrorCode(1_003_017_004, "跨源必需来源执行失败");

    /**
     * 调用方取消（409）：等待来源结果期间<b>调用线程</b>被中断。
     *
     * <p>与 {@link #AI_CROSS_SOURCE_SOURCE_TIMEOUT_CONFLICT} 是两件事：超时是"来源没在预算内完成"，
     * 可以重试；取消是"调用方自己不要了"，重试等于替它白干一遍。
     * 归因错位会让调度器重试一个已被放弃的请求，并把排查引向根本没问题的来源。
     *
     * <p>与 F12 的 {@code ExternalHttpException.Reason.CANCELLED} 同源：
     * 中断发生在调用方线程，语义是"被取消/容器关闭"，与对端无关。
     */
    ErrorCode AI_CROSS_SOURCE_CANCELLED_CONFLICT = new ErrorCode(1_003_017_013, "跨源取数已被调用方取消");

    /**
     * 并发来源数超过预算（409）：进入的来源数超出口径声明的并发上限。
     *
     * <p>与 {@link #AI_CROSS_SOURCE_RESULT_TOO_LARGE} 分开：后者是行数/内存预算超限。
     * 两者都是"这次为什么没跑完"，但证据指向完全不同的东西——并发超限要去查来源扇出与
     * 调度，规模超限要去查单次取数形状。报错成后者会让运维对着完全正常的行数证据找原因。
     */
    ErrorCode AI_CROSS_SOURCE_CONCURRENCY_EXCEEDED_CONFLICT = new ErrorCode(1_003_017_014, "跨源并发来源数超过口径声明的并发上限");

    /**
     * 超过行数/内存预算（422）：**受控结束**，不静默截断也不允许把内存吃光。
     *
     * <p>跨源聚合的中间结果按来源预聚合后拉取，行数与字节数都是可以在拉取过程中计量的；
     * 超过预算即终止并保留已完成的来源记录，而不是截断后给出一个偏小的"看起来对"的总数。
     */
    ErrorCode AI_CROSS_SOURCE_RESULT_TOO_LARGE = new ErrorCode(1_003_017_005, "跨源中间结果超过行数或内存预算");

    /**
     * 来源结果被截断（409）：来源在自身行数上限内未能返回完整结果集。
     *
     * <p>这是最危险的一类：源内已按主键粒度预聚合的 SUM 被截断后仍然是"合法数字"，
     * 但它偏小。宁可阻断，也不能让一个偏小的合计看起来像完整结果。
     */
    ErrorCode AI_CROSS_SOURCE_RESULT_TRUNCATED_CONFLICT = new ErrorCode(1_003_017_006, "跨源来源结果被截断，拒绝按不完整数据汇总");

    /**
     * 来源时间点偏移超限（409）：各源数据时间差超过声明的容忍窗口。
     *
     * <p>跨源合计只能解释为"所有来源都成立的那个时刻"，即各源数据时间的**最小值**；
     * 偏移过大时这个时刻离任何一个来源都太远，跨源相加失去意义。
     */
    ErrorCode AI_CROSS_SOURCE_CONSISTENCY_SKEW_CONFLICT = new ErrorCode(1_003_017_007, "跨源各来源数据时间点偏移超过容忍窗口");

    /** 超过数仓容量上限（422）：拒绝执行而不是引入分布式查询集群来"想办法跑完"。 */
    ErrorCode AI_CROSS_SOURCE_CAPACITY_EXCEEDED = new ErrorCode(1_003_017_008, "跨源执行超过数仓容量上限");

    /** 容量登记状态冲突（409）：转登记的执行已处于终态，不允许再次变更登记状态。 */
    ErrorCode AI_CROSS_SOURCE_CAPACITY_REGISTRATION_CONFLICT = new ErrorCode(1_003_017_009, "跨源容量登记状态不允许该变更");

    /**
     * 来源重复计入被拒（409）：同一执行键下该来源已被计入，重试不得再次叠加。
     *
     * <p>重试幂等靠的是"每来源一行 + 乐观锁 + 合计由行求和"这套机制，而不是"重试时小心一点"；
     * 第二次写入在这里被显式拒绝，因此重复贡献不会进入合计。
     */
    ErrorCode AI_CROSS_SOURCE_ALREADY_COUNTED_CONFLICT = new ErrorCode(1_003_017_010, "该来源已计入本次跨源执行，拒绝重复汇总");

    /** 版本化实体键缺失（409）：来源行没有可关联的实体键，无法参与跨源关联。 */
    ErrorCode AI_CROSS_SOURCE_ENTITY_KEY_MISSING_CONFLICT = new ErrorCode(1_003_017_011, "跨源来源行缺少版本化实体键");

    /**
     * 实体键映射版本不一致（409）：参与关联的来源钉在不同映射版本上。
     *
     * <p>"同一个客户编号"在不同映射版本下可能指向不同统一对象，跨版本关联会把两份事实并成一份。
     */
    ErrorCode AI_CROSS_SOURCE_ENTITY_KEY_REVISION_CONFLICT = new ErrorCode(1_003_017_012, "跨源来源的实体键映射版本不一致，拒绝跨版本关联");
}
