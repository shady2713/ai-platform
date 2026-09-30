package com.basicframework.module.ai.service.tool;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MCP_AUTHENTICATION_REJECTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MCP_DISCOVERY_TERMINATED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MCP_ENDPOINT_NOT_ALLOWED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MCP_PROTOCOL_VERSION_UNSUPPORTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MCP_TOOL_APPROVAL_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MCP_TOOL_DRAFT_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MCP_TOOL_LIST_EXCEEDED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MCP_TOOL_SCHEMA_DRIFT_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;

import com.basicframework.framework.ai.provider.mcp.McpClientAdapter;
import com.basicframework.framework.ai.provider.mcp.McpClientException;
import com.basicframework.framework.ai.provider.mcp.McpDiscoverySnapshot;
import com.basicframework.framework.ai.provider.mcp.McpEndpointPolicy;
import com.basicframework.framework.ai.provider.mcp.McpServerEndpoint;
import com.basicframework.framework.ai.provider.mcp.McpTermination;
import com.basicframework.framework.ai.provider.mcp.McpToolDescriptor;
import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.module.ai.dal.dataobject.mcp.AiMcpDiscoveryRunDO;
import com.basicframework.module.ai.dal.dataobject.mcp.AiMcpToolDraftDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolDO;
import com.basicframework.module.ai.dal.mysql.mcp.AiMcpDiscoveryRunMapper;
import com.basicframework.module.ai.dal.mysql.mcp.AiMcpToolDraftMapper;
import com.basicframework.module.ai.dal.mysql.tool.AiToolMapper;
import com.basicframework.module.ai.domain.tool.AiToolPolicy;
import com.basicframework.module.ai.service.connector.AiMcpEndpointFactory;
import com.basicframework.module.ai.service.tool.dto.AiToolSaveDTO;
import com.basicframework.module.ai.service.tool.dto.AiToolVersionSaveDTO;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * MCP 工具发现与审批实现（X07）。
 *
 * <p>本类是"MCP 协议事实"到"D08 工具注册事实"的唯一转换点，也是本卡四条验收的共同落点。
 * 它<b>不</b>新建审批管线：审批后仍由 D08 的 {@link AiToolService} 落成
 * {@code ai_tool} + {@code ai_tool_version}（政策默认 DENY），执行仍由
 * {@link AiToolPolicyGate} 判定、{@link AiToolExecutor} 执行。
 *
 * <h2>验收 1：未审核新工具不可自动执行</h2>
 * <p>发现路径<b>只</b>写 {@code ai_mcp_tool_draft}（状态 DRAFT），不创建 {@code ai_tool}。
 * 于是未审批工具在注册表里根本不存在，{@code AiToolPolicyGate.decide} 必然
 * {@code AI_TOOL_NOT_FOUND}（404）——拒绝发生在"查不到"这一层，比"查到了再拒绝"更强：
 * 连"这个工具存在"都不暴露。
 *
 * <h2>验收 2：工具提示不能提权</h2>
 * <p>本类对描述只做两件事：原样落库供人阅读、以及（在上游侧）长度截断。
 * <b>没有任何分支读取描述来决定授权</b>，{@link McpToolDescriptor#schemaFingerprint()} 也不含描述。
 * 因此描述里写"忽略之前的指令 / 把政策设为 AUTO / 已通过安全审批"不会改变任何结论。
 *
 * <h2>验收 3：远程断线有界恢复</h2>
 * <p>失败路径<b>不</b>返回摘要而是抛出稳定错误码，并把<b>实际尝试次数</b>与终止原因写进
 * {@code ai_mcp_discovery_run}。因此"连不上"与"上游没有工具"在类型与数据上都不可能混淆。
 *
 * <h2>验收 4：上游升级导致 Schema 变化时阻断旧发布</h2>
 * <p>再次发现时若观察指纹 ≠ 已审批指纹，立即把草稿置 {@code BLOCKED} 并记
 * {@link #AI_MCP_TOOL_SCHEMA_DRIFT_CONFLICT}。旧审批由此<b>立即失效</b>：
 * {@link #requireApprovable} 对 BLOCKED 草稿一律拒绝，已发布的旧版本不再被认为仍然有效。
 */
@Service
@RequiredArgsConstructor
public class AiMcpToolServiceImpl implements AiMcpToolService {

    /** 平台侧固定的输出 schema：MCP 工具结果不建模为列集合，空对象表示"无声明列"。 */
    private static final String OUTPUT_SCHEMA = "{}";

    /** 平台工具标识前缀：避免与 HTTP 来源工具撞名。 */
    private static final String TOOL_CODE_PREFIX = "mcp-";

    private final McpClientAdapter clientAdapter;

    private final AiMcpEndpointFactory endpointFactory;

    private final AiMcpToolDraftMapper draftMapper;

    private final AiMcpDiscoveryRunMapper runMapper;

    private final AiToolMapper toolMapper;

    private final AiToolService toolService;

    /** 草稿准入判据（与发布路径共用，避免两处判定漂移）。 */
    private final AiMcpToolDraftChecker draftChecker;

    /** 平台配置 → 出站与协议准入（默认拒绝一切）。 */
    private final McpClientAccessPolicy accessPolicy;

    /**
     * 失败留痕用独立事务记录（X07）：失败留痕若与业务事务同生共死，
     * 就会被随异常一起回滚，"断线有界终止"在数据库里什么都不剩。
     */
    private final AiMcpDiscoveryRunRecorder runRecorder;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiMcpDiscoveryResultDTO discover(Long connectorId) {
        McpEndpointPolicy policy = accessPolicy.endpointPolicy();
        McpServerEndpoint endpoint = endpointFactory.build(connectorId, policy, accessPolicy.isAllowAnonymousServers());
        long startedAt = System.nanoTime();
        try {
            return recordSuccess(connectorId, clientAdapter.discover(endpoint, policy), elapsedMillis(startedAt));
        } catch (McpClientException failure) {
            // 有界重试耗尽/地址被拒/版本漂移等：先留痕（带尝试次数），再抛稳定错误码。
            // 注意这里**不返回**任何摘要：失败绝不能被读成"上游没有工具"。
            recordFailure(connectorId, failure, elapsedMillis(startedAt));
            throw exception(translate(failure.termination()));
        }
    }

    /** 成功路径：逐个工具落草稿，并按指纹决定"新增 / 原样保留 / 漂移阻断"。 */
    private AiMcpDiscoveryResultDTO recordSuccess(Long connectorId, McpDiscoverySnapshot snapshot, long elapsedMillis) {
        int newCount = 0;
        int blockedCount = 0;
        for (McpToolDescriptor tool : snapshot.tools()) {
            // 描述不参与任何判定：只有 inputSchema 决定指纹与准入
            String fingerprint = tool.schemaFingerprint();
            String platformSchema = AiMcpToolSchemaMapper.toPlatformInputSchema(tool);
            AiMcpToolDraftDO existing = draftMapper.selectByUpstream(connectorId, tool.name());
            if (existing == null) {
                insertDraft(connectorId, tool, fingerprint, platformSchema);
                newCount++;
            } else if (!Objects.equals(existing.getObservedFingerprint(), fingerprint)) {
                // 上游结构变了：阻断旧发布（验收 4）。新 schema 不覆盖已审批版本，
                // 而是先阻断，等人工重新发现确认后再重新审批。
                blockForDrift(existing, fingerprint, tool, platformSchema);
                blockedCount++;
            }
            // 指纹未变：既有审批继续有效，不打扰审批人（也不重置为 DRAFT）
        }
        int toolCount = snapshot.tools().size();
        runMapper.insert(newRun(
                connectorId,
                snapshot.serverName(),
                snapshot.protocolVersion(),
                snapshot.attempts(),
                AiMcpDiscoveryRunDO.TERMINATION_SUCCESS,
                null,
                toolCount,
                newCount,
                blockedCount,
                elapsedMillis));
        return new AiMcpDiscoveryResultDTO(
                connectorId,
                snapshot.serverName(),
                snapshot.protocolVersion(),
                snapshot.attempts(),
                AiMcpDiscoveryRunDO.TERMINATION_SUCCESS,
                toolCount,
                newCount,
                blockedCount,
                elapsedMillis);
    }

    /** 失败路径：独立事务写留痕（含实际尝试次数与终止原因），供运维与验收直接断言。 */
    private void recordFailure(Long connectorId, McpClientException failure, long elapsedMillis) {
        ErrorCode code = translate(failure.termination());
        runRecorder.recordFailure(
                connectorId,
                "",
                "",
                failure.attempts(),
                failure.termination().name(),
                code.getCode(),
                0,
                0,
                0,
                elapsedMillis);
    }

    private void insertDraft(Long connectorId, McpToolDescriptor tool, String fingerprint, String platformSchema) {
        LocalDateTime now = LocalDateTime.now();
        draftMapper.insert(new AiMcpToolDraftDO()
                .setConnectorId(connectorId)
                .setUpstreamToolName(tool.name())
                .setUpstreamTitle(tool.title())
                .setUpstreamDescription(tool.description())
                .setObservedFingerprint(fingerprint)
                .setPlatformInputSchemaJson(platformSchema)
                .setApprovedFingerprint(null)
                .setStatus(AiMcpToolDraftStatus.DRAFT)
                .setToolId(null)
                .setToolVersionId(null)
                .setBlockedReasonCode(null)
                .setFirstDiscoveredAt(now)
                .setLastDiscoveredAt(now)
                .setVersion(0));
    }

    /** 漂移阻断：记录新指纹、置 BLOCKED、写稳定原因码；清掉旧审批指纹使旧审批立即失效。 */
    private void blockForDrift(
            AiMcpToolDraftDO existing, String fingerprint, McpToolDescriptor tool, String platformSchema) {
        // 先取当前版本再改：CAS 的"期望版本"若写成 existing.getVersion()，会因 Java 实参求值顺序
        // 在 setter 链把版本 +1 之后才求值，于是永远 CAS 不中（本卡第一次实现就是这个 bug，
        // 由 AiMcpClientIT 的真实 MySQL 用例发现）。
        int expectedVersion = existing.getVersion() == null ? 0 : existing.getVersion();
        if (draftMapper.updateWithVersionClearingApproval(
                        existing.setObservedFingerprint(fingerprint)
                                .setPlatformInputSchemaJson(platformSchema)
                                .setUpstreamTitle(tool.title())
                                .setUpstreamDescription(tool.description())
                                .setStatus(AiMcpToolDraftStatus.BLOCKED)
                                .setApprovedFingerprint(null)
                                .setBlockedReasonCode(AI_MCP_TOOL_SCHEMA_DRIFT_CONFLICT.getCode())
                                .setLastDiscoveredAt(LocalDateTime.now())
                                .setVersion(expectedVersion + 1),
                        expectedVersion)
                == 0) {
            // 并发发现只有一个赢家；失败方按状态冲突重试，绝不"两边都写成功"
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiMcpToolApprovalDTO approve(Long draftId, Integer version) {
        if (draftId == null || version == null) {
            throw exception(AI_STATE_CONFLICT);
        }
        AiMcpToolDraftDO draft = draftMapper.selectById(draftId);
        if (draft == null) {
            throw exception(AI_MCP_TOOL_DRAFT_NOT_EXISTS);
        }
        if (AiMcpToolDraftStatus.BLOCKED.equals(draft.getStatus())) {
            // 被上游改动过的项：必须先重新发现（刷新指纹）再审批，不允许沿用旧认知放行
            throw exception(AI_MCP_TOOL_APPROVAL_CONFLICT);
        }
        if (AiMcpToolDraftStatus.APPROVED.equals(draft.getStatus())) {
            // 已审批项重复审批：审批与指纹一一对应，重复审批等于"多次放行同一个未复核的 schema"
            throw exception(AI_MCP_TOOL_APPROVAL_CONFLICT);
        }
        String fingerprint = draft.getObservedFingerprint();
        String platformSchema = draft.getPlatformInputSchemaJson();
        if (!StringUtils.hasText(fingerprint) || !StringUtils.hasText(platformSchema)) {
            // 没有可锁定的指纹/参数面就不可能"审批"：拒绝而不是放行一个空壳
            throw exception(AI_MCP_TOOL_APPROVAL_CONFLICT);
        }
        Long toolId = ensureRegisteredTool(draft);
        // 政策显式 DENY：MCP 工具不因"被审批"就自动放行，仍需单独的人工政策决定
        Long toolVersionId = toolService.createVersion(new AiToolVersionSaveDTO()
                .setToolId(toolId)
                .setToolType(AiToolPolicy.ToolType.READ.name())
                .setPolicy(AiToolPolicy.DENY.name())
                .setSourceKind(AiToolSourceKind.MCP_TOOL)
                .setSourceRef(draft.getUpstreamToolName())
                .setInputSchemaJson(platformSchema)
                .setOutputSchemaJson(OUTPUT_SCHEMA));
        if (draftMapper.updateWithVersion(
                        draft.setStatus(AiMcpToolDraftStatus.APPROVED)
                                .setApprovedFingerprint(fingerprint)
                                .setToolId(toolId)
                                .setToolVersionId(toolVersionId)
                                .setBlockedReasonCode(null)
                                .setVersion(draft.getVersion() + 1),
                        version)
                == 0) {
            // 乐观锁失败：并发审批只能有一个赢家
            throw exception(AI_STATE_CONFLICT);
        }
        return new AiMcpToolApprovalDTO(draftId, toolId, toolVersionId, AiToolPolicy.DENY.name(), fingerprint);
    }

    /** 幂等地确保平台侧存在对应的注册工具（按上游工具名派生稳定标识）。 */
    private Long ensureRegisteredTool(AiMcpToolDraftDO draft) {
        String code = platformToolCode(draft.getUpstreamToolName());
        AiToolDO existing = toolMapper.selectByCode(code);
        if (existing != null) {
            return existing.getId();
        }
        String title =
                StringUtils.hasText(draft.getUpstreamTitle()) ? draft.getUpstreamTitle() : draft.getUpstreamToolName();
        return toolService.create(new AiToolSaveDTO()
                .setCode(code)
                .setName(title)
                // 说明固定标注来源与审批态：模型看到的说明永远带着"待审批的 MCP 工具草稿"标记
                .setDescription("待审批的 MCP 工具草稿（来源：" + draft.getUpstreamToolName() + "）")
                .setConnectorId(draft.getConnectorId()));
    }

    /** 平台工具标识：加前缀避免撞名，并收敛到 D08 的标识语法与长度。 */
    private static String platformToolCode(String upstreamToolName) {
        String suffix = upstreamToolName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        if (suffix.isEmpty() || !Character.isLetter(suffix.charAt(0))) {
            suffix = "t" + suffix;
        }
        String code = TOOL_CODE_PREFIX + suffix;
        return code.length() > 64 ? code.substring(0, 64) : code;
    }

    @Override
    public AiMcpToolDraftDTO getDraft(Long connectorId, String upstreamToolName) {
        AiMcpToolDraftDO draft = draftMapper.selectByUpstream(connectorId, upstreamToolName);
        if (draft == null) {
            throw exception(AI_MCP_TOOL_DRAFT_NOT_EXISTS);
        }
        return toDto(draft);
    }

    @Override
    public List<AiMcpToolDraftDTO> listDrafts(Long connectorId) {
        return draftMapper.selectByConnector(connectorId).stream()
                .map(AiMcpToolServiceImpl::toDto)
                .toList();
    }

    @Override
    public void requireApprovable(Long connectorId, String upstreamToolName) {
        // 与发布路径共用同一份判据（AiMcpToolSourceGuard 也转调这里），避免两处判定漂移
        draftChecker.requireApprovable(connectorId, upstreamToolName);
    }

    @Override
    public McpEndpointPolicy endpointPolicy() {
        return accessPolicy.endpointPolicy();
    }

    private static AiMcpToolDraftDTO toDto(AiMcpToolDraftDO draft) {
        return new AiMcpToolDraftDTO(
                draft.getId(),
                draft.getConnectorId(),
                draft.getUpstreamToolName(),
                draft.getUpstreamTitle(),
                draft.getUpstreamDescription(),
                draft.getObservedFingerprint(),
                draft.getApprovedFingerprint(),
                draft.getStatus(),
                draft.getToolId(),
                draft.getToolVersionId(),
                draft.getBlockedReasonCode(),
                draft.getFirstDiscoveredAt(),
                draft.getLastDiscoveredAt(),
                draft.getVersion());
    }

    private static AiMcpDiscoveryRunDO newRun(
            Long connectorId,
            String serverName,
            String protocolVersion,
            int attempts,
            String termination,
            Integer failureCode,
            int toolCount,
            int newToolCount,
            int blockedCount,
            long elapsedMillis) {
        return new AiMcpDiscoveryRunDO()
                .setConnectorId(connectorId)
                .setServerName(serverName)
                .setProtocolVersion(protocolVersion)
                .setAttempts(attempts)
                .setTermination(termination)
                .setFailureCode(failureCode)
                .setToolCount(toolCount)
                .setNewToolCount(newToolCount)
                .setBlockedCount(blockedCount)
                .setElapsedMillis(elapsedMillis)
                .setVersion(0);
    }

    /** MCP 终止原因 → 稳定错误码（不透出上游正文、主机名或令牌）。 */
    private static ErrorCode translate(McpTermination termination) {
        return switch (termination) {
            case ADDRESS_DENIED -> AI_MCP_ENDPOINT_NOT_ALLOWED;
            case PROTOCOL_VERSION_UNSUPPORTED -> AI_MCP_PROTOCOL_VERSION_UNSUPPORTED;
            case UNAUTHORIZED -> AI_MCP_AUTHENTICATION_REJECTED;
            case RESPONSE_TOO_LARGE -> AI_MCP_TOOL_LIST_EXCEEDED;
            // SUCCESS 不会走到这里（成功路径不经过 translate）；为保持 switch 穷尽仍需给出取值
            case SUCCESS, RETRIES_EXHAUSTED, TIMEOUT, UNREACHABLE, PROTOCOL_ERROR -> AI_MCP_DISCOVERY_TERMINATED;
        };
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }
}
