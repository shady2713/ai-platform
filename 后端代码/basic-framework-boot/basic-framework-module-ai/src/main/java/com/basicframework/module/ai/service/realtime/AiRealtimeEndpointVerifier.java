package com.basicframework.module.ai.service.realtime;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_ADAPTER_UNAVAILABLE_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_AUDIO_FORMAT_UNSUPPORTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_ENDPOINT_CONFIG_CHANGED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_PROTOCOL_UNVERIFIED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;

import com.basicframework.framework.ai.core.model.ModelEndpointSnapshot;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.realtime.RealtimeAdapter;
import com.basicframework.framework.ai.core.realtime.RealtimeAudioFormat;
import com.basicframework.framework.ai.core.realtime.RealtimeCapability;
import com.basicframework.framework.ai.core.realtime.RealtimeCapabilityReport;
import com.basicframework.framework.ai.core.realtime.RealtimeProbeRequest;
import com.basicframework.framework.ai.core.realtime.RealtimeProtocol;
import com.basicframework.framework.ai.provider.realtime.RealtimeProtocolVerification;
import com.basicframework.framework.security.core.crypto.CredentialCipher;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeEndpointCapabilityDO;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeSessionDO;
import com.basicframework.module.ai.dal.mysql.realtime.AiRealtimeEndpointCapabilityMapper;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 实时端点能力验证器（X05，ADR 0052）：把"这个端点的这个协议真的能用"变成可复核的事实。
 *
 * <p>判定顺序（顺序即安全语义，任何一步失败都不进入下一步）：
 * <ol>
 *   <li>端点存在且启用（停用/不存在按端点错误码拒绝）；</li>
 *   <li>协议有**已注册适配器**：没有适配器就是"平台不知道的能力"，拒绝（不回退其它协议）；</li>
 *   <li>请求的音频格式在适配器支持集合内（否则 400，不发探测）；</li>
 *   <li>（端点, 配置版本, 协议）的验证结论：没有结论或结论已过冷却期 → 用适配器发起一次**真实探测**
 *       并按 {@link RealtimeProtocolVerification} 收窄后落台账；结论是 VERIFIED 且请求格式在
 *       已验证格式集合内才放行，其余一律 {@code AI_REALTIME_PROTOCOL_UNVERIFIED}。</li>
 * </ol>
 *
 * <p>为什么"未验证"必须是默认：真实供应商的实时链路在本环境没有凭据与出网通道，
 * 任何"按供应商名推断可用"的实现都会把未验证能力发布给业务。因此台账是唯一依据，
 * 探测是唯一来源，失败结论在冷却窗口内不重复外发（避免受理变成探测风暴）。
 */
@Component
@RequiredArgsConstructor
public class AiRealtimeEndpointVerifier {

    /** 凭据解密的 AAD 上下文，必须与 {@code AiModelClientResolver} 写入时一致。 */
    private static final String CREDENTIAL_CONTEXT_PREFIX = "ai_model_endpoint:";

    private final AiModelEndpointService endpointService;

    private final AiRealtimeAdapterRegistry adapters;

    private final AiRealtimeEndpointCapabilityMapper capabilityMapper;

    private final CredentialCipher credentialCipher;

    private final AiRealtimeParams params;

    /** 验证一次（受理路径的唯一入口）。 */
    public AiRealtimeVerifiedEndpoint verify(
            Long endpointId, RealtimeProtocol protocol, RealtimeAudioFormat audioFormat) {
        AiModelEndpointDO endpoint = endpointService.getEnabledEndpoint(endpointId);
        RealtimeAdapter adapter =
                adapters.find(protocol).orElseThrow(() -> exception(AI_REALTIME_ADAPTER_UNAVAILABLE_CONFLICT));
        if (!adapter.supportedAudioFormats().contains(audioFormat)) {
            throw exception(AI_REALTIME_AUDIO_FORMAT_UNSUPPORTED);
        }
        AiRealtimeEndpointCapabilityDO verified = requireVerified(endpoint, protocol, audioFormat, adapter);
        if (!audioFormats(verified).contains(audioFormat)) {
            // 台账里验证通过的格式集合不含本次请求的格式：按格式不支持拒绝（不放行"差不多能用"的格式）
            throw exception(AI_REALTIME_AUDIO_FORMAT_UNSUPPORTED);
        }
        return new AiRealtimeVerifiedEndpoint(
                endpoint.getId(),
                endpoint.getConfigRevision(),
                endpoint.getCredentialRevision(),
                currentModelRef(endpoint.getId()),
                protocol,
                audioFormat,
                snapshot(endpoint),
                adapter);
    }

    /**
     * 重连路径的验证（X05）：只接受**受理时固定的**配置与凭据版本。
     *
     * <p>配置/凭据版本已变化时拒绝续接（{@code AI_REALTIME_ENDPOINT_CONFIG_CHANGED_CONFLICT}）：
     * 用新配置悄悄续接一条旧会话会改变已受理语义（模型/地址/凭据都可能不同），
     * 客户端应结束旧会话并重新受理。
     */
    public AiRealtimeVerifiedEndpoint verifyPinned(AiRealtimeSessionDO session) {
        RealtimeProtocol protocol =
                RealtimeProtocol.parse(session.getProtocol()).orElseThrow(() -> exception(AI_STATE_CONFLICT));
        RealtimeAudioFormat audioFormat =
                RealtimeAudioFormat.parse(session.getAudioFormat()).orElseThrow(() -> exception(AI_STATE_CONFLICT));
        AiModelEndpointDO endpoint = endpointService.getEnabledEndpoint(session.getEndpointId());
        if (!Objects.equals(endpoint.getConfigRevision(), session.getEndpointConfigRevision())
                || !Objects.equals(endpoint.getCredentialRevision(), session.getEndpointCredentialRevision())) {
            throw exception(AI_REALTIME_ENDPOINT_CONFIG_CHANGED_CONFLICT);
        }
        RealtimeAdapter adapter =
                adapters.find(protocol).orElseThrow(() -> exception(AI_REALTIME_ADAPTER_UNAVAILABLE_CONFLICT));
        AiRealtimeEndpointCapabilityDO verified = requireVerified(endpoint, protocol, audioFormat, adapter);
        if (!audioFormats(verified).contains(audioFormat)) {
            throw exception(AI_REALTIME_AUDIO_FORMAT_UNSUPPORTED);
        }
        return new AiRealtimeVerifiedEndpoint(
                endpoint.getId(),
                endpoint.getConfigRevision(),
                endpoint.getCredentialRevision(),
                currentModelRef(endpoint.getId()),
                protocol,
                audioFormat,
                snapshot(endpoint),
                adapter);
    }

    /**
     * 取当前配置版本的验证结论：不存在或需要重探（未确认且过了冷却窗口）时执行一次真实探测。
     *
     * <p>探测结果一律落台账（含失败），因此"未验证"是可复核的事实，而不是内存里的临时判断。
     */
    private AiRealtimeEndpointCapabilityDO requireVerified(
            AiModelEndpointDO endpoint,
            RealtimeProtocol protocol,
            RealtimeAudioFormat audioFormat,
            RealtimeAdapter adapter) {
        AiRealtimeEndpointCapabilityDO existing =
                capabilityMapper.selectOneByVersion(endpoint.getId(), endpoint.getConfigRevision(), protocol.name());
        if (existing != null && !needsReprobe(existing, LocalDateTime.now())) {
            return requireConfirmed(existing);
        }
        RealtimeCapabilityReport narrowed = probe(endpoint, protocol, audioFormat, adapter);
        capabilityMapper.upsert(
                endpoint.getId(),
                endpoint.getConfigRevision(),
                protocol.name(),
                narrowed.status().name(),
                narrowed.detailCode(),
                names(narrowed.declared()),
                names(narrowed.confirmed()),
                adapter.supportedAudioFormats().stream()
                        .map(RealtimeAudioFormat::canonicalForm)
                        .sorted()
                        .collect(Collectors.joining(",")),
                narrowed.latencyMillis(),
                LocalDateTime.now(),
                "ai-realtime-probe");
        AiRealtimeEndpointCapabilityDO persisted =
                capabilityMapper.selectOneByVersion(endpoint.getId(), endpoint.getConfigRevision(), protocol.name());
        return requireConfirmed(persisted == null ? toLedgerView(endpoint, protocol, narrowed) : persisted);
    }

    /**
     * 只有 VERIFIED 才是可发布结论：失败/不支持一律拒绝（稳定明细码留在台账，不回传上游报文）。
     *
     * <p>为什么校验放在这里而不是调用点：受理与重连两条路径都必须过同一道闸门，
     * 把"状态必须是 VERIFIED"写进唯一的结论出口，任何新增调用点都不可能漏掉。
     */
    private AiRealtimeEndpointCapabilityDO requireConfirmed(AiRealtimeEndpointCapabilityDO conclusion) {
        if (!AiRealtimeEndpointCapabilityDO.STATUS_VERIFIED.equals(conclusion.getStatus())) {
            throw exception(AI_REALTIME_PROTOCOL_UNVERIFIED_CONFLICT);
        }
        return conclusion;
    }

    /** 是否需要重探：确认过的结论直接用；未确认的结论在冷却窗口内不重复外发。 */
    private boolean needsReprobe(AiRealtimeEndpointCapabilityDO existing, LocalDateTime now) {
        if (AiRealtimeEndpointCapabilityDO.STATUS_VERIFIED.equals(existing.getStatus())) {
            return false;
        }
        if (existing.getProbedTime() == null) {
            return true;
        }
        return existing.getProbedTime()
                .plusSeconds(params.PROBE_COOLDOWN_SECONDS)
                .isBefore(now);
    }

    /** 真实探测：适配器返回原始结论，平台按协议必需能力与格式范围收窄。 */
    private RealtimeCapabilityReport probe(
            AiModelEndpointDO endpoint,
            RealtimeProtocol protocol,
            RealtimeAudioFormat audioFormat,
            RealtimeAdapter adapter) {
        ModelEndpointSnapshot snapshot = snapshot(endpoint);
        long startedAt = System.nanoTime();
        RealtimeCapabilityReport raw;
        try {
            raw = adapter.probe(new RealtimeProbeRequest(snapshot, protocol, audioFormat));
        } catch (ModelException failure) {
            // 预期内失败（上游拒绝/不可达）：结论落到台账，不把上游报文带给调用方
            raw = RealtimeCapabilityReport.failed(
                    protocol,
                    endpoint.getConfigRevision(),
                    Set.of(),
                    failure.getReason().name(),
                    elapsed(startedAt));
        } catch (RuntimeException failure) {
            raw = RealtimeCapabilityReport.failed(
                    protocol,
                    endpoint.getConfigRevision(),
                    Set.of(),
                    failure.getClass().getSimpleName(),
                    elapsed(startedAt));
        }
        return RealtimeProtocolVerification.verify(
                protocol, endpoint.getConfigRevision(), raw, adapter.supportedAudioFormats(), audioFormat);
    }

    /** 探测未落库成功时的只读视图（不应发生：落库失败会让事务回滚并拒绝受理）。 */
    private AiRealtimeEndpointCapabilityDO toLedgerView(
            AiModelEndpointDO endpoint, RealtimeProtocol protocol, RealtimeCapabilityReport report) {
        AiRealtimeEndpointCapabilityDO view = new AiRealtimeEndpointCapabilityDO();
        view.setEndpointId(endpoint.getId());
        view.setConfigRevision(endpoint.getConfigRevision());
        view.setProtocol(protocol.name());
        view.setStatus(report.status().name());
        view.setDetailCode(report.detailCode());
        view.setConfirmedCapabilities(names(report.confirmed()));
        view.setAudioFormats("");
        view.setLatencyMillis(report.latencyMillis());
        view.setProbedTime(LocalDateTime.now());
        return view;
    }

    /** 台账里验证通过的格式集合。 */
    private Set<RealtimeAudioFormat> audioFormats(AiRealtimeEndpointCapabilityDO ledger) {
        if (ledger == null
                || ledger.getAudioFormats() == null
                || ledger.getAudioFormats().isBlank()) {
            return Set.of();
        }
        return Arrays.stream(ledger.getAudioFormats().split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(RealtimeAudioFormat::parse)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 能力名列表 → 列值（逗号分隔，稳定顺序）。 */
    private static String names(Set<RealtimeCapability> capabilities) {
        return capabilities == null
                ? ""
                : capabilities.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    private String currentModelRef(Long endpointId) {
        String modelId = endpointService.getRevisions(endpointId).stream()
                .findFirst()
                .map(AiModelEndpointRevisionDO::getModelId)
                .orElseThrow(() -> new ModelException(ModelException.Reason.ENDPOINT_NOT_FOUND, "端点缺少配置版本"));
        return modelId == null ? "" : modelId.trim();
    }

    /**
     * 端点快照（含解密凭据）。
     *
     * <p>为什么在本类里组装而不是复用 {@code AiModelClientResolver}：解析器只暴露 {@code ModelPort}，
     * 实时适配器需要的端点事实只能以冻结的 {@link ModelEndpointSnapshot} 形状传递；解密上下文
     * 与解析器保持一致（{@code ai_model_endpoint:<id>}），凭据只进受控内存（快照 toString 已脱敏）。
     */
    private ModelEndpointSnapshot snapshot(AiModelEndpointDO endpoint) {
        AiModelEndpointRevisionDO revision = endpointService.getRevisions(endpoint.getId()).stream()
                .findFirst()
                .orElseThrow(() -> new ModelException(ModelException.Reason.ENDPOINT_NOT_FOUND, "端点缺少配置版本"));
        if (endpoint.getCredentialRevision() == null
                || endpoint.getCredentialRevision() <= 0
                || endpoint.getCredentialCiphertext() == null) {
            throw new ModelException(ModelException.Reason.CREDENTIAL_UNAVAILABLE, "端点未配置凭据");
        }
        String apiKey = credentialCipher.decrypt(
                endpoint.getCredentialCiphertext(), CREDENTIAL_CONTEXT_PREFIX + endpoint.getId());
        return new ModelEndpointSnapshot(
                endpoint.getId(),
                endpoint.getConfigRevision(),
                endpoint.getCredentialRevision(),
                endpoint.getProvider(),
                endpoint.getBaseUrl(),
                revision.getModelId(),
                Set.of(),
                apiKey);
    }

    private static long elapsed(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}
