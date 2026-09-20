package com.basicframework.module.ai.service.dataset;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_CONFIG_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_CODE_DUPLICATE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_DEFINITION_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_DISABLED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_REFERENCED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_SOURCE_NOT_AUTHORIZED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_VERSION_DRIFTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_VERSION_IMMUTABLE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_VERSION_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_VERSION_NOT_VERIFIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlObjectMetadata;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetDO;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetVersionDO;
import com.basicframework.module.ai.dal.mysql.connector.AiConnectorMapper;
import com.basicframework.module.ai.dal.mysql.dataset.AiDatasetMapper;
import com.basicframework.module.ai.dal.mysql.dataset.AiDatasetVersionMapper;
import com.basicframework.module.ai.domain.semantic.AiDatasetDefinition;
import com.basicframework.module.ai.domain.semantic.AiDatasetDriftReport;
import com.basicframework.module.ai.domain.semantic.AiSemanticTypes;
import com.basicframework.module.ai.service.connector.AiConnectorConfig;
import com.basicframework.module.ai.service.connector.AiMysqlConnectorService;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetSaveDTO;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetVersionSaveDTO;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetVersionVerifyResultDTO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 语义数据集与版本实现（D04）。
 *
 * <p>三条不变式：
 * <ol>
 *   <li><b>来源可读性由连接器保证</b>：来源对象必须在连接器授权白名单内，数据集不另建一套可读判断；</li>
 *   <li><b>版本是不可变快照</b>：发布后定义与哈希不得修改，只能新建版本；漂移只更新验证结论；</li>
 *   <li><b>发布必须"当下可执行"</b>：不仅要已验证，还要在发布时确认上游结构自验证以来未再变化
 *       （否则置 DRIFTED 并拒绝），避免"验证通过但发布的是已失效的定义"。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AiDatasetServiceImpl implements AiDatasetService {

    /** 数据集标识：字母开头，字母数字与连字符/下划线，长度 3..64。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{2,63}$");

    /** 来源对象：{@code schema.object}。 */
    private static final Pattern SOURCE_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,64}\\.[A-Za-z0-9_]{1,64}$");

    private static final int MAX_NAME_LENGTH = 128;

    private static final int MAX_DESCRIPTION_LENGTH = 512;

    private final AiDatasetMapper datasetMapper;

    private final AiDatasetVersionMapper versionMapper;

    private final AiConnectorMapper connectorMapper;

    /** 只读连接器（D03）：授权元数据发现的唯一来源。 */
    private final AiMysqlConnectorService mysqlConnectorService;

    /** 版本引用检查：报表（R04）等消费方在各自卡片注册实现。 */
    private final List<AiDatasetVersionReferenceChecker> referenceCheckers;

    /** 漂移标记写入器：发布失败也要留下"已漂移"结论（独立事务）。 */
    private final AiDatasetVersionDriftWriter driftWriter;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(AiDatasetSaveDTO saveDTO) {
        requireSaveFields(saveDTO, true);
        AiConnectorDO connector = requireMysqlConnector(saveDTO.getConnectorId());
        String sourceObject = normalizeSource(saveDTO.getSourceObject());
        AiConnectorConfig config = AiConnectorConfig.parse(connector.getConnectorType(), connector.getConfigJson());
        if (!config.allowedObjects().contains(sourceObject)) {
            // 数据集只能声明"连接器已经授权"的对象，否则等于绕过 D03 的白名单
            throw exception(AI_DATASET_SOURCE_NOT_AUTHORIZED);
        }
        if (datasetMapper.selectByCode(saveDTO.getCode()) != null) {
            throw exception(AI_DATASET_CODE_DUPLICATE, saveDTO.getCode());
        }
        AiDatasetDO dataset = new AiDatasetDO()
                .setCode(saveDTO.getCode())
                .setName(saveDTO.getName())
                .setDescription(saveDTO.getDescription() == null ? "" : saveDTO.getDescription())
                .setConnectorId(connector.getId())
                .setSourceObject(sourceObject)
                .setStatus(AiDatasetDO.STATUS_ENABLED)
                .setLatestVersionNo(0)
                .setPublishedVersionNo(0)
                .setVersion(0);
        datasetMapper.insert(dataset);
        return dataset.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(AiDatasetSaveDTO saveDTO) {
        requireSaveFields(saveDTO, false);
        AiDatasetDO existing = requireDataset(saveDTO.getId());
        if (datasetMapper.updateWithVersion(
                        new AiDatasetDO()
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
        requireVersion(version);
        if (enabled == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiDatasetDO existing = requireDataset(id);
        if (datasetMapper.updateWithVersion(
                        new AiDatasetDO()
                                .setId(id)
                                .setStatus(enabled ? AiDatasetDO.STATUS_ENABLED : AiDatasetDO.STATUS_DISABLED)
                                .setVersion(existing.getVersion() + 1),
                        version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Integer version) {
        requireVersion(version);
        AiDatasetDO existing = requireDataset(id);
        // 版本引用保护：任一版本被报表等引用即拒绝删除（版本行保留，历史引用可追溯）
        for (AiDatasetVersionDO datasetVersion : versionMapper.selectByDataset(id)) {
            Optional<String> reference = referenceCheckers.stream()
                    .map(checker -> checker.findReference(datasetVersion.getId()))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .findFirst();
            if (reference.isPresent()) {
                throw exception(AI_DATASET_REFERENCED);
            }
        }
        if (datasetMapper.updateWithVersion(new AiDatasetDO().setId(id).setVersion(existing.getVersion() + 1), version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        datasetMapper.deleteById(id);
    }

    @Override
    public AiDatasetDO getDataset(Long id) {
        return requireDataset(id);
    }

    @Override
    public PageResult<AiDatasetDO> getDatasetPage(PageParam pageParam, Long connectorId, String status) {
        return datasetMapper.selectPage(pageParam, connectorId, status);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createVersion(AiDatasetVersionSaveDTO saveDTO) {
        if (saveDTO == null || saveDTO.getDatasetId() == null || !StringUtils.hasText(saveDTO.getDefinitionJson())) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiDatasetDO dataset = requireEnabledDataset(saveDTO.getDatasetId());
        // 定义校验与规范化：不合规的定义不会进库（未知键/越界/别名歧义/缺权限策略都在这里拒绝）
        AiDatasetDefinition definition = AiDatasetDefinition.parse(saveDTO.getDefinitionJson());
        AiDatasetVersionDO latest = versionMapper.selectLatest(dataset.getId());
        int versionNo = (latest == null ? 0 : latest.getVersionNo()) + 1;
        AiDatasetVersionDO version = new AiDatasetVersionDO()
                .setDatasetId(dataset.getId())
                .setVersionNo(versionNo)
                .setStatus(AiDatasetVersionDO.STATUS_DRAFT)
                .setDefinitionJson(definition.canonicalJson())
                .setSchemaHash(definition.schemaHash())
                .setVerificationStatus(AiDatasetVersionDO.VERIFICATION_UNVERIFIED)
                .setVersion(0);
        versionMapper.insert(version);
        if (datasetMapper.updateWithVersion(
                        new AiDatasetDO()
                                .setId(dataset.getId())
                                .setLatestVersionNo(versionNo)
                                .setVersion(dataset.getVersion() + 1),
                        dataset.getVersion())
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        return version.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiDatasetVersionVerifyResultDTO verifyVersion(Long versionId, Integer version) {
        requireVersion(version);
        AiDatasetVersionDO existing = requireVersionRow(versionId);
        AiDatasetDO dataset = requireEnabledDataset(existing.getDatasetId());
        if (AiDatasetVersionDO.STATUS_PUBLISHED.equals(existing.getStatus())) {
            throw exception(AI_DATASET_VERSION_IMMUTABLE);
        }
        AiDatasetDefinition definition = AiDatasetDefinition.parse(existing.getDefinitionJson());
        AiDatasetDriftReport report = driftReport(dataset, definition);
        boolean publishable = report.isPublishable();
        if (versionMapper.updateWithVersion(
                        new AiDatasetVersionDO()
                                .setId(existing.getId())
                                .setVerificationStatus(
                                        publishable
                                                ? AiDatasetVersionDO.VERIFICATION_VERIFIED
                                                : AiDatasetVersionDO.VERIFICATION_DRIFTED)
                                .setSourceSchemaHash(report.upstreamHash())
                                .setDriftJson(publishable && !report.hasDrift() ? null : report.summary())
                                .setVerifiedAt(LocalDateTime.now())
                                .setVersion(existing.getVersion() + 1),
                        version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        return toResult(existing, publishable, report);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiDatasetVersionVerifyResultDTO publishVersion(Long versionId, Integer version) {
        requireVersion(version);
        AiDatasetVersionDO existing = requireVersionRow(versionId);
        AiDatasetDO dataset = requireEnabledDataset(existing.getDatasetId());
        if (AiDatasetVersionDO.STATUS_PUBLISHED.equals(existing.getStatus())) {
            throw exception(AI_DATASET_VERSION_IMMUTABLE);
        }
        if (!AiDatasetVersionDO.VERIFICATION_VERIFIED.equals(existing.getVerificationStatus())) {
            throw exception(AI_DATASET_VERSION_NOT_VERIFIED);
        }
        // 发布前重新确认"可执行范围"：定义仍合规（权限策略/别名）且上游结构自验证以来未变化
        AiDatasetDefinition definition = AiDatasetDefinition.parse(existing.getDefinitionJson());
        AiDatasetDriftReport report = driftReport(dataset, definition);
        if (!report.isPublishable() || !report.upstreamHash().equals(existing.getSourceSchemaHash())) {
            // 独立事务落"已漂移"，本方法随后抛错回滚，但漂移事实必须留下
            driftWriter.markDrifted(existing.getId(), version, report.summary());
            throw exception(AI_DATASET_VERSION_DRIFTED);
        }
        if (versionMapper.updateWithVersion(
                        new AiDatasetVersionDO()
                                .setId(existing.getId())
                                .setStatus(AiDatasetVersionDO.STATUS_PUBLISHED)
                                .setPublishedAt(LocalDateTime.now())
                                .setVersion(existing.getVersion() + 1),
                        version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        if (datasetMapper.updateWithVersion(
                        new AiDatasetDO()
                                .setId(dataset.getId())
                                .setPublishedVersionNo(existing.getVersionNo())
                                .setVersion(dataset.getVersion() + 1),
                        dataset.getVersion())
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        return toResult(existing, true, report).setStatus(AiDatasetVersionDO.STATUS_PUBLISHED);
    }

    @Override
    public AiDatasetVersionDO getVersion(Long versionId) {
        return requireVersionRow(versionId);
    }

    @Override
    public PageResult<AiDatasetVersionDO> getVersionPage(Long datasetId, PageParam pageParam) {
        if (datasetId != null) {
            requireDataset(datasetId);
        }
        return versionMapper.selectPage(pageParam, datasetId, null);
    }

    /** 定义与上游结构的差异（上游对象缺失时，定义引用的列全部视为缺失）。 */
    private AiDatasetDriftReport driftReport(AiDatasetDO dataset, AiDatasetDefinition definition) {
        Map<String, String> upstream = new LinkedHashMap<>();
        String upstreamHash = hashOfColumns(upstream);
        List<AiMysqlObjectMetadata> objects = mysqlConnectorService.discoverObjects(dataset.getConnectorId());
        AiMysqlObjectMetadata source = objects.stream()
                .filter(object -> object.qualifiedName().equals(dataset.getSourceObject()))
                .findFirst()
                .orElse(null);
        if (source != null) {
            source.columns()
                    .forEach(column -> upstream.put(
                            column.name().toLowerCase(Locale.ROOT),
                            column.dataType() == null ? "" : column.dataType().toLowerCase(Locale.ROOT)));
            upstreamHash = hashOfColumns(upstream);
        }
        List<String> missing = new ArrayList<>();
        List<String> typeChanged = new ArrayList<>();
        for (AiDatasetDefinition.Field field : definition.fields()) {
            String column = field.sourceColumn().toLowerCase(Locale.ROOT);
            String upstreamType = upstream.get(column);
            if (upstreamType == null) {
                missing.add(field.sourceColumn());
            } else if (!AiSemanticTypes.isCompatible(field.type(), upstreamType)) {
                typeChanged.add(field.sourceColumn());
            }
        }
        List<String> declared = definition.sourceColumns().stream()
                .map(column -> column.toLowerCase(Locale.ROOT))
                .toList();
        List<String> added = upstream.keySet().stream()
                .filter(column -> !declared.contains(column))
                .toList();
        return new AiDatasetDriftReport(missing, typeChanged, added, definition.schemaHash(), upstreamHash);
    }

    /** 上游结构哈希：列名与类型排序拼接后的 sha256（与定义哈希无关）。 */
    private static String hashOfColumns(Map<String, String> columns) {
        List<String> entries = columns.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .sorted()
                .toList();
        return sha256(String.join(",", entries));
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    private static AiDatasetVersionVerifyResultDTO toResult(
            AiDatasetVersionDO version, boolean publishable, AiDatasetDriftReport report) {
        return new AiDatasetVersionVerifyResultDTO()
                .setVersionId(version.getId())
                .setVersionNo(version.getVersionNo())
                .setStatus(version.getStatus())
                .setVerificationStatus(
                        publishable
                                ? AiDatasetVersionDO.VERIFICATION_VERIFIED
                                : AiDatasetVersionDO.VERIFICATION_DRIFTED)
                .setMissingColumns(report.missingColumns())
                .setTypeChangedColumns(report.typeChangedColumns())
                .setAddedColumns(report.addedColumns())
                .setSchemaHash(report.declaredHash())
                .setSourceSchemaHash(report.upstreamHash())
                .setPublishable(publishable);
    }

    private static String normalizeSource(String sourceObject) {
        if (!StringUtils.hasText(sourceObject)
                || !SOURCE_PATTERN.matcher(sourceObject).matches()) {
            throw exception(AI_DATASET_DEFINITION_INVALID);
        }
        return sourceObject.toLowerCase(Locale.ROOT);
    }

    private static void requireSaveFields(AiDatasetSaveDTO saveDTO, boolean creating) {
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
                    || saveDTO.getConnectorId() == null
                    || !StringUtils.hasText(saveDTO.getSourceObject())) {
                throw exception(AI_REQUEST_INVALID);
            }
            return;
        }
        if (saveDTO.getId() == null || saveDTO.getVersion() == null) {
            throw exception(AI_REQUEST_INVALID);
        }
    }

    private static void requireVersion(Integer version) {
        if (version == null) {
            throw exception(AI_REQUEST_INVALID);
        }
    }

    private AiConnectorDO requireMysqlConnector(Long connectorId) {
        AiConnectorDO connector = connectorMapper.selectById(connectorId);
        if (connector == null) {
            throw exception(AI_CONNECTOR_NOT_FOUND);
        }
        if (!AiConnectorDO.TYPE_MYSQL.equals(connector.getConnectorType())) {
            // 数据集只声明 MySQL 只读对象；HTTP 接口来源（D02/D07）在 D07 落地时扩展来源类型
            throw exception(AI_CONNECTOR_CONFIG_INVALID);
        }
        return connector;
    }

    private AiDatasetDO requireDataset(Long id) {
        AiDatasetDO dataset = id == null ? null : datasetMapper.selectById(id);
        if (dataset == null) {
            throw exception(AI_DATASET_NOT_FOUND);
        }
        return dataset;
    }

    private AiDatasetDO requireEnabledDataset(Long id) {
        AiDatasetDO dataset = requireDataset(id);
        if (!AiDatasetDO.STATUS_ENABLED.equals(dataset.getStatus())) {
            throw exception(AI_DATASET_DISABLED);
        }
        return dataset;
    }

    private AiDatasetVersionDO requireVersionRow(Long versionId) {
        AiDatasetVersionDO version = versionId == null ? null : versionMapper.selectById(versionId);
        if (version == null) {
            throw exception(AI_DATASET_VERSION_NOT_FOUND);
        }
        return version;
    }
}
