package com.basicframework.module.ai.service.tool;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_OPERATION_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_OPERATION_NOT_PUBLISHED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_ARGUMENT_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_CODE_DUPLICATE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_REFERENCED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_TYPE_UNSUPPORTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_VERSION_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_VERSION_NOT_PUBLISHED;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorOperationDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolVersionDO;
import com.basicframework.module.ai.dal.mysql.connector.AiConnectorMapper;
import com.basicframework.module.ai.dal.mysql.connector.AiConnectorOperationMapper;
import com.basicframework.module.ai.dal.mysql.tool.AiToolMapper;
import com.basicframework.module.ai.dal.mysql.tool.AiToolVersionMapper;
import com.basicframework.module.ai.domain.tool.AiToolInputSchema;
import com.basicframework.module.ai.domain.tool.AiToolPolicy;
import com.basicframework.module.ai.service.tool.dto.AiToolSaveDTO;
import com.basicframework.module.ai.service.tool.dto.AiToolVersionSaveDTO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 工具注册与版本实现（D08）。
 *
 * <p>三条不变式：
 * <ol>
 *   <li><b>政策默认 DENY 且在版本里</b>：新建版本不写政策就是 DENY；发布后政策不可修改；</li>
 *   <li><b>首期只发布读工具</b>：WRITE 版本一律拒绝发布（宁可没有，也不要一个能改数据的通道）；</li>
 *   <li><b>来源必须已发布</b>：版本绑定的是 operationKey，发布前确认该 operation 存在且已发布，
 *       否则工具执行时才发现问题就太晚了。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AiToolServiceImpl implements AiToolService {

    /** 工具标识：字母开头，字母数字与连字符/下划线，长度 3..64。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{2,63}$");

    private static final int MAX_NAME_LENGTH = 128;

    private static final int MAX_DESCRIPTION_LENGTH = 512;

    private static final int MAX_SOURCE_REF_LENGTH = 128;

    private static final int MAX_OUTPUT_SCHEMA_LENGTH = 3_500;

    private final AiToolMapper toolMapper;

    private final AiToolVersionMapper versionMapper;

    private final AiConnectorMapper connectorMapper;

    private final AiConnectorOperationMapper operationMapper;

    /** 引用检查：服务发布版本/分析步骤在各自卡片注册实现。 */
    private final List<AiToolReferenceChecker> referenceCheckers;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(AiToolSaveDTO saveDTO) {
        requireSaveFields(saveDTO, true);
        requireConnector(saveDTO.getConnectorId());
        if (toolMapper.selectByCode(saveDTO.getCode()) != null) {
            throw exception(AI_TOOL_CODE_DUPLICATE, saveDTO.getCode());
        }
        AiToolDO tool = new AiToolDO()
                .setCode(saveDTO.getCode())
                .setName(saveDTO.getName())
                .setDescription(saveDTO.getDescription() == null ? "" : saveDTO.getDescription())
                .setConnectorId(saveDTO.getConnectorId())
                .setStatus(AiToolDO.STATUS_ENABLED)
                .setLatestVersionNo(0)
                .setVersion(0);
        toolMapper.insert(tool);
        return tool.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(AiToolSaveDTO saveDTO) {
        requireSaveFields(saveDTO, false);
        AiToolDO existing = requireTool(saveDTO.getId());
        if (toolMapper.updateWithVersion(
                        new AiToolDO()
                                .setId(existing.getId())
                                .setName(saveDTO.getName())
                                .setDescription(saveDTO.getDescription() == null ? "" : saveDTO.getDescription())
                                .setVersion(existing.getVersion() + 1),
                        saveDTO.getVersion())
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer version, Boolean enabled) {
        if (version == null || enabled == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiToolDO existing = requireTool(id);
        if (toolMapper.updateWithVersion(
                        new AiToolDO()
                                .setId(id)
                                .setStatus(enabled ? AiToolDO.STATUS_ENABLED : AiToolDO.STATUS_DISABLED)
                                .setVersion(existing.getVersion() + 1),
                        version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Integer version) {
        if (version == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiToolDO existing = requireTool(id);
        Optional<String> reference = referenceCheckers.stream()
                .map(checker -> checker.findReference(id))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
        if (reference.isPresent()) {
            throw exception(AI_TOOL_REFERENCED);
        }
        if (toolMapper.updateWithVersion(new AiToolDO().setId(id).setVersion(existing.getVersion() + 1), version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        toolMapper.deleteById(id);
    }

    @Override
    public AiToolDO getTool(Long id) {
        return requireTool(id);
    }

    @Override
    public PageResult<AiToolDO> getToolPage(PageParam pageParam, Long connectorId, String status) {
        requirePageParam(pageParam);
        return toolMapper.selectPage(pageParam, connectorId, status);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createVersion(AiToolVersionSaveDTO saveDTO) {
        if (saveDTO == null || saveDTO.getToolId() == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiToolDO tool = requireTool(saveDTO.getToolId());
        AiToolPolicy.ToolType toolType = AiToolPolicy.ToolType.parse(saveDTO.getToolType());
        // 政策缺省即 DENY：不写政策的新版本默认不可执行
        AiToolPolicy policy = AiToolPolicy.parse(saveDTO.getPolicy());
        String sourceKind = StringUtils.hasText(saveDTO.getSourceKind())
                ? saveDTO.getSourceKind().trim().toUpperCase(java.util.Locale.ROOT)
                : AiToolVersionDO.SOURCE_HTTP_OPERATION;
        if (!AiToolVersionDO.SOURCE_HTTP_OPERATION.equals(sourceKind)) {
            // 首期只有一种来源；其他来源（如数据集查询）在后续卡片接入
            throw exception(AI_TOOL_TYPE_UNSUPPORTED);
        }
        String sourceRef = saveDTO.getSourceRef();
        if (!StringUtils.hasText(sourceRef) || sourceRef.length() > MAX_SOURCE_REF_LENGTH) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiToolInputSchema inputSchema = AiToolInputSchema.parse(saveDTO.getInputSchemaJson());
        String outputSchema = saveDTO.getOutputSchemaJson();
        if (!StringUtils.hasText(outputSchema) || outputSchema.length() > MAX_OUTPUT_SCHEMA_LENGTH) {
            throw exception(AI_REQUEST_INVALID);
        }
        requireJsonObject(outputSchema);
        AiToolVersionDO latest = versionMapper.selectLatest(tool.getId());
        int versionNo = (latest == null ? 0 : latest.getVersionNo()) + 1;
        AiToolVersionDO version = new AiToolVersionDO()
                .setToolId(tool.getId())
                .setVersionNo(versionNo)
                .setStatus(AiToolVersionDO.STATUS_DRAFT)
                .setToolType(toolType.name())
                .setPolicy(policy.name())
                .setSourceKind(sourceKind)
                .setSourceRef(sourceRef.trim())
                .setInputSchemaJson(inputSchema.canonicalJson())
                .setOutputSchemaJson(outputSchema)
                .setSchemaHash(hash(toolType, policy, sourceKind, sourceRef.trim(), inputSchema, outputSchema))
                .setVersion(0);
        versionMapper.insert(version);
        if (toolMapper.updateWithVersion(
                        new AiToolDO()
                                .setId(tool.getId())
                                .setLatestVersionNo(versionNo)
                                .setVersion(tool.getVersion() + 1),
                        tool.getVersion())
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        return version.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publishVersion(Long versionId, Integer version) {
        if (versionId == null || version == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiToolVersionDO existing = requireVersion(versionId);
        if (AiToolVersionDO.STATUS_PUBLISHED.equals(existing.getStatus())) {
            throw exception(AI_STATE_CONFLICT);
        }
        if (!AiToolPolicy.ToolType.READ.name().equals(existing.getToolType())) {
            // 首期只发布读工具：写操作能力先不开放
            throw exception(AI_TOOL_TYPE_UNSUPPORTED);
        }
        AiToolDO tool = requireTool(existing.getToolId());
        // 来源必须已发布：工具执行时不应才发现"来源还是草稿"
        AiConnectorOperationDO operation = operationMapper.selectByKey(tool.getConnectorId(), existing.getSourceRef());
        if (operation == null) {
            throw exception(AI_CONNECTOR_OPERATION_NOT_FOUND);
        }
        if (!AiConnectorOperationDO.STATUS_PUBLISHED.equals(operation.getStatus())) {
            throw exception(AI_CONNECTOR_OPERATION_NOT_PUBLISHED);
        }
        if (versionMapper.updateWithVersion(
                        new AiToolVersionDO()
                                .setId(existing.getId())
                                .setStatus(AiToolVersionDO.STATUS_PUBLISHED)
                                .setPublishedAt(LocalDateTime.now())
                                .setVersion(existing.getVersion() + 1),
                        version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    public AiToolVersionDO getVersion(Long versionId) {
        return requireVersion(versionId);
    }

    @Override
    public PageResult<AiToolVersionDO> getVersionPage(Long toolId, PageParam pageParam) {
        requirePageParam(pageParam);
        if (toolId != null) {
            requireTool(toolId);
        }
        return versionMapper.selectPage(pageParam, toolId, null);
    }

    /** 分页参数必填：缺失分页参数属于调用方错误，不静默返回全量。 */
    private static void requirePageParam(PageParam pageParam) {
        if (pageParam == null) {
            throw exception(AI_REQUEST_INVALID);
        }
    }

    @Override
    public AiToolVersionDO requirePublishedVersion(String toolCode) {
        AiToolDO tool = toolCode == null ? null : toolMapper.selectByCode(toolCode);
        if (tool == null || !AiToolDO.STATUS_ENABLED.equals(tool.getStatus())) {
            // 停用与不存在对外不可区分：不泄漏"这个工具存在但被停用了"
            throw exception(AI_TOOL_NOT_FOUND);
        }
        AiToolVersionDO published = versionMapper.selectByTool(tool.getId()).stream()
                .filter(candidate -> AiToolVersionDO.STATUS_PUBLISHED.equals(candidate.getStatus()))
                .findFirst()
                .orElse(null);
        if (published == null) {
            throw exception(AI_TOOL_VERSION_NOT_PUBLISHED);
        }
        return published;
    }

    private static void requireSaveFields(AiToolSaveDTO saveDTO, boolean creating) {
        if (saveDTO == null || !StringUtils.hasText(saveDTO.getName())) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (saveDTO.getName().length() > MAX_NAME_LENGTH) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (saveDTO.getDescription() != null && saveDTO.getDescription().length() > MAX_DESCRIPTION_LENGTH) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (creating) {
            if (!StringUtils.hasText(saveDTO.getCode())
                    || !CODE_PATTERN.matcher(saveDTO.getCode()).matches()
                    || saveDTO.getConnectorId() == null) {
                throw exception(AI_REQUEST_INVALID);
            }
            return;
        }
        if (saveDTO.getId() == null || saveDTO.getVersion() == null) {
            throw exception(AI_REQUEST_INVALID);
        }
    }

    private void requireConnector(Long connectorId) {
        AiConnectorDO connector = connectorMapper.selectById(connectorId);
        if (connector == null) {
            throw exception(AI_CONNECTOR_NOT_FOUND);
        }
    }

    private AiToolDO requireTool(Long id) {
        AiToolDO tool = id == null ? null : toolMapper.selectById(id);
        if (tool == null) {
            throw exception(AI_TOOL_NOT_FOUND);
        }
        return tool;
    }

    private AiToolVersionDO requireVersion(Long versionId) {
        AiToolVersionDO version = versionId == null ? null : versionMapper.selectById(versionId);
        if (version == null) {
            throw exception(AI_TOOL_VERSION_NOT_FOUND);
        }
        return version;
    }

    private static void requireJsonObject(String json) {
        try {
            if (JsonUtils.parseObject(json, java.util.Map.class) == null) {
                throw exception(AI_REQUEST_INVALID);
            }
        } catch (IllegalArgumentException notAnObject) {
            throw exception(AI_REQUEST_INVALID);
        }
    }

    /** 版本内容哈希：政策 + 类型 + 来源 + 输入/输出 schema（政策变化必然改变哈希）。 */
    private static String hash(
            AiToolPolicy.ToolType toolType,
            AiToolPolicy policy,
            String sourceKind,
            String sourceRef,
            AiToolInputSchema inputSchema,
            String outputSchema) {
        String canonical = toolType.name() + "|" + policy.name() + "|" + sourceKind + "|" + sourceRef + "|"
                + inputSchema.canonicalJson() + "|" + outputSchema;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    /** 参数校验入口（执行判定用）：未声明参数/必填缺失/类型不符一律拒绝。 */
    public static java.util.Map<String, Object> validateArguments(
            AiToolVersionDO version, java.util.Map<String, Object> arguments) {
        AiToolInputSchema schema = AiToolInputSchema.parse(version.getInputSchemaJson());
        try {
            return schema.validateArguments(arguments);
        } catch (com.basicframework.framework.common.exception.ServiceException failure) {
            throw exception(AI_TOOL_ARGUMENT_INVALID);
        }
    }
}
