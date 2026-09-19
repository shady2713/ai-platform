package com.basicframework.module.ai.service.serviceconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseEvaluationDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceResourceDO;
import com.basicframework.module.ai.dal.mysql.serviceconfig.AiServiceMapper;
import com.basicframework.module.ai.dal.mysql.serviceconfig.AiServiceReleaseEvaluationMapper;
import com.basicframework.module.ai.dal.mysql.serviceconfig.AiServiceReleaseMapper;
import com.basicframework.module.ai.dal.mysql.serviceconfig.AiServiceResourceMapper;
import com.basicframework.module.ai.domain.runtime.AiRunSnapshot;
import com.basicframework.module.ai.domain.serviceconfig.AiServiceContentHash;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.authorization.dto.AiAuthorizationDecisionDTO;
import com.basicframework.module.ai.service.model.AiModelCapabilityProbeService;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.dto.AiModelCapabilityOverviewDTO;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceCapabilityDTO;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceEvaluationSaveDTO;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceRunSnapshotDTO;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** S02 发布预检查与不可变版本：候选冻结、评测绑定、别名切换与运行解析。 */
class AiServiceReleaseServiceImplTest {

    private static final Long SERVICE_ID = 9L;

    private static final Long RELEASE_ID = 21L;

    private static final Long ENDPOINT_ID = 3L;

    private static final String PROMPT = "你是订单助手";

    private static final String INPUT_SCHEMA = "{\"type\":\"object\"}";

    private final AiServiceMapper serviceMapper = mock(AiServiceMapper.class);

    private final AiServiceReleaseMapper releaseMapper = mock(AiServiceReleaseMapper.class);

    private final AiServiceResourceMapper resourceMapper = mock(AiServiceResourceMapper.class);

    private final AiServiceReleaseEvaluationMapper evaluationMapper = mock(AiServiceReleaseEvaluationMapper.class);

    private final AiAuthorizationService authorizationService = mock(AiAuthorizationService.class);

    private final AiModelEndpointService endpointService = mock(AiModelEndpointService.class);

    private final AiModelCapabilityProbeService capabilityProbeService = mock(AiModelCapabilityProbeService.class);

    private final AiServiceService serviceService = mock(AiServiceService.class);

    private final AiServiceReleaseServiceImpl service = new AiServiceReleaseServiceImpl(
            serviceMapper,
            releaseMapper,
            resourceMapper,
            evaluationMapper,
            authorizationService,
            endpointService,
            capabilityProbeService,
            serviceService);

    @BeforeEach
    void setUpDefaults() {
        when(serviceService.checkCapabilities(SERVICE_ID))
                .thenReturn(new AiServiceCapabilityDTO()
                        .setRequired(List.of("TEXT"))
                        .setPublishable(List.of("TEXT"))
                        .setSatisfied(true));
        when(serviceMapper.updateWithVersion(any(), anyInt())).thenReturn(1);
        when(releaseMapper.updateWithVersion(any(), anyInt())).thenReturn(1);
        when(endpointService.getEndpoint(ENDPOINT_ID)).thenReturn(endpoint(7));
        when(capabilityProbeService.getCapabilityOverview(ENDPOINT_ID))
                .thenReturn(new AiModelCapabilityOverviewDTO()
                        .setEndpointId(ENDPOINT_ID)
                        .setDeclared(List.of("TEXT"))
                        .setSupported(List.of("TEXT"))
                        .setPublishable(List.of("TEXT")));
        when(authorizationService.authorize(any(), anyString(), anyString(), any(), anyString(), any(), anyList()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(true));
    }

    private static AiModelEndpointDO endpoint(int configRevision) {
        return new AiModelEndpointDO()
                .setId(ENDPOINT_ID)
                .setName("it-endpoint")
                .setConfigRevision(configRevision)
                .setEnabled(true)
                .setVersion(4);
    }

    private static AiServiceDO readyService(Integer threshold) {
        return new AiServiceDO()
                .setId(SERVICE_ID)
                .setAppId(5L)
                .setCode("order-qa")
                .setName("订单问答")
                .setStatus(AiServiceDO.STATUS_READY)
                .setModelEndpointId(ENDPOINT_ID)
                .setPromptTemplate(PROMPT)
                .setInputSchema(INPUT_SCHEMA)
                .setRequiredCapabilities("TEXT")
                .setRunSubjectType("USER")
                .setEvalThreshold(threshold)
                .setDraftRevision(3)
                .setVersion(2);
    }

    private static AiServiceResourceDO binding(Long id, String status) {
        return new AiServiceResourceDO()
                .setId(id)
                .setServiceId(SERVICE_ID)
                .setReleaseId(RELEASE_ID)
                .setResourceType("REPORT")
                .setResourceKey("report-1")
                .setActions("READ")
                .setStatus(status)
                .setVersion(0);
    }

    /** 内容摘要必须由冻结内容算出：测试用的发布版本与真实写入路径保持同一算法。 */
    private static String hashOf(int configRevision, int threshold, List<AiServiceResourceDO> bindings) {
        return AiServiceContentHash.compute(
                ENDPOINT_ID,
                configRevision,
                PROMPT,
                INPUT_SCHEMA,
                null,
                "TEXT",
                threshold,
                bindings.stream()
                        .map(item -> new AiServiceContentHash.ResourceBinding(
                                item.getResourceType(), item.getResourceKey(), item.getActions()))
                        .collect(Collectors.toList()));
    }

    private static AiServiceReleaseDO release(int configRevision, int threshold, List<AiServiceResourceDO> bindings) {
        return new AiServiceReleaseDO()
                .setId(RELEASE_ID)
                .setServiceId(SERVICE_ID)
                .setReleaseVersion(1)
                .setModelEndpointId(ENDPOINT_ID)
                .setEndpointConfigRevision(configRevision)
                .setPromptTemplate(PROMPT)
                .setInputSchema(INPUT_SCHEMA)
                .setRequiredCapabilities("TEXT")
                .setEvalThreshold(threshold)
                .setContentHash(hashOf(configRevision, threshold, bindings))
                .setStatus(AiServiceReleaseDO.STATUS_CANDIDATE)
                .setVersion(0);
    }

    /** 登记发布版本及其冻结绑定：摘要、绑定快照与查询桩保持一致。 */
    private AiServiceReleaseDO stubRelease(int configRevision, int threshold, AiServiceResourceDO... bindings) {
        List<AiServiceResourceDO> frozen = List.of(bindings);
        AiServiceReleaseDO release = release(configRevision, threshold, frozen);
        when(releaseMapper.selectById(RELEASE_ID)).thenReturn(release);
        when(resourceMapper.selectReleaseBindings(RELEASE_ID)).thenReturn(frozen);
        when(resourceMapper.selectActiveReleaseBindings(RELEASE_ID))
                .thenReturn(frozen.stream()
                        .filter(item -> AiServiceResourceDO.STATUS_ACTIVE.equals(item.getStatus()))
                        .collect(Collectors.toList()));
        return release;
    }

    private static AiServiceReleaseEvaluationDO evaluation(String contentHash, int revision, int score, int threshold) {
        return new AiServiceReleaseEvaluationDO()
                .setId(31L)
                .setReleaseId(RELEASE_ID)
                .setContentHash(contentHash)
                .setEndpointConfigRevision(revision)
                .setScore(score)
                .setThreshold(threshold)
                .setPassed(score >= threshold)
                .setCaseCount(12);
    }

    private static void assertCode(
            Throwable throwable, com.basicframework.framework.common.exception.ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void createCandidateFreezesContentBindingsAndEndpointRevision() {
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(readyService(80));
        when(resourceMapper.selectDraftBindings(SERVICE_ID))
                .thenReturn(List.of(binding(11L, AiServiceResourceDO.STATUS_ACTIVE)));
        when(releaseMapper.selectMaxReleaseVersion(SERVICE_ID)).thenReturn(2);
        // MyBatis-Plus 插入后回填自增主键：桩保持一致，候选编号才能返回
        org.mockito.Mockito.doAnswer(invocation -> {
                    ((AiServiceReleaseDO) invocation.getArgument(0)).setId(RELEASE_ID);
                    return 1;
                })
                .when(releaseMapper)
                .insert(any(AiServiceReleaseDO.class));

        assertThat(service.createCandidate(SERVICE_ID, 2)).isEqualTo(RELEASE_ID);

        verify(endpointService).markReferenced(ENDPOINT_ID, 4);
        ArgumentCaptor<AiServiceReleaseDO> releaseCaptor = ArgumentCaptor.forClass(AiServiceReleaseDO.class);
        verify(releaseMapper).insert(releaseCaptor.capture());
        AiServiceReleaseDO frozen = releaseCaptor.getValue();
        assertThat(frozen.getReleaseVersion()).as("版本号在历史最大值上递增").isEqualTo(3);
        assertThat(frozen.getEndpointConfigRevision()).as("端点配置版本被冻结").isEqualTo(7);
        assertThat(frozen.getEvalThreshold()).isEqualTo(80);
        assertThat(frozen.getContentHash())
                .as("摘要由冻结内容按同一算法算出")
                .isEqualTo(hashOf(7, 80, List.of(binding(11L, AiServiceResourceDO.STATUS_ACTIVE))))
                .hasSize(64);
        assertThat(frozen.getStatus()).isEqualTo(AiServiceReleaseDO.STATUS_CANDIDATE);

        ArgumentCaptor<AiServiceResourceDO> bindingCaptor = ArgumentCaptor.forClass(AiServiceResourceDO.class);
        verify(resourceMapper).insert(bindingCaptor.capture());
        assertThat(bindingCaptor.getValue().getReleaseId())
                .as("候选冻结时草稿绑定复制到版本快照")
                .isEqualTo(RELEASE_ID);
        assertThat(bindingCaptor.getValue().getServiceId()).isEqualTo(SERVICE_ID);
    }

    @Test
    void createCandidateRequiresReadyAndSatisfiedCapabilities() {
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(readyService(0).setStatus(AiServiceDO.STATUS_DRAFT));
        assertThatThrownBy(() -> service.createCandidate(SERVICE_ID, 2))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_SERVICE_NOT_READY));

        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(readyService(0));
        when(serviceService.checkCapabilities(SERVICE_ID))
                .thenReturn(new AiServiceCapabilityDTO()
                        .setRequired(List.of("TEXT"))
                        .setPublishable(List.of())
                        .setMissing(List.of("TEXT"))
                        .setSatisfied(false));
        assertThatThrownBy(() -> service.createCandidate(SERVICE_ID, 2))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_MODEL_CAPABILITY_UNSUPPORTED));

        // 能力恢复满足后，草稿版本 CAS 失败仍然拒绝
        when(serviceService.checkCapabilities(SERVICE_ID))
                .thenReturn(new AiServiceCapabilityDTO()
                        .setRequired(List.of("TEXT"))
                        .setPublishable(List.of("TEXT"))
                        .setSatisfied(true));
        when(serviceMapper.updateWithVersion(any(), anyInt())).thenReturn(0);
        assertThatThrownBy(() -> service.createCandidate(SERVICE_ID, 2))
                .as("冻结时并用草稿版本做并发保护")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_STATE_CONFLICT));
    }

    @Test
    void recordEvaluationDerivesVerdictFromFrozenThreshold() {
        stubRelease(7, 80);

        service.recordEvaluation(new AiServiceEvaluationSaveDTO()
                .setReleaseId(RELEASE_ID)
                .setScore(79)
                .setCaseCount(20)
                .setNotes("回归集 v3"));

        ArgumentCaptor<AiServiceReleaseEvaluationDO> captor =
                ArgumentCaptor.forClass(AiServiceReleaseEvaluationDO.class);
        verify(evaluationMapper).insert(captor.capture());
        AiServiceReleaseEvaluationDO recorded = captor.getValue();
        assertThat(recorded.getPassed()).as("低于门槛由平台判失败，调用方不能自报通过").isFalse();
        assertThat(recorded.getThreshold()).isEqualTo(80);
        assertThat(recorded.getContentHash()).as("结论绑定发布内容摘要").isEqualTo(hashOf(7, 80, List.of()));
        assertThat(recorded.getEndpointConfigRevision()).isEqualTo(7);
    }

    @Test
    void recordEvaluationRejectsRetiredReleaseAndChangedEndpointConfig() {
        when(releaseMapper.selectById(RELEASE_ID))
                .thenReturn(release(7, 0, List.of()).setStatus(AiServiceReleaseDO.STATUS_RETIRED));
        assertThatThrownBy(() -> service.recordEvaluation(new AiServiceEvaluationSaveDTO()
                        .setReleaseId(RELEASE_ID)
                        .setScore(90)
                        .setCaseCount(1)))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_STATE_CONFLICT));

        stubRelease(7, 0);
        when(endpointService.getEndpoint(ENDPOINT_ID)).thenReturn(endpoint(8));
        assertThatThrownBy(() -> service.recordEvaluation(new AiServiceEvaluationSaveDTO()
                        .setReleaseId(RELEASE_ID)
                        .setScore(90)
                        .setCaseCount(1)))
                .as("端点配置已变时记录评测等于给无效证据盖章")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_SERVICE_ENDPOINT_CONFIG_CHANGED));

        assertThatThrownBy(() -> service.recordEvaluation(new AiServiceEvaluationSaveDTO()
                        .setReleaseId(RELEASE_ID)
                        .setScore(101)
                        .setCaseCount(1)))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
    }

    @Test
    void publishSwitchesAliasAtomicallyAfterPreChecks() {
        AiServiceReleaseDO release = stubRelease(7, 80, binding(11L, AiServiceResourceDO.STATUS_ACTIVE));
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(readyService(80));
        when(evaluationMapper.selectLatest(RELEASE_ID)).thenReturn(evaluation(release.getContentHash(), 7, 90, 80));

        service.publish(RELEASE_ID, 0);

        verify(releaseMapper).retireActive(SERVICE_ID);
        ArgumentCaptor<AiServiceReleaseDO> captor = ArgumentCaptor.forClass(AiServiceReleaseDO.class);
        verify(releaseMapper).updateWithVersion(captor.capture(), eq(0));
        assertThat(captor.getValue().getStatus()).isEqualTo(AiServiceReleaseDO.STATUS_ACTIVE);
        assertThat(captor.getValue().getPromptTemplate()).as("状态切换不触碰内容列").isNull();
    }

    @Test
    void publishBlockersAreOrderedAndFailClosed() {
        AiServiceReleaseDO release = stubRelease(7, 80, binding(11L, AiServiceResourceDO.STATUS_ACTIVE));
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(readyService(80));

        when(evaluationMapper.selectLatest(RELEASE_ID)).thenReturn(null);
        assertThat(service.checkPublishReadiness(RELEASE_ID))
                .containsExactly(AiErrorCodeConstants.AI_SERVICE_EVAL_MISSING.getMsg());

        // 旧内容的评测报告不能用于新内容
        when(evaluationMapper.selectLatest(RELEASE_ID)).thenReturn(evaluation(hashOf(7, 80, List.of()), 7, 100, 80));
        assertThat(service.checkPublishReadiness(RELEASE_ID))
                .containsExactly(AiErrorCodeConstants.AI_SERVICE_EVAL_MISSING.getMsg());

        // 最新一条是失败结论：历史里有通过记录也不得发布
        when(evaluationMapper.selectLatest(RELEASE_ID)).thenReturn(evaluation(release.getContentHash(), 7, 10, 80));
        assertThat(service.checkPublishReadiness(RELEASE_ID))
                .containsExactly(AiErrorCodeConstants.AI_SERVICE_EVAL_BELOW_THRESHOLD.getMsg());

        // 门槛被抬高后原结论不再达标
        when(evaluationMapper.selectLatest(RELEASE_ID)).thenReturn(evaluation(release.getContentHash(), 7, 85, 90));
        assertThat(service.checkPublishReadiness(RELEASE_ID))
                .containsExactly(AiErrorCodeConstants.AI_SERVICE_EVAL_BELOW_THRESHOLD.getMsg());

        // 全部通过
        when(evaluationMapper.selectLatest(RELEASE_ID)).thenReturn(evaluation(release.getContentHash(), 7, 90, 80));
        assertThat(service.checkPublishReadiness(RELEASE_ID)).isEmpty();

        // 资源被禁用（绑定已解除）优先于评测
        stubRelease(7, 80, binding(11L, AiServiceResourceDO.STATUS_RELEASED));
        assertThat(service.checkPublishReadiness(RELEASE_ID))
                .containsExactly(AiErrorCodeConstants.AI_SERVICE_RESOURCE_UNAVAILABLE.getMsg());

        // 端点配置版本变化
        stubRelease(7, 80, binding(11L, AiServiceResourceDO.STATUS_ACTIVE));
        when(endpointService.getEndpoint(ENDPOINT_ID)).thenReturn(endpoint(8));
        assertThat(service.checkPublishReadiness(RELEASE_ID))
                .containsExactly(AiErrorCodeConstants.AI_SERVICE_ENDPOINT_CONFIG_CHANGED.getMsg());

        // 绑定后失权
        when(endpointService.getEndpoint(ENDPOINT_ID)).thenReturn(endpoint(7));
        when(authorizationService.authorize(any(), anyString(), anyString(), any(), anyString(), any(), anyList()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(false).setDenyReason("REVOKED"));
        assertThat(service.checkPublishReadiness(RELEASE_ID))
                .containsExactly(AiErrorCodeConstants.AI_AUTHORIZATION_DENIED.getMsg());

        // 能力不再满足（探测结论变化）：按发布版本自己冻结的能力判定，而不是当前草稿
        when(authorizationService.authorize(any(), anyString(), anyString(), any(), anyString(), any(), anyList()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(true));
        when(capabilityProbeService.getCapabilityOverview(ENDPOINT_ID))
                .thenReturn(new AiModelCapabilityOverviewDTO()
                        .setEndpointId(ENDPOINT_ID)
                        .setDeclared(List.of("TEXT"))
                        .setSupported(List.of())
                        .setPublishable(List.of()));
        assertThat(service.checkPublishReadiness(RELEASE_ID))
                .containsExactly(AiErrorCodeConstants.AI_MODEL_CAPABILITY_UNSUPPORTED.getMsg());
    }

    @Test
    void integrityGuardRejectsTamperedReleaseContent() {
        AiServiceReleaseDO tampered = stubRelease(7, 0).setPromptTemplate("被改写的提示词");
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(readyService(0));
        when(evaluationMapper.selectLatest(RELEASE_ID)).thenReturn(evaluation(tampered.getContentHash(), 7, 90, 0));

        assertThat(service.checkPublishReadiness(RELEASE_ID))
                .as("内容摘要与冻结内容不一致时拒绝发布")
                .containsExactly(AiErrorCodeConstants.AI_STATE_CONFLICT.getMsg());
    }

    @Test
    void publishRejectsNonCandidateAndKeepsActiveOnCasFailure() {
        when(releaseMapper.selectById(RELEASE_ID))
                .thenReturn(release(7, 0, List.of()).setStatus(AiServiceReleaseDO.STATUS_ACTIVE));
        assertThatThrownBy(() -> service.publish(RELEASE_ID, 1))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_STATE_CONFLICT));
        verify(releaseMapper, never()).retireActive(any());

        // 预检查全部通过但版本 CAS 失败：抛 409，事务回滚后当前 active 不变
        stubRelease(7, 0);
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(readyService(0));
        when(evaluationMapper.selectLatest(RELEASE_ID)).thenReturn(evaluation(hashOf(7, 0, List.of()), 7, 90, 0));
        when(releaseMapper.updateWithVersion(any(), anyInt())).thenReturn(0);
        assertThatThrownBy(() -> service.publish(RELEASE_ID, 0))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_STATE_CONFLICT));
    }

    @Test
    void disableRetiresActiveAndRejectsWhenNothingActive() {
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(readyService(0));
        when(releaseMapper.retireActive(SERVICE_ID)).thenReturn(1);
        service.disable(SERVICE_ID, 2);
        verify(releaseMapper).retireActive(SERVICE_ID);

        when(releaseMapper.retireActive(SERVICE_ID)).thenReturn(0);
        assertThatThrownBy(() -> service.disable(SERVICE_ID, 3))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_STATE_CONFLICT));
    }

    @Test
    void resolveForNewRunRequiresActiveReleaseWithUsableResources() {
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(readyService(0));

        when(releaseMapper.selectActiveByService(SERVICE_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.resolveForNewRun(SERVICE_ID))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_SERVICE_NOT_PUBLISHED));

        when(releaseMapper.selectActiveByService(SERVICE_ID))
                .thenReturn(release(7, 0, List.of()).setStatus(AiServiceReleaseDO.STATUS_ACTIVE));
        when(resourceMapper.selectReleaseBindings(RELEASE_ID))
                .thenReturn(List.of(binding(11L, AiServiceResourceDO.STATUS_RELEASED)));
        assertThatThrownBy(() -> service.resolveForNewRun(SERVICE_ID))
                .as("资源被禁用后新运行拒绝")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_SERVICE_RESOURCE_UNAVAILABLE));

        stubRelease(7, 0, binding(11L, AiServiceResourceDO.STATUS_ACTIVE));
        when(releaseMapper.selectActiveByService(SERVICE_ID))
                .thenReturn(release(7, 0, List.of()).setStatus(AiServiceReleaseDO.STATUS_ACTIVE));
        assertThat(service.resolveForNewRun(SERVICE_ID).getRelease().getReleaseVersion())
                .isEqualTo(1);
        assertThat(service.resolveForNewRun(SERVICE_ID).getBindings()).hasSize(1);

        when(endpointService.getEndpoint(ENDPOINT_ID)).thenReturn(endpoint(9));
        assertThatThrownBy(() -> service.resolveForNewRun(SERVICE_ID))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_SERVICE_ENDPOINT_CONFIG_CHANGED));
    }

    @Test
    void resolveForNewRunRejectsDisabledEndpointWithStableError() {
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(readyService(0));
        when(releaseMapper.selectActiveByService(SERVICE_ID))
                .thenReturn(release(7, 0, List.of()).setStatus(AiServiceReleaseDO.STATUS_ACTIVE));
        when(endpointService.getEndpoint(ENDPOINT_ID)).thenReturn(endpoint(7).setEnabled(false));

        assertThatThrownBy(() -> service.resolveForNewRun(SERVICE_ID))
                .as("失效模型给稳定错误：停用端点不接受任何运行")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_MODEL_ENDPOINT_DISABLED));
    }

    @Test
    void resolveForNewRunRechecksAuthorizationAgainstCurrentGrants() {
        AiServiceReleaseDO active = stubRelease(7, 0, binding(11L, AiServiceResourceDO.STATUS_ACTIVE));
        active.setStatus(AiServiceReleaseDO.STATUS_ACTIVE);
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(readyService(0));
        when(releaseMapper.selectActiveByService(SERVICE_ID)).thenReturn(active);

        assertThat(service.resolveForNewRun(SERVICE_ID).isPinned()).isFalse();
        assertThat(service.resolveForNewRun(SERVICE_ID).getPin().getModelRevision())
                .isEqualTo(7);
        assertThat(service.resolveForNewRun(SERVICE_ID).getPin().resourceBindingIds())
                .containsExactly(11L);

        when(authorizationService.authorize(any(), anyString(), anyString(), any(), anyString(), any(), anyList()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(false).setDenyReason("REVOKED"));
        assertThatThrownBy(() -> service.resolveForNewRun(SERVICE_ID))
                .as("当前授权变化始终优先于已冻结的发布版本")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_AUTHORIZATION_DENIED));
    }

    @Test
    void rollbackSwitchesAliasBackToHistoricalVersion() {
        AiServiceReleaseDO target = stubRelease(7, 0, binding(11L, AiServiceResourceDO.STATUS_ACTIVE));
        target.setStatus(AiServiceReleaseDO.STATUS_RETIRED);
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(readyService(0));
        when(evaluationMapper.selectLatest(RELEASE_ID)).thenReturn(evaluation(target.getContentHash(), 7, 90, 0));
        when(releaseMapper.retireActive(SERVICE_ID)).thenReturn(1);

        service.rollback(RELEASE_ID, 0);

        verify(releaseMapper).retireActive(SERVICE_ID);
        ArgumentCaptor<AiServiceReleaseDO> captor = ArgumentCaptor.forClass(AiServiceReleaseDO.class);
        verify(releaseMapper).updateWithVersion(captor.capture(), eq(0));
        assertThat(captor.getValue().getStatus()).isEqualTo(AiServiceReleaseDO.STATUS_ACTIVE);
        assertThat(captor.getValue().getPromptTemplate())
                .as("回退只切状态：历史版本内容一字不改")
                .isNull();
    }

    @Test
    void rollbackRejectsCandidateAndAlreadyActiveTargets() {
        when(releaseMapper.selectById(RELEASE_ID))
                .thenReturn(release(7, 0, List.of()).setStatus(AiServiceReleaseDO.STATUS_CANDIDATE));
        assertThatThrownBy(() -> service.rollback(RELEASE_ID, 0))
                .as("从未发布的候选不能作为回退目标")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_SERVICE_RELEASE_NOT_PUBLISHED));

        when(releaseMapper.selectById(RELEASE_ID))
                .thenReturn(release(7, 0, List.of()).setStatus(AiServiceReleaseDO.STATUS_ACTIVE));
        assertThatThrownBy(() -> service.rollback(RELEASE_ID, 0))
                .as("目标已是当前生效版本：回退必须是版本切换")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_STATE_CONFLICT));
        verify(releaseMapper, never()).retireActive(any());
    }

    @Test
    void rollbackRunsSamePreChecksAsPublish() {
        AiServiceReleaseDO target = stubRelease(7, 80, binding(11L, AiServiceResourceDO.STATUS_ACTIVE));
        target.setStatus(AiServiceReleaseDO.STATUS_RETIRED);
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(readyService(80));
        when(evaluationMapper.selectLatest(RELEASE_ID)).thenReturn(evaluation(target.getContentHash(), 7, 10, 80));

        assertThatThrownBy(() -> service.rollback(RELEASE_ID, 0))
                .as("回退方向的评测证据不足时不切换别名")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_SERVICE_EVAL_BELOW_THRESHOLD));
        verify(releaseMapper, never()).retireActive(any());
    }

    @Test
    void resolvePinnedRunRequiresReleaseIdAndContentHash() {
        assertThatThrownBy(() -> service.resolvePinnedRun(null))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
        assertThatThrownBy(() -> service.resolvePinnedRun(
                        AiRunSnapshot.of(release(7, 0, List.of()).setId(null), List.of())))
                .as("缺少版本编号的固定值不可用")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
        assertThatThrownBy(() -> service.resolvePinnedRun(
                        AiRunSnapshot.of(release(7, 0, List.of()).setContentHash(""), List.of())))
                .as("缺少内容摘要的固定值不可用")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
    }

    @Test
    void publishDoesNotRequireCapabilitiesWhenTheReleaseDeclaresNone() {
        // 不声明能力的版本：摘要必须由同一份"不含能力"的内容算出，否则完整性守卫先拒绝
        AiServiceReleaseDO release = release(7, 0, List.of()).setRequiredCapabilities("");
        release.setContentHash(
                AiServiceContentHash.compute(ENDPOINT_ID, 7, PROMPT, INPUT_SCHEMA, null, "", 0, List.of()));
        release.setStatus(AiServiceReleaseDO.STATUS_RETIRED);
        when(releaseMapper.selectById(RELEASE_ID)).thenReturn(release);
        when(resourceMapper.selectReleaseBindings(RELEASE_ID)).thenReturn(List.of());
        when(resourceMapper.selectActiveReleaseBindings(RELEASE_ID)).thenReturn(List.of());
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(readyService(0));
        when(evaluationMapper.selectLatest(RELEASE_ID)).thenReturn(evaluation(release.getContentHash(), 7, 90, 0));
        when(releaseMapper.retireActive(SERVICE_ID)).thenReturn(1);

        // 不声明能力的版本不因探测结论变化被阻塞；能力检查只覆盖它自己冻结的能力集合
        assertThat(service.checkPublishReadiness(RELEASE_ID)).isEmpty();
        service.rollback(RELEASE_ID, 0);
        verify(releaseMapper).retireActive(SERVICE_ID);
    }

    @Test
    void pinnedRunKeepsPinnedVersionWhileAliasMovesOn() {
        AiServiceReleaseDO first = stubRelease(7, 0, binding(11L, AiServiceResourceDO.STATUS_ACTIVE));
        first.setStatus(AiServiceReleaseDO.STATUS_RETIRED);
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(readyService(0));
        // 别名已经切到第二版：新运行按别名解析，固定会话仍解析回第一版
        AiServiceReleaseDO second = release(7, 0, List.of()).setId(22L).setReleaseVersion(2);
        second.setStatus(AiServiceReleaseDO.STATUS_ACTIVE);
        when(releaseMapper.selectActiveByService(SERVICE_ID)).thenReturn(second);
        when(releaseMapper.selectById(22L)).thenReturn(second);

        AiRunSnapshot pin = AiRunSnapshot.of(first, resourceMapper.selectReleaseBindings(RELEASE_ID));
        AiServiceRunSnapshotDTO pinned = service.resolvePinnedRun(pin);
        assertThat(pinned.isPinned()).isTrue();
        assertThat(pinned.getRelease().getId()).as("版本固定：别名切换不改变已固定会话的版本").isEqualTo(RELEASE_ID);
        assertThat(pinned.getPin().getReleaseVersion()).isEqualTo(1);
        assertThat(service.resolveForNewRun(SERVICE_ID).getRelease().getId())
                .as("新运行按别名解析到最新版本")
                .isEqualTo(22L);
    }

    @Test
    void pinnedRunRejectsTamperedPinAndOfflineService() {
        AiServiceReleaseDO stored = stubRelease(7, 0, binding(11L, AiServiceResourceDO.STATUS_ACTIVE));
        stored.setStatus(AiServiceReleaseDO.STATUS_RETIRED);
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(readyService(0));

        // 固定值与库中版本的内容摘要不一致（例如会话记录被改写）：拒绝而不是换一个版本执行
        AiRunSnapshot tampered = AiRunSnapshot.of(release(7, 0, List.of()).setContentHash("b".repeat(64)), List.of());
        when(releaseMapper.selectById(RELEASE_ID)).thenReturn(stored);
        assertThatThrownBy(() -> service.resolvePinnedRun(tampered))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_SERVICE_RELEASE_PIN_STALE));

        // 跨服务固定：固定值不能被用来解析其它服务的版本（与"版本不存在"同语义）
        AiServiceReleaseDO foreign = release(7, 0, List.of()).setId(RELEASE_ID).setServiceId(77L);
        when(serviceMapper.selectById(77L)).thenReturn(readyService(0).setId(77L));
        assertThatThrownBy(() -> service.resolvePinnedRun(AiRunSnapshot.of(foreign, List.of())))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND));

        AiRunSnapshot pin = AiRunSnapshot.of(stored, List.of());
        when(releaseMapper.selectById(RELEASE_ID)).thenReturn(stored.setStatus(AiServiceReleaseDO.STATUS_CANDIDATE));
        assertThatThrownBy(() -> service.resolvePinnedRun(pin))
                .as("候选从未对运行开放，不可能被固定")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_SERVICE_RELEASE_NOT_PUBLISHED));

        when(releaseMapper.selectById(RELEASE_ID)).thenReturn(stored.setStatus(AiServiceReleaseDO.STATUS_RETIRED));
        when(releaseMapper.selectActiveByService(SERVICE_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.resolvePinnedRun(pin))
                .as("服务被显式停用后固定会话同样停止")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_SERVICE_NOT_PUBLISHED));
    }

    @Test
    void pinnedRunRejectsChangedBindingVersionAndRevokedGrant() {
        AiServiceReleaseDO first = stubRelease(7, 0, binding(11L, AiServiceResourceDO.STATUS_ACTIVE));
        first.setStatus(AiServiceReleaseDO.STATUS_RETIRED);
        when(releaseMapper.selectById(RELEASE_ID)).thenReturn(first);
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(readyService(0));
        when(releaseMapper.selectActiveByService(SERVICE_ID))
                .thenReturn(first.setStatus(AiServiceReleaseDO.STATUS_ACTIVE).setId(RELEASE_ID));
        AiRunSnapshot pin = AiRunSnapshot.of(first, resourceMapper.selectReleaseBindings(RELEASE_ID));

        // 绑定被解绑（版本推进）：固定运行不能静默改用新绑定
        when(resourceMapper.selectReleaseBindings(RELEASE_ID))
                .thenReturn(List.of(
                        binding(11L, AiServiceResourceDO.STATUS_RELEASED).setVersion(1)));
        assertThatThrownBy(() -> service.resolvePinnedRun(pin))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_SERVICE_RESOURCE_UNAVAILABLE));

        // 绑定仍在但当前授权被撤销：固定版本不保留旧权限
        when(resourceMapper.selectReleaseBindings(RELEASE_ID))
                .thenReturn(List.of(binding(11L, AiServiceResourceDO.STATUS_ACTIVE)));
        when(authorizationService.authorize(any(), anyString(), anyString(), any(), anyString(), any(), anyList()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(false).setDenyReason("REVOKED"));
        assertThatThrownBy(() -> service.resolvePinnedRun(pin))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_AUTHORIZATION_DENIED));

        // 端点配置漂移：固定运行不静默改用新配置
        when(authorizationService.authorize(any(), anyString(), anyString(), any(), anyString(), any(), anyList()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(true));
        when(endpointService.getEndpoint(ENDPOINT_ID)).thenReturn(endpoint(8));
        assertThatThrownBy(() -> service.resolvePinnedRun(pin))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_SERVICE_ENDPOINT_CONFIG_CHANGED));
    }
}
