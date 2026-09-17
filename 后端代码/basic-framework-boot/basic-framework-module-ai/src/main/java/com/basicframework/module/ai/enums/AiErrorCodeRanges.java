package com.basicframework.module.ai.enums;

/**
 * AI 中台错误码区间与 HTTP 映射（F08 冻结）。
 *
 * <p>区间分配：框架/基础设施使用 {@code 1_001_xxx_xxx}，system 模块使用 {@code 1_002_xxx_xxx}，
 * AI 中台独占 {@code 1_003_xxx_xxx}。子区间按能力域划分，新增域必须领新子区间，不复用他域编号：
 *
 * <pre>
 *   1_003_001_xxx  通用/协议（入参、幂等、状态冲突、权限）
 *   1_003_002_xxx  模型中心（端点、凭据、调用、能力）
 *   1_003_003_xxx  应用与授权（应用、主体、票据、范围）
 *   1_003_004_xxx  运行与任务（运行、任务、取消、配额）
 *   1_003_005_xxx  知识库（文档、解析、索引、检索授权）
 *   1_003_006_xxx  数据与工具（连接器、查询计划、工具执行）
 *   1_003_007_xxx  报表与 Chat（报表、主题、嵌入）
 * </pre>
 *
 * <p>HTTP 映射遵循 [ADR 0003](http-status-semantics) 与 [ADR 0049](identity-boundaries)：
 * 入参非法 400、未认证 401、已认证但无权限 403、资源不存在 404、状态/幂等冲突 409、
 * 限流与配额 429、上游（模型/连接器）失败 502。映射表同步维护在
 * {@code docs/contracts/ai/error-code-map.md}，由本类与文档两侧共同约束。
 */
public final class AiErrorCodeRanges {

    /** AI 中台错误码前缀（{@code 1_003}）。 */
    public static final int AI_PREFIX = 1_003;

    /** 通用/协议子区间。 */
    public static final int DOMAIN_COMMON = 1_003_001;

    /** 模型中心子区间。 */
    public static final int DOMAIN_MODEL = 1_003_002;

    /** 应用与授权子区间。 */
    public static final int DOMAIN_IDENTITY = 1_003_003;

    /** 运行与任务子区间。 */
    public static final int DOMAIN_RUN = 1_003_004;

    /** 知识库子区间。 */
    public static final int DOMAIN_KNOWLEDGE = 1_003_005;

    /** 数据与工具子区间。 */
    public static final int DOMAIN_CONNECTOR = 1_003_006;

    /** 报表与 Chat 子区间。 */
    public static final int DOMAIN_REPORT = 1_003_007;

    private AiErrorCodeRanges() {}
}
