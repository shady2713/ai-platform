package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseDO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.identity.SubjectScope;
import com.basicframework.module.ai.domain.identity.SubjectScopeResolver;
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
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceSaveDTO;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * S02 发布预检查与不可变版本端到端（真实 MySQL）：候选冻结、评测绑定、别名切换、
 * 失败发布不改当前 active、资源禁用后新运行拒绝、发布版本不可原地修改。
 */
@Import(AiServiceReleaseIT.ResolverConfiguration.class)
class AiServiceReleaseIT extends AbstractPersistenceIntegrationTest {

    @TestConfiguration
    static class ResolverConfiguration {

        @Bean
        SubjectScopeResolver releaseScopeResolver() {
            return request -> Optional.of(new SubjectScope(
                    Set.of(10L),
                    Set.of("report-1", "report-2", "kb-1"),
                    request.scopeSource(),
                    request.scopeVersion()));
        }
    }

    private static final String APP_CODE = "it-release-app";

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
        List<Long> endpointIds = jdbcTemplate.queryForList(
                "SELECT id FROM ai_model_endpoint WHERE name = ?", Long.class, "it-release-endpoint");
        for (Long endpointId : endpointIds) {
            jdbcTemplate.update("DELETE FROM ai_model_probe WHERE endpoint_id = ?", endpointId);
            jdbcTemplate.update("DELETE FROM ai_model_endpoint_revision WHERE endpoint_id = ?", endpointId);
            jdbcTemplate.update("DELETE FROM ai_model_endpoint WHERE id = ?", endpointId);
        }
    }

    private Long createApplication() {
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(APP_CODE)
                .setName("IT 发布应用")
                .setOrigins(List.of("https://release.example.com")));
        Long appId = issue.getApplication().getId();
        applicationService.updateStatus(appId, 0, true);
        subjectService.syncSubject(appId, AiSubjectType.APP, null, "IT 发布应用", "it-owner", 1L);
        return appId;
    }

    private Long createProbedEndpoint() {
        AiModelEndpointSaveDTO saveDTO = new AiModelEndpointSaveDTO();
        saveDTO.setName("it-release-endpoint");
        saveDTO.setProvider("openai_compatible");
        saveDTO.setBaseUrl("https://it-release.example.com/v1");
        saveDTO.setModelId("gpt-4o-mini");
        saveDTO.setCapabilities(List.of("TEXT", "STRUCTURED_OUTPUT"));
        saveDTO.setCredential("sk-it-release");
        Long endpointId = endpointService.createEndpoint(saveDTO);
        var endpoint = endpointService.getEndpoint(endpointId);
        endpointService.updateEndpointStatus(endpointId, endpoint.getVersion(), true);
        var revision = endpointService.getRevisions(endpointId).get(0);
        insertProbe(endpointId, revision.getRevision(), "TEXT", "SUPPORTED");
        insertProbe(endpointId, revision.getRevision(), "CONNECTIVITY", "SUPPORTED");
        return endpointId;
    }

    private void insertProbe(Long endpointId, int configRevision, String kind, String status) {
        jdbcTemplate.update(
                "INSERT INTO ai_model_probe (endpoint_id, config_revision, credential_revision, probe_kind, status,"
                        + " detail_code, latency_ms, creator, updater) VALUES (?, ?, 1, ?, ?, ?, 5, 'it', 'it')",
                endpointId,
                configRevision,
                kind,
                status,
                "SUPPORTED".equals(status) ? null : "TIMEOUT");
    }

    private static AiServiceSaveDTO draft(Long appId, Long endpointId, int threshold) {
        return new AiServiceSaveDTO()
                .setAppId(appId)
                .setCode("order-qa")
                .setName("订单问答")
                .setModelEndpointId(endpointId)
                .setPromptTemplate("你是订单助手")
                .setInputSchema("{\"type\":\"object\"}")
                .setOutputSchema("{\"type\":\"object\"}")
                .setRequiredCapabilities(List.of("TEXT"))
                .setRunSubjectType("USER")
                .setEvalThreshold(threshold);
    }

    /** 建好草稿 + 资源授权与绑定 + 标记可发布，返回服务编号。 */
    private Long prepareReadyService(Long appId, Long endpointId, int threshold) {
        grantService.createGrant(appId, "APP", null, "REPORT", "report-1", Set.of("READ"));
        Long serviceId = serviceService.createDraft(draft(appId, endpointId, threshold));
        serviceService.bindResource(new AiServiceResourceSaveDTO()
                .setServiceId(serviceId)
                .setResourceType("REPORT")
                .setResourceKey("report-1")
                .setActions(List.of("READ")));
        var service = serviceService.getService(serviceId);
        serviceService.markReady(serviceId, service.getVersion());
        return serviceId;
    }

    private static void assertCode(
            Throwable throwable, com.basicframework.framework.common.exception.ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void releaseLifecycleFreezesContentAndSwitchesAlias() {
        Long appId = createApplication();
        Long endpointId = createProbedEndpoint();
        Long serviceId = prepareReadyService(appId, endpointId, 80);

        // 冻结候选：端点配置版本、门槛与资源快照都固定下来
        var ready = serviceService.getService(serviceId);
        Long releaseId = releaseService.createCandidate(serviceId, ready.getVersion());
        AiServiceReleaseDO candidate = jdbcTemplate.queryForObject(
                "SELECT release_version, endpoint_config_revision, eval_threshold, content_hash, status, version"
                        + " FROM ai_service_release WHERE id = ?",
                (rs, rowNum) -> {
                    AiServiceReleaseDO loaded = new AiServiceReleaseDO();
                    loaded.setId(releaseId);
                    loaded.setReleaseVersion(rs.getInt("release_version"));
                    loaded.setEndpointConfigRevision(rs.getInt("endpoint_config_revision"));
                    loaded.setEvalThreshold(rs.getInt("eval_threshold"));
                    loaded.setContentHash(rs.getString("content_hash"));
                    loaded.setStatus(rs.getString("status"));
                    loaded.setVersion(rs.getInt("version"));
                    return loaded;
                },
                releaseId);
        assertThat(candidate.getReleaseVersion()).isEqualTo(1);
        assertThat(candidate.getEvalThreshold()).isEqualTo(80);
        assertThat(candidate.getStatus()).isEqualTo(AiServiceReleaseDO.STATUS_CANDIDATE);
        assertThat(releaseService.listReleaseBindings(releaseId))
                .as("候选冻结时复制草稿绑定为版本快照")
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.getReleaseId()).isEqualTo(releaseId);
                    assertThat(binding.getResourceKey()).isEqualTo("report-1");
                });

        // 未评测不得发布
        assertThatThrownBy(() -> releaseService.publish(releaseId, candidate.getVersion()))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_SERVICE_EVAL_MISSING));

        // 评测低于门槛：记录为未通过，发布仍拒绝
        releaseService.recordEvaluation(new AiServiceEvaluationSaveDTO()
                .setReleaseId(releaseId)
                .setScore(60)
                .setCaseCount(10));
        assertThat(releaseService.listEvaluations(releaseId))
                .singleElement()
                .satisfies(evaluation -> assertThat(evaluation.getPassed()).isFalse());
        assertThatThrownBy(() -> releaseService.publish(releaseId, candidate.getVersion()))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_SERVICE_EVAL_BELOW_THRESHOLD));

        // 达标后发布：别名切换
        releaseService.recordEvaluation(new AiServiceEvaluationSaveDTO()
                .setReleaseId(releaseId)
                .setScore(92)
                .setCaseCount(10));
        releaseService.publish(releaseId, candidate.getVersion());
        var active = releaseService.resolveForNewRun(serviceId).getRelease();
        assertThat(active.getId()).isEqualTo(releaseId);
        assertThat(active.getStatus()).isEqualTo(AiServiceReleaseDO.STATUS_ACTIVE);
        assertThat(active.getPromptTemplate()).as("运行时用冻结内容而不是草稿").isEqualTo("你是订单助手");
        assertThat(active.getEndpointConfigRevision()).isEqualTo(candidate.getEndpointConfigRevision());

        // 发布版本不可原地修改：发布只推进状态与版本，内容列原样保留
        Map<String, Object> afterPublish = jdbcTemplate.queryForMap(
                "SELECT prompt_template, input_schema, output_schema, required_capabilities, content_hash,"
                        + " endpoint_config_revision, eval_threshold FROM ai_service_release WHERE id = ?",
                releaseId);
        assertThat(afterPublish.get("prompt_template")).isEqualTo("你是订单助手");
        assertThat(afterPublish.get("content_hash")).isEqualTo(candidate.getContentHash());
        assertThat(afterPublish.get("eval_threshold")).isEqualTo(80);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT version FROM ai_service_release WHERE id = ?", Integer.class, releaseId))
                .as("状态切换只推进乐观锁版本")
                .isEqualTo(candidate.getVersion() + 1);

        // 停用后新运行不再解析到该服务
        releaseService.disable(serviceId, serviceService.getService(serviceId).getVersion());
        assertThatThrownBy(() -> releaseService.resolveForNewRun(serviceId))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_SERVICE_NOT_PUBLISHED));
        assertThatThrownBy(() -> releaseService.disable(
                        serviceId, serviceService.getService(serviceId).getVersion()))
                .as("没有生效版本时停用返回状态冲突")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_STATE_CONFLICT));
    }

    @Test
    void failedPublishKeepsCurrentActiveAndReleasedBindingBlocksNewRuns() {
        Long appId = createApplication();
        Long endpointId = createProbedEndpoint();
        Long serviceId = prepareReadyService(appId, endpointId, 0);

        // 第一版发布
        var ready = serviceService.getService(serviceId);
        Long firstReleaseId = releaseService.createCandidate(serviceId, ready.getVersion());
        releaseService.recordEvaluation(new AiServiceEvaluationSaveDTO()
                .setReleaseId(firstReleaseId)
                .setScore(90)
                .setCaseCount(5));
        releaseService.publish(firstReleaseId, 0);
        assertThat(releaseService.resolveForNewRun(serviceId).getRelease().getId())
                .isEqualTo(firstReleaseId);

        // 第二版：内容变更后重新冻结（修订号递增），并把门槛抬到 80；评测不达标 → 发布失败
        var current = serviceService.getService(serviceId);
        serviceService.updateDraft(draft(appId, endpointId, 80)
                .setId(serviceId)
                .setVersion(current.getVersion())
                .setPromptTemplate("你是订单助手 v2"));
        var edited = serviceService.getService(serviceId);
        serviceService.markReady(serviceId, edited.getVersion());
        var readyAgain = serviceService.getService(serviceId);
        Long secondReleaseId = releaseService.createCandidate(serviceId, readyAgain.getVersion());
        releaseService.recordEvaluation(new AiServiceEvaluationSaveDTO()
                .setReleaseId(secondReleaseId)
                .setScore(10)
                .setCaseCount(5));
        assertThatThrownBy(() -> releaseService.publish(secondReleaseId, 0))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_SERVICE_EVAL_BELOW_THRESHOLD));

        // 失败发布不改变当前 active：新运行仍解析到第一版
        var stillActive = releaseService.resolveForNewRun(serviceId).getRelease();
        assertThat(stillActive.getId()).isEqualTo(firstReleaseId);
        assertThat(stillActive.getStatus()).isEqualTo(AiServiceReleaseDO.STATUS_ACTIVE);
        assertThat(jdbcTemplate.queryForList(
                        "SELECT id FROM ai_service_release WHERE service_id = ? AND status = 'ACTIVE'",
                        Long.class,
                        serviceId))
                .as("同一服务最多一条 ACTIVE")
                .containsExactly(firstReleaseId);

        // 资源禁用（解绑生效版本的绑定）后新运行拒绝
        Long activeBindingId =
                releaseService.listReleaseBindings(firstReleaseId).get(0).getId();
        serviceService.unbindResource(activeBindingId, 0);
        assertThatThrownBy(() -> releaseService.resolveForNewRun(serviceId))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_SERVICE_RESOURCE_UNAVAILABLE));

        // 该候选自身的绑定被解除后，即使评测达标也不能发布，且当前 active 不被顶掉
        Long secondBindingId =
                releaseService.listReleaseBindings(secondReleaseId).get(0).getId();
        serviceService.unbindResource(secondBindingId, 0);
        releaseService.recordEvaluation(new AiServiceEvaluationSaveDTO()
                .setReleaseId(secondReleaseId)
                .setScore(99)
                .setCaseCount(5));
        assertThatThrownBy(() -> releaseService.publish(secondReleaseId, 0))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_SERVICE_RESOURCE_UNAVAILABLE));
        assertThat(jdbcTemplate.queryForList(
                        "SELECT id FROM ai_service_release WHERE service_id = ? AND status = 'ACTIVE'",
                        Long.class,
                        serviceId))
                .as("失败的发布不改变当前 active")
                .containsExactly(firstReleaseId);
        assertThat(releaseService.listEvaluations(secondReleaseId)).hasSize(2);
    }

    @Test
    void evaluationIsBoundToContentOfTheFrozenCandidate() {
        Long appId = createApplication();
        Long endpointId = createProbedEndpoint();
        Long serviceId = prepareReadyService(appId, endpointId, 0);

        var ready = serviceService.getService(serviceId);
        Long firstReleaseId = releaseService.createCandidate(serviceId, ready.getVersion());
        releaseService.recordEvaluation(new AiServiceEvaluationSaveDTO()
                .setReleaseId(firstReleaseId)
                .setScore(95)
                .setCaseCount(5));

        // 新建候选（内容相同，摘要相同）：旧报告对相同内容依然有效
        var unchanged = serviceService.getService(serviceId);
        Long sameContentReleaseId = releaseService.createCandidate(serviceId, unchanged.getVersion());
        var first = jdbcTemplate.queryForObject(
                "SELECT content_hash FROM ai_service_release WHERE id = ?", String.class, firstReleaseId);
        var second = jdbcTemplate.queryForObject(
                "SELECT content_hash FROM ai_service_release WHERE id = ?", String.class, sameContentReleaseId);
        assertThat(second).as("相同内容产生相同摘要").isEqualTo(first);

        // 内容变化后的新候选：旧版本的评测报告不参与它的发布判定
        var current = serviceService.getService(serviceId);
        serviceService.updateDraft(draft(appId, endpointId, 0)
                .setId(serviceId)
                .setVersion(current.getVersion())
                .setPromptTemplate("你是订单助手 v3"));
        var edited = serviceService.getService(serviceId);
        serviceService.markReady(serviceId, edited.getVersion());
        var readyAgain = serviceService.getService(serviceId);
        Long changedReleaseId = releaseService.createCandidate(serviceId, readyAgain.getVersion());
        String changedHash = jdbcTemplate.queryForObject(
                "SELECT content_hash FROM ai_service_release WHERE id = ?", String.class, changedReleaseId);
        assertThat(changedHash).as("内容变化后摘要必须变化").isNotEqualTo(first);
        assertThat(releaseService.listEvaluations(changedReleaseId))
                .as("新候选没有可用的评测证据")
                .isEmpty();
        assertThat(releaseService.checkPublishReadiness(changedReleaseId))
                .containsExactly(AiErrorCodeConstants.AI_SERVICE_EVAL_MISSING.getMsg());
    }
}
