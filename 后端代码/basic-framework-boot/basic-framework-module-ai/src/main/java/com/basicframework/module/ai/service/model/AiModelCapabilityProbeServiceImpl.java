package com.basicframework.module.ai.service.model;

import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelPort;
import com.basicframework.framework.ai.core.model.ModelProbeKind;
import com.basicframework.framework.ai.core.model.ModelProbeResult;
import com.basicframework.module.ai.adapter.model.AiModelClientResolver;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelProbeDO;
import com.basicframework.module.ai.dal.mysql.model.AiModelProbeMapper;
import com.basicframework.module.ai.service.model.dto.AiModelCapabilityOverviewDTO;
import com.basicframework.module.ai.service.model.dto.AiModelProbeResultDTO;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 能力探测实现（M04）。
 *
 * <p>关键语义：
 * <ul>
 *   <li>端点解析失败（未配置凭据、目标不在允许清单、端点停用等）时，**每类探测都记为 FAILED**
 *       并带稳定的失败原因，而不是抛错或按"未启用"静默跳过，探测结论不会被启用状态掩盖；</li>
 *   <li>声明了但探测未确认的能力不进可发布范围；</li>
 *   <li>结论落库带 configRevision/credentialRevision，配置或凭据变化后旧结论可对比。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AiModelCapabilityProbeServiceImpl implements AiModelCapabilityProbeService {

    /** 探测顺序固定，便于结果稳定对比。 */
    private static final List<ModelProbeKind> PROBE_ORDER = List.of(
            ModelProbeKind.CONNECTIVITY,
            ModelProbeKind.TEXT,
            ModelProbeKind.TEXT_STREAM,
            ModelProbeKind.STRUCTURED_OUTPUT,
            ModelProbeKind.TOOL_CALLING,
            ModelProbeKind.EMBEDDING,
            // X02 起媒体探测项也进入探测流程：否则准入的第 3 条判据（探测已确认）对媒体能力不可达，
            // 声明了媒体能力的端点永远无法通过准入（X01 矩阵 §7 登记的前置缺口）。
            ModelProbeKind.IMAGE_UNDERSTANDING,
            ModelProbeKind.IMAGE_OCR,
            ModelProbeKind.IMAGE_GENERATION,
            ModelProbeKind.IMAGE_EDIT,
            ModelProbeKind.SPEECH_TO_TEXT,
            ModelProbeKind.TEXT_TO_SPEECH);

    /** 探测类型到能力词汇的映射；CONNECTIVITY 只证明可达，不对应可发布能力。 */
    private static final Map<ModelProbeKind, ModelCapability> KIND_CAPABILITIES = Map.ofEntries(
            Map.entry(ModelProbeKind.TEXT, ModelCapability.TEXT),
            Map.entry(ModelProbeKind.TEXT_STREAM, ModelCapability.TEXT_STREAM),
            Map.entry(ModelProbeKind.STRUCTURED_OUTPUT, ModelCapability.STRUCTURED_OUTPUT),
            Map.entry(ModelProbeKind.TOOL_CALLING, ModelCapability.TOOL_CALLING),
            Map.entry(ModelProbeKind.EMBEDDING, ModelCapability.EMBEDDING),
            Map.entry(ModelProbeKind.IMAGE_UNDERSTANDING, ModelCapability.IMAGE_UNDERSTANDING),
            Map.entry(ModelProbeKind.IMAGE_OCR, ModelCapability.IMAGE_OCR),
            Map.entry(ModelProbeKind.IMAGE_GENERATION, ModelCapability.IMAGE_GENERATION),
            Map.entry(ModelProbeKind.IMAGE_EDIT, ModelCapability.IMAGE_EDIT),
            Map.entry(ModelProbeKind.SPEECH_TO_TEXT, ModelCapability.SPEECH_TO_TEXT),
            Map.entry(ModelProbeKind.TEXT_TO_SPEECH, ModelCapability.TEXT_TO_SPEECH));

    private final AiModelEndpointService endpointService;

    private final AiModelClientResolver clientResolver;

    private final AiModelProbeMapper probeMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<AiModelProbeResultDTO> probeAll(Long endpointId) {
        AiModelEndpointDO endpoint = endpointService.getEndpoint(endpointId);
        ModelException.Reason resolveFailure = null;
        ModelPort port = null;
        try {
            port = clientResolver.resolveForProbe(endpointId);
        } catch (ModelException exception) {
            resolveFailure = exception.getReason();
        }
        List<AiModelProbeResultDTO> results = new ArrayList<>(PROBE_ORDER.size());
        for (ModelProbeKind kind : PROBE_ORDER) {
            ModelProbeResult result =
                    resolveFailure == null ? port.probe(kind) : ModelProbeResult.failed(kind, resolveFailure, 0L);
            results.add(persist(endpoint, result));
        }
        return results;
    }

    @Override
    public List<AiModelProbeResultDTO> getLatestResults(Long endpointId) {
        endpointService.getEndpoint(endpointId);
        return probeMapper.selectLatestPerKind(endpointId).stream()
                .map(AiModelCapabilityProbeServiceImpl::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public AiModelCapabilityOverviewDTO getCapabilityOverview(Long endpointId) {
        AiModelEndpointDO endpoint = endpointService.getEndpoint(endpointId);
        List<String> declared = declaredCapabilities(endpoint);
        Set<String> supported = getLatestResults(endpointId).stream()
                .filter(result -> ModelProbeResult.Status.SUPPORTED.name().equals(result.getStatus()))
                .map(result -> capabilityOf(result.getProbeKind()))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> publishable = declared.stream().filter(supported::contains).collect(Collectors.toList());
        return new AiModelCapabilityOverviewDTO()
                .setEndpointId(endpointId)
                .setDeclared(declared)
                .setSupported(new ArrayList<>(supported))
                .setPublishable(publishable);
    }

    private List<String> declaredCapabilities(AiModelEndpointDO endpoint) {
        List<AiModelEndpointRevisionDO> revisions = endpointService.getRevisions(endpoint.getId());
        if (revisions.isEmpty() || revisions.get(0).getCapabilities() == null) {
            return List.of();
        }
        return Arrays.stream(revisions.get(0).getCapabilities().split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toList());
    }

    private static String capabilityOf(String probeKind) {
        try {
            ModelCapability capability = KIND_CAPABILITIES.get(ModelProbeKind.valueOf(probeKind));
            return capability == null ? null : capability.name();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private AiModelProbeResultDTO persist(AiModelEndpointDO endpoint, ModelProbeResult result) {
        AiModelProbeDO record = new AiModelProbeDO()
                .setEndpointId(endpoint.getId())
                .setConfigRevision(endpoint.getConfigRevision())
                .setCredentialRevision(endpoint.getCredentialRevision())
                .setProbeKind(result.kind().name())
                .setStatus(result.status().name())
                .setDetailCode(result.detailCode())
                .setEmbeddingDimension(result.embeddingDimension())
                .setLatencyMs((int) Math.min(result.latencyMillis(), Integer.MAX_VALUE));
        probeMapper.insert(record);
        return toDTO(record);
    }

    private static AiModelProbeResultDTO toDTO(AiModelProbeDO record) {
        return new AiModelProbeResultDTO()
                .setProbeKind(record.getProbeKind())
                .setStatus(record.getStatus())
                .setDetailCode(record.getDetailCode())
                .setEmbeddingDimension(record.getEmbeddingDimension())
                .setLatencyMs(record.getLatencyMs())
                .setConfigRevision(record.getConfigRevision());
    }
}
