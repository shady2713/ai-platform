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
 *   1_003_008_xxx  服务配置（服务发布、评测门槛、运行快照、版本回退）
 *   1_003_009_xxx  评测（套件、样例、评测运行与结果、人工复核）
 *   1_003_010_xxx  多模态媒体（图片理解/OCR/生成/编辑、非实时 STT/TTS 的输入输出准入）
 *   1_003_011_xxx  受控异步结果 Webhook（目标登记、事件白名单、投递管理、人工重投）
 *   1_003_012_xxx  可视化流程编排（流程图契约、版本隔离、受控运行与节点留痕）
 *   1_003_013_xxx  跨系统（主体联邦映射、授权发现与范围选择）
 *   1_003_014_xxx  实时语音（会话协商与短期票据、协议能力验证、背压/打断/重连）
 *   1_003_015_xxx  主数据映射（企业统一对象、源键映射、映射版本与冲突/过期阻断）
 *   1_003_016_xxx  跨源指标口径（口径版本、显式数据集与映射版本、扇出阻断、币种换算与缺口澄清）
 *   1_003_017_xxx  跨源有界执行（来源预算与受控结束、时间点偏移、重试去重与容量拒绝/登记）
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

    /** 服务配置子区间。 */
    public static final int DOMAIN_SERVICE = 1_003_008;

    /** 评测评分子区间（Q04）。 */
    public static final int DOMAIN_EVALUATION = 1_003_009;

    /** 多模态媒体子区间（X01）：媒体能力准入与媒体输入输出校验。 */
    public static final int DOMAIN_MEDIA = 1_003_010;

    /** 受控异步结果 Webhook 子区间（X10）：目标登记、事件白名单、投递管理与人工重投。 */
    public static final int DOMAIN_WEBHOOK = 1_003_011;

    /** 可视化流程编排子区间（X08）：流程图契约、版本隔离与受控运行。 */
    public static final int DOMAIN_WORKFLOW = 1_003_012;

    /** 跨系统子区间（Y01）：主体联邦映射、多系统授权发现与范围选择。 */
    public static final int DOMAIN_CROSS_SYSTEM = 1_003_013;

    /** 实时语音子区间（X05）：会话协商与生命周期、协议能力验证、背压与打断、重连与工具去重。 */
    public static final int DOMAIN_REALTIME = 1_003_014;

    /** 主数据映射子区间（Y02）：企业统一对象、源键映射、映射版本与冲突/过期阻断。 */
    public static final int DOMAIN_MASTER_DATA = 1_003_015;

    /** 跨源指标口径子区间（Y03）：口径版本、扇出关联阻断、币种换算与缺口澄清。 */
    public static final int DOMAIN_METRIC_SEMANTICS = 1_003_016;

    /** 跨源有界执行子区间（Y04）：来源预算与受控结束、时间点偏移、重试去重与容量拒绝/登记。 */
    public static final int DOMAIN_CROSS_SOURCE_EXECUTION = 1_003_017;

    private AiErrorCodeRanges() {}
}
