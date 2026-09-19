package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseDO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.identity.SubjectScope;
import com.basicframework.module.ai.domain.identity.SubjectScopeResolver;
import com.basicframework.module.ai.domain.runtime.AiRunSnapshot;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.authorization.AiResourceGrantService;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.dto.AiModelEndpointSaveDTO;
import com.basicframework.module.ai.service.serviceconfig.AiServiceReleaseService;
import com.basicframework.module.ai.service.serviceconfig.AiServiceService;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceEvaluationSaveDTO;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceResourceSaveDTO;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceRunSnapshotDTO;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceSaveDTO;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * S03 版本回退与运行快照解析端到端（真实 MySQL）：N/N-1 快照共存、别名切换只影响后续运行、
 * 回退与发布共用预检查、固定版本始终按当前授权与绑定状态判定。
 */
@Import(AiServiceVersionRollbackIT.ResolverConfiguration.class)
class AiServiceVersionRollbackIT extends AbstractPersistenceIntegrationTest {

    @TestConfiguration
    static class ResolverConfiguration {

        @Bean
        SubjectScopeResolver rollbackScopeResolver() {
            return request -> Optional.of(new SubjectScope(
                    Set.of(10L), Set.of("report-1", "kb-1"), request.scopeSource(), request.scopeVersion()));
        }
    }

    private static final String APP_CODE = "it-rollback-app";

    private static final String ENDPOINT_NAME = "it-rollback-endpoint";

    @Autowired
    private AiApplicationService applicationService;

    @Autowired
    private AiResourceGrantService grantService;

    @Autowired
    private AiModelEndpointService endpointService;

    @Autowired
    private AiSubjectService subjectService;

    @Autowired
    private AiServiceService serviceService;

    @Autowired
    private AiServiceReleaseService releaseService;

    @AfterEach
    void cleanUp() {
        List<Long> appIds =
                jdbcTemplate.queryForList("SELECT id FROM ai_application WHERE app_code = ?", Long.class, APP_CODE);
        for (Long appId : appIds) {
            List<Long> serviceIds =
                    jdbcTemplate.queryForList("SELECT id FROM ai_service WHERE app_id = ?", Long.class, appId);
            for (Long serviceId : serviceIds) {
                List<Long> releaseIds = jdbcTemplate.queryForList(
                        "SELECT id FROM ai_service_release WHERE service_id = ?", Long.class, serviceId);
                for (Long releaseId : releaseIds) {
                    jdbcTemplate.update("DELETE FROM ai_service_release_evaluation WHERE release_id = ?", releaseId);
                }
                jdbcTemplate.update("DELETE FROM ai_service_resource WHERE service_id = ?", serviceId);
                jdbcTemplate.update("DELETE FROM ai_service_release WHERE service_id = ?", serviceId);
                jdbcTemplate.update("DELETE FROM ai_service WHERE id = ?", serviceId);
            }
            jdbcTemplate.update("DELETE FROM ai_resource_grant WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_subject WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_access_ticket WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application_credential WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application WHERE id = ?", appId);
        }
        List<Long> endpointIds =
                jdbcTemplate.queryForList("SELECT id FROM ai_model_endpoint WHERE name = ?", Long.class, ENDPOINT_NAME);
        for (Long endpointId : endpointIds) {
            jdbcTemplate.update("DELETE FROM ai_model_probe WHERE endpoint_id = ?", endpointId);
            jdbcTemplate.update("DELETE FROM ai_model_endpoint_revision WHERE endpoint_id = ?", endpointId);
            jdbcTemplate.update("DELETE FROM ai_model_endpoint WHERE id = ?", endpointId);
        }
    }

    private Long createApplication() {
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(APP_CODE)
                .setName("IT 回退应用")
                .setOrigins(List.of("https://rollback.example.com")));
        Long appId = issue.getApplication().getId();
        applicationService.updateStatus(appId, 0, true);
        subjectService.syncSubject(appId, AiSubjectType.APP, null, "IT 回退应用", "it-owner", 1L);
        return appId;
    }

    private Long createProbedEndpoint() {
        AiModelEndpointSaveDTO saveDTO = new AiModelEndpointSaveDTO();
        saveDTO.setName(ENDPOINT_NAME);
        saveDTO.setProvider("openai_compatible");
        saveDTO.setBaseUrl("https://it-rollback.example.com/v1");
        saveDTO.setModelId("gpt-4o-mini");
        saveDTO.setCapabilities(List.of("TEXT"));
        saveDTO.setCredential("sk-it-rollback");
        Long endpointId = endpointService.createEndpoint(saveDTO);
        var endpoint = endpointService.getEndpoint(endpointId);
        endpointService.updateEndpointStatus(endpointId, endpoint.getVersion(), true);
        var revision = endpointService.getRevisions(endpointId).get(0);
        jdbcTemplate.update(
                "INSERT INTO ai_model_probe (endpoint_id, config_revision, credential_revision, probe_kind, status,"
                        + " detail_code, latency_ms, creator, updater) VALUES (?, ?, 1, 'TEXT', 'SUPPORTED', NULL, 5,"
                        + " 'it', 'it')",
                endpointId,
                revision.getRevision());
        return endpointId;
    }

    private static AiServiceSaveDTO draft(Long appId, Long endpointId, String prompt) {
        return new AiServiceSaveDTO()
                .setAppId(appId)
                .setCode("order-qa")
                .setName("订单问答")
                .setModelEndpointId(endpointId)
                .setPromptTemplate(prompt)
                .setInputSchema("{\"type\":\"object\"}")
                .setOutputSchema("{\"type\":\"object\"}")
                .setRequiredCapabilities(List.of("TEXT"))
                .setRunSubjectType("USER")
                .setEvalThreshold(0);
    }

    /** 建好草稿 + 资源授权与绑定 + 标记可发布，返回服务编号。 */
    private Long prepareReadyService(Long appId, Long endpointId, String prompt) {
        grantService.createGrant(appId, "APP", null, "REPORT", "report-1", Set.of("READ"));
        Long serviceId = serviceService.createDraft(draft(appId, endpointId, prompt));
        serviceService.bindResource(new AiServiceResourceSaveDTO()
                .setServiceId(serviceId)
                .setResourceType("REPORT")
                .setResourceKey("report-1")
                .setActions(List.of("READ")));
        var service = serviceService.getService(serviceId);
        serviceService.markReady(serviceId, service.getVersion());
        return serviceId;
    }

    /** 冻结当前草稿、评测达标并发布，返回发布版本编号。 */
    private Long publishCurrentDraft(Long serviceId, int score) {
        var ready = serviceService.getService(serviceId);
        Long releaseId = releaseService.createCandidate(serviceId, ready.getVersion());
        releaseService.recordEvaluation(new AiServiceEvaluationSaveDTO()
                .setReleaseId(releaseId)
                .setScore(score)
                .setCaseCount(5));
        releaseService.publish(releaseId, 0);
        return releaseId;
    }

    private Long editDraftAndMarkReady(Long appId, Long endpointId, Long serviceId, String prompt) {
        var current = serviceService.getService(serviceId);
        serviceService.updateDraft(
                draft(appId, endpointId, prompt).setId(serviceId).setVersion(current.getVersion()));
        var edited = serviceService.getService(serviceId);
        serviceService.markReady(serviceId, edited.getVersion());
        return serviceId;
    }

    private Integer versionOf(Long releaseId) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM ai_service_release WHERE id = ?", Integer.class, releaseId);
    }

    private static void assertCode(
            Throwable throwable, com.basicframework.framework.common.exception.ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void aliasSwitchKeepsPinnedSessionsOnTheirVersion() {
        Long appId = createApplication();
        Long endpointId = createProbedEndpoint();
        Long serviceId = prepareReadyService(appId, endpointId, "你是订单助手");

        // 第一版发布：新运行的固定值来自别名解析
        Long firstReleaseId = publishCurrentDraft(serviceId, 90);
        AiServiceRunSnapshotDTO firstRun = releaseService.resolveForNewRun(serviceId);
        assertThat(firstRun.getRelease().getId()).isEqualTo(firstReleaseId);
        assertThat(firstRun.isPinned()).isFalse();
        AiRunSnapshot firstPin = firstRun.getPin();
        assertThat(firstPin.getReleaseVersion()).isEqualTo(1);
        assertThat(firstPin.getModelRevision())
                .as("固定值包含端点配置版本")
                .isEqualTo(firstRun.getRelease().getEndpointConfigRevision());
        assertThat(firstPin.resourceBindingIds())
                .as("固定值包含资源版本")
                .containsExactly(firstRun.getBindings().get(0).getId());

        // 第二版发布：新运行解析到 N+1，已固定会话仍解析到 N
        editDraftAndMarkReady(appId, endpointId, serviceId, "你是订单助手 v2");
        Long secondReleaseId = publishCurrentDraft(serviceId, 95);
        AiServiceRunSnapshotDTO secondRun = releaseService.resolveForNewRun(serviceId);
        assertThat(secondRun.getRelease().getId()).isEqualTo(secondReleaseId);
        AiRunSnapshot secondPin = secondRun.getPin();

        AiServiceRunSnapshotDTO stillFirst = releaseService.resolvePinnedRun(firstPin);
        assertThat(stillFirst.getRelease().getId()).as("运行沿用它开始的版本，不被新版本静默替换").isEqualTo(firstReleaseId);
        assertThat(stillFirst.getRelease().getPromptTemplate()).isEqualTo("你是订单助手");
        assertThat(stillFirst.isPinned()).isTrue();

        // 回退到 N：别名切回历史版本，当前只有一条 ACTIVE
        Integer firstVersionBeforeRollback = versionOf(firstReleaseId);
        releaseService.rollback(firstReleaseId, firstVersionBeforeRollback);
        assertThat(jdbcTemplate.queryForList(
                        "SELECT id FROM ai_service_release WHERE service_id = ? AND status = 'ACTIVE'",
                        Long.class,
                        serviceId))
                .containsExactly(firstReleaseId);
        assertThat(releaseService.resolveForNewRun(serviceId).getRelease().getId())
                .as("回退只影响后续运行")
                .isEqualTo(firstReleaseId);

        // 旧会话（固定 N+1）不受回退影响：仍是它开始的版本与内容
        AiServiceRunSnapshotDTO pinnedSecond = releaseService.resolvePinnedRun(secondPin);
        assertThat(pinnedSecond.getRelease().getId()).isEqualTo(secondReleaseId);
        assertThat(pinnedSecond.getRelease().getPromptTemplate()).isEqualTo("你是订单助手 v2");
        assertThat(pinnedSecond.getRelease().getStatus()).isEqualTo(AiServiceReleaseDO.STATUS_RETIRED);

        // 回退只推进状态与版本，发布版本内容一字不改
        assertThat(versionOf(firstReleaseId)).isEqualTo(firstVersionBeforeRollback + 1);
        assertThat(jdbcTemplate.queryForMap(
                        "SELECT prompt_template, content_hash, endpoint_config_revision FROM ai_service_release"
                                + " WHERE id = ?",
                        secondReleaseId))
                .containsEntry("prompt_template", "你是订单助手 v2")
                .containsEntry("content_hash", secondPin.getContentHash())
                .containsEntry("endpoint_config_revision", secondPin.getModelRevision());
    }

    @Test
    void pinnedRunAlwaysChecksCurrentModelAuthorizationAndBindings() {
        Long appId = createApplication();
        Long endpointId = createProbedEndpoint();
        Long serviceId = prepareReadyService(appId, endpointId, "你是订单助手");
        publishCurrentDraft(serviceId, 90);
        AiRunSnapshot pin = releaseService.resolveForNewRun(serviceId).getPin();
        Long bindingId = pin.resourceBindingIds().get(0);

        // 端点停用：失效模型给稳定错误，新运行与固定会话都不例外
        var endpoint = endpointService.getEndpoint(endpointId);
        endpointService.updateEndpointStatus(endpointId, endpoint.getVersion(), false);
        assertThatThrownBy(() -> releaseService.resolveForNewRun(serviceId))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_MODEL_ENDPOINT_DISABLED));
        assertThatThrownBy(() -> releaseService.resolvePinnedRun(pin))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_MODEL_ENDPOINT_DISABLED));
        var disabled = endpointService.getEndpoint(endpointId);
        endpointService.updateEndpointStatus(endpointId, disabled.getVersion(), true);

        // 授权撤销：固定版本不保留旧权限
        Long grantId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_resource_grant WHERE application_id = ? AND resource_key = 'report-1'",
                Long.class,
                appId);
        grantService.revokeGrant(grantId, grantService.getGrant(grantId).getVersion());
        assertThatThrownBy(() -> releaseService.resolveForNewRun(serviceId))
                .as("当前授权变化优先于已固定版本")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_AUTHORIZATION_DENIED));
        assertThatThrownBy(() -> releaseService.resolvePinnedRun(pin))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_AUTHORIZATION_DENIED));

        // 绑定解除：固定运行不静默改用别的绑定
        serviceService.unbindResource(bindingId, 0);
        assertThatThrownBy(() -> releaseService.resolvePinnedRun(pin))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_SERVICE_RESOURCE_UNAVAILABLE));
    }

    @Test
    void rollbackRunsSamePreChecksAndKeepsActiveOnFailure() {
        Long appId = createApplication();
        Long endpointId = createProbedEndpoint();
        Long serviceId = prepareReadyService(appId, endpointId, "你是订单助手");

        Long firstReleaseId = publishCurrentDraft(serviceId, 90);

        // 候选不能作为回退目标：必须先发布
        var ready = serviceService.getService(serviceId);
        Long candidateId = releaseService.createCandidate(serviceId, ready.getVersion());
        assertThatThrownBy(() -> releaseService.rollback(candidateId, 0))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_SERVICE_RELEASE_NOT_PUBLISHED));

        // 目标是当前生效版本：回退必须是版本切换，不是原地重放
        assertThatThrownBy(() -> releaseService.rollback(firstReleaseId, versionOf(firstReleaseId)))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_STATE_CONFLICT));

        // 第二版上线后，把第一版的绑定解除：回退目标依赖已解除的资源时拒绝，且当前 active 不变
        releaseService.recordEvaluation(new AiServiceEvaluationSaveDTO()
                .setReleaseId(candidateId)
                .setScore(95)
                .setCaseCount(5));
        releaseService.publish(candidateId, versionOf(candidateId));
        Long firstBindingId =
                releaseService.listReleaseBindings(firstReleaseId).get(0).getId();
        serviceService.unbindResource(firstBindingId, 0);
        assertThatThrownBy(() -> releaseService.rollback(firstReleaseId, versionOf(firstReleaseId)))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_SERVICE_RESOURCE_UNAVAILABLE));
        assertThat(jdbcTemplate.queryForList(
                        "SELECT id FROM ai_service_release WHERE service_id = ? AND status = 'ACTIVE'",
                        Long.class,
                        serviceId))
                .as("失败的回退不改变当前 active")
                .containsExactly(candidateId);
        assertThat(releaseService.resolveForNewRun(serviceId).getRelease().getId())
                .isEqualTo(candidateId);

        // 端点非秘密配置被修改（配置版本漂移）：回退与发布同样在预检查处拒绝
        var endpoint = endpointService.getEndpoint(endpointId);
        endpointService.updateEndpoint(new AiModelEndpointSaveDTO()
                .setId(endpointId)
                .setVersion(endpoint.getVersion())
                .setName(endpoint.getName())
                .setProvider(endpoint.getProvider())
                .setBaseUrl(endpoint.getBaseUrl())
                .setModelId("gpt-4o-mini-2024")
                .setCapabilities(List.of("TEXT")));
        assertThat(releaseService.checkPublishReadiness(candidateId))
                .as("端点配置版本漂移后预检查拒绝")
                .containsExactly(AiErrorCodeConstants.AI_SERVICE_ENDPOINT_CONFIG_CHANGED.getMsg());
        assertThatThrownBy(() -> releaseService.rollback(firstReleaseId, versionOf(firstReleaseId)))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_SERVICE_ENDPOINT_CONFIG_CHANGED));
        assertThat(jdbcTemplate.queryForList(
                        "SELECT id FROM ai_service_release WHERE service_id = ? AND status = 'ACTIVE'",
                        Long.class,
                        serviceId))
                .as("漂移导致的回退失败同样不改变当前 active")
                .containsExactly(candidateId);
    }

    @Test
    void pinnedRunIsRejectedWhenTheServiceIsDisabled() {
        Long appId = createApplication();
        Long endpointId = createProbedEndpoint();
        Long serviceId = prepareReadyService(appId, endpointId, "你是订单助手");
        publishCurrentDraft(serviceId, 90);
        AiRunSnapshot pin = releaseService.resolveForNewRun(serviceId).getPin();

        // 服务级停用是显式动作：固定会话同样停止，不因版本固定而绕过
        releaseService.disable(serviceId, serviceService.getService(serviceId).getVersion());
        assertThatThrownBy(() -> releaseService.resolvePinnedRun(pin))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_SERVICE_NOT_PUBLISHED));
        assertThatThrownBy(() -> releaseService.resolveForNewRun(serviceId))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_SERVICE_NOT_PUBLISHED));

        // 重新上线后固定会话恢复可用（固定值没有被改写）
        releaseService.rollback(
                jdbcTemplate.queryForObject(
                        "SELECT id FROM ai_service_release WHERE service_id = ? ORDER BY release_version DESC LIMIT 1",
                        Long.class,
                        serviceId),
                jdbcTemplate.queryForObject(
                        "SELECT version FROM ai_service_release WHERE service_id = ? ORDER BY release_version DESC"
                                + " LIMIT 1",
                        Integer.class,
                        serviceId));
        assertThat(releaseService.resolvePinnedRun(pin).getRelease().getId()).isEqualTo(pin.getReleaseId());
    }
}
