package com.basicframework.module.ai.enums;

import com.basicframework.framework.common.exception.ErrorCode;

/**
 * 受控 MCP 客户端错误码（X07，{@code 1_003_019_xxx}）。
 *
 * <p>与 Y03/Y04/Y05 同理从 {@link AiErrorCodeConstants} 拆出：主登记册在 Y04 交付后已正好
 * 800 行、零余量，而 {@code check-source-quality} 对任何源文件强制 800 行上限。
 * {@link AiErrorCodeConstants} 继承本接口，因此 {@code AiErrorCodeConstants.AI_MCP_*} 的引用面
 * 与其它能力域一致；全库唯一性与 HTTP 派生按 {@code enums/} 目录下 {@code ErrorCodeConstants}
 * 通配扫描，本接口被独立发现，编号只计一次。
 *
 * <p><b>命名是承重的</b>：HTTP 状态由常量名派生——后缀 {@code _NOT_EXISTS} → 404，
 * 名称含 {@code CONFLICT}/{@code EXISTS}/{@code DUPLICATE} → 409，其余 422（ADR 0003）。
 *
 * <p>本区间的共同主题是**默认拒绝**：网络地址不在允许清单、协议版本不在允许清单、工具未经审批、
 * 上游 schema 与已审批指纹不符——四类都拒绝，且拒绝路径不携带上游正文、主机名或令牌片段。
 * 本区间刻意<b>不</b>用 {@code _NOT_AUTHORIZED} 命名：MCP 侧的"无权"由 D08 的工具政策
 * （{@code AI_TOOL_POLICY_DENIED} 等既有编号）承担，本区间只回答"MCP 准入这一步能不能过"。
 */
public interface AiMcpClientErrorCodeConstants {

    /**
     * MCP 端点地址不被允许（422）：主机/端口不在允许清单、非 https、私网未批准或地址不合法。
     *
     * <p>消息体不含主机名：拒绝消息本身不应成为"这个地址存不存在"的探测通道。
     */
    ErrorCode AI_MCP_ENDPOINT_NOT_ALLOWED = new ErrorCode(1_003_019_000, "MCP 端点地址不在允许范围内");

    /** MCP 协议版本不在允许清单（422）：版本漂移默认拒绝，不降级协商到未验证的旧版本。 */
    ErrorCode AI_MCP_PROTOCOL_VERSION_UNSUPPORTED = new ErrorCode(1_003_019_001, "MCP 协议版本不在允许范围内");

    /**
     * MCP 授权被上游拒绝（422）：令牌无效或权限不足。
     *
     * <p>不重试：同一个令牌重试只会得到同一个拒绝，重试只是放大对上游的压力。
     */
    ErrorCode AI_MCP_AUTHENTICATION_REJECTED = new ErrorCode(1_003_019_002, "MCP 服务器拒绝了授权凭据");

    /**
     * MCP 发现有界重试耗尽（422）：断线后按上限重试，仍失败则**明确终止**。
     *
     * <p>存在的意义是让"连不上"有稳定编号可断言——不允许它退化成空工具清单
     * （那会让"上游没有工具"与"连不上"不可区分）。
     */
    ErrorCode AI_MCP_DISCOVERY_TERMINATED = new ErrorCode(1_003_019_003, "MCP 工具发现有界重试已耗尽并终止");

    /** MCP 工具清单超过单次发现上限（422）：拒绝而不是截断（截断会被读成"上游就这些工具"）。 */
    ErrorCode AI_MCP_TOOL_LIST_EXCEEDED = new ErrorCode(1_003_019_004, "MCP 工具清单超过单次发现上限");

    /**
     * MCP 工具 schema 无法表达为平台参数面（422）：参数名不合规、类型不受支持或未声明参数。
     *
     * <p>这类工具**不生成草稿**：与其猜一个参数面让人审批，不如直接拒绝并要求上游修正声明。
     */
    ErrorCode AI_MCP_TOOL_SCHEMA_UNSUPPORTED = new ErrorCode(1_003_019_005, "MCP 工具参数声明无法映射到平台参数面");

    /**
     * MCP 工具未审批（422）：发现生成的是**待审批草稿**，未经人工审批不可进入执行面。
     *
     * <p>这是验收 1 的落点：发现即草稿、草稿即不可执行，模型无法凭工具名调用未审批工具。
     */
    ErrorCode AI_MCP_TOOL_NOT_APPROVED = new ErrorCode(1_003_019_006, "MCP 工具尚未审批，不能进入执行面");

    /**
     * MCP 工具 schema 漂移（409）：上游升级导致 schema 变化，与已审批指纹不符，阻断旧发布。
     *
     * <p>用 409（状态冲突）而不是 422：这不是"入参不合法"，而是"已发布项的前提被上游改掉了"，
     * 运维需要按冲突处理（人工复核后重发），重试没有意义。
     */
    ErrorCode AI_MCP_TOOL_SCHEMA_DRIFT_CONFLICT = new ErrorCode(1_003_019_007, "MCP 工具参数声明与已审批版本不一致，已阻断");

    /**
     * MCP 工具审批状态冲突（409）：已审批项重复审批，或被上游改掉的项试图沿用旧审批。
     *
     * <p>防止"用一次旧审批反复放行漂移后的新 schema"——审批必须与当前指纹一一对应。
     */
    ErrorCode AI_MCP_TOOL_APPROVAL_CONFLICT = new ErrorCode(1_003_019_008, "MCP 工具审批状态冲突，需重新审批");

    /** MCP 工具草稿不存在（404）：按 (连接器, 上游工具名) 未找到草稿记录。 */
    ErrorCode AI_MCP_TOOL_DRAFT_NOT_EXISTS = new ErrorCode(1_003_019_009, "MCP 工具草稿不存在");

    /**
     * MCP 工具执行路径未接入（422）：本卡只做发现，不代执行远程工具。
     *
     * <p>存在的意义是让"已审批但尚未接入执行"的 MCP 工具<b>显式不可执行</b>：
     * 若不在执行器入口拒绝，它会一路走到 HTTP 执行链路才以"operation 不存在"之类的
     * 无关错误失败，运维会误判成配置问题，而"这个工具到底能不能执行"本身就是一个
     * 需要被明确回答的安全问题。
     */
    ErrorCode AI_MCP_TOOL_NOT_EXECUTABLE = new ErrorCode(1_003_019_010, "MCP 工具执行路径尚未接入，不允许执行");
}
