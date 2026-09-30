package com.basicframework.module.ai.dal.dataobject.mcp;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * MCP 工具草稿（X07）：远程工具的**待审批**登记。
 *
 * <p>为什么需要这张表而不是直接写 {@code ai_tool}：MCP 工具的"存在"来自上游，
 * 而上游随时可以新增/改名/改参数。平台必须能把"上游现在说了什么"与
 * "平台审批过什么"分开记账，否则就会出现两种坏结果——
 * 上游新增一个工具它就自动可执行（默认放行），或上游悄悄改参数而平台仍按旧参数放行（静默漂移）。
 *
 * <p>三条不变式：
 * <ol>
 *   <li><b>发现即草稿</b>：{@code status=DRAFT} 的记录一定没有 {@code toolId}，
 *       即"未进入注册表"；</li>
 *   <li><b>审批与指纹一一对应</b>：{@code approvedFingerprint} 只在人工审批时写入，
 *       且必须等于当时的 {@code observedFingerprint}；</li>
 *   <li><b>漂移即阻断</b>：再次发现时若指纹与已审批指纹不符，{@code status} 置
 *       {@link #STATUS_BLOCKED} 并记 {@code blockedReasonCode}，旧审批立即失去效力，
 *       直到人工重新审批。</li>
 * </ol>
 *
 * <p>{@code description} 存的是上游原文（不可信文本），只供人工审核时阅读；
 * 任何授权判定都不读它（见 starter-ai {@code McpToolDescriptor} 的提示注入防线说明）。
 */
@TableName("ai_mcp_tool_draft")
@KeySequence("ai_mcp_tool_draft_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiMcpToolDraftDO extends SoftDeletableDO {

    /** 状态：已发现、待审批（不可执行）。 */
    public static final String STATUS_DRAFT = "DRAFT";

    /** 状态：已审批（进入 D08 注册与政策流程，仍受工具政策闸门约束）。 */
    public static final String STATUS_APPROVED = "APPROVED";

    /** 状态：已阻断（上游 schema 漂移导致旧审批失效，必须重新审批）。 */
    public static final String STATUS_BLOCKED = "BLOCKED";

    /** 草稿编号 */
    @TableId
    private Long id;

    /** 连接器编号（MCP 服务器以 HTTP 连接器登记，地址与令牌复用 D01 的加密与校验）。 */
    private Long connectorId;

    /** 上游工具名（MCP 协议内的原始名称）。 */
    private String upstreamToolName;

    /** 上游工具标题（不可信文本，仅供人工审核）。 */
    private String upstreamTitle;

    /** 上游工具描述原文（不可信文本，仅供人工审核；不参与任何授权判定）。 */
    private String upstreamDescription;

    /** 本次观察到的结构指纹（工具名 + 输入 schema，不含描述）。 */
    private String observedFingerprint;

    /**
     * 映射到平台参数面后的输入 schema（JSON 文本）。
     *
     * <p>落库而不是每次审批时重新翻译有两个理由：一是审批人看到的与被登记的<b>必然是同一份</b>
     * （重新翻译意味着上游 schema 与平台映射规则任一变化都会让"审批过的内容"与"登记的内容"错位）；
     * 二是审批动作因此不依赖任何进程内状态。
     */
    private String platformInputSchemaJson;

    /** 已审批的结构指纹（审批时锁定；与 observedFingerprint 不一致即漂移）。 */
    private String approvedFingerprint;

    /** 状态（DRAFT/APPROVED/BLOCKED）。 */
    private String status;

    /** 平台工具编号（审批通过并注册进 ai_tool 后回填；草稿态为空）。 */
    private Long toolId;

    /** 平台工具版本编号（审批时创建并发布后的版本；草稿态为空）。 */
    private Long toolVersionId;

    /** 阻断原因稳定错误码（仅 STATUS_BLOCKED 时有值）。 */
    private Integer blockedReasonCode;

    /** 首次发现时间。 */
    private java.time.LocalDateTime firstDiscoveredAt;

    /** 最近一次发现时间。 */
    private java.time.LocalDateTime lastDiscoveredAt;

    /** 乐观锁版本。 */
    private Integer version;
}
