package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
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
import com.basicframework.module.ai.service.serviceconfig.AiServiceService;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceResourceSaveDTO;
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
 * S01 服务草稿端到端（真实 MySQL）：草稿校验、越权绑定拒绝、能力门禁与并发编辑冲突。
 *
 * <p>能力门禁依赖 M04 的探测结论：用例直接写入探测记录（模拟已确认能力），
 * 从而验证"声明 ∩ 确认 = 可发布"的判定链路，而不需要真实模型端点。
 */
@Import(AiServiceDraftIT.ResolverConfiguration.class)
class AiServiceDraftIT extends AbstractPersistenceIntegrationTest {

    @TestConfiguration
    static class ResolverConfiguration {

        @Bean
        SubjectScopeResolver serviceScopeResolver() {
            return request -> Optional.of(new SubjectScope(
                    Set.of(10L), Set.of("report-1", "kb-1"), request.scopeSource(), request.scopeVersion()));
        }
    }

    private static final String APP_CODE = "it-service-app";

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

    @AfterEach
    void cleanUp() {
        List<Long> appIds =
                jdbcTemplate.queryForList("SELECT id FROM ai_application WHERE app_code = ?", Long.class, APP_CODE);
        for (Long appId : appIds) {
            List<Long> serviceIds =
                    jdbcTemplate.queryForList("SELECT id FROM ai_service WHERE app_id = ?", Long.class, appId);
            for (Long serviceId : serviceIds) {
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
                "SELECT id FROM ai_model_endpoint WHERE name = ?", Long.class, "it-service-endpoint");
        for (Long endpointId : endpointIds) {
            jdbcTemplate.update("DELETE FROM ai_model_probe WHERE endpoint_id = ?", endpointId);
            jdbcTemplate.update("DELETE FROM ai_model_endpoint_revision WHERE endpoint_id = ?", endpointId);
            jdbcTemplate.update("DELETE FROM ai_model_endpoint WHERE id = ?", endpointId);
        }
    }

    private Long createApplication() {
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(APP_CODE)
                .setName("IT 服务应用")
                .setOrigins(List.of("https://service.example.com")));
        Long appId = issue.getApplication().getId();
        applicationService.updateStatus(appId, 0, true);
        // 服务以应用身份运行：登记 APP 主体，绑定资源时由 A03 按该主体判定
        subjectService.syncSubject(appId, AiSubjectType.APP, null, "IT 服务应用", "it-owner", 1L);
        return appId;
    }

    /** 建一个端点并写入探测结论（CONNECTIVITY/TEXT 为 SUPPORTED）。 */
    private Long createProbedEndpoint(boolean textSupported) {
        AiModelEndpointSaveDTO saveDTO = new AiModelEndpointSaveDTO();
        saveDTO.setName("it-service-endpoint");
        saveDTO.setProvider("openai_compatible");
        saveDTO.setBaseUrl("https://it-service.example.com/v1");
        saveDTO.setModelId("gpt-4o-mini");
        saveDTO.setCapabilities(List.of("TEXT", "STRUCTURED_OUTPUT"));
        saveDTO.setCredential("sk-it-service");
        Long endpointId = endpointService.createEndpoint(saveDTO);
        var endpoint = endpointService.getEndpoint(endpointId);
        endpointService.updateEndpointStatus(endpointId, endpoint.getVersion(), true);
        var revision = endpointService.getRevisions(endpointId).get(0);
        insertProbe(endpointId, revision.getRevision(), "TEXT", textSupported ? "SUPPORTED" : "FAILED");
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

    private static AiServiceSaveDTO draft(Long appId, Long endpointId) {
        return new AiServiceSaveDTO()
                .setAppId(appId)
                .setCode("order-qa")
                .setName("订单问答")
                .setModelEndpointId(endpointId)
                .setPromptTemplate("你是订单助手")
                .setInputSchema("{\"type\":\"object\"}")
                .setRequiredCapabilities(List.of("TEXT"))
                .setRunSubjectType("USER");
    }

    @Test
    void draftLifecycleEnforcesBindingAndCapabilityRules() {
        Long appId = createApplication();
        Long endpointId = createProbedEndpoint(true);

        // 草稿创建
        Long serviceId = serviceService.createDraft(draft(appId, endpointId));
        assertThat(serviceService.getService(serviceId).getStatus()).isEqualTo("DRAFT");

        // 越权绑定拒绝：应用尚未获得 report-1 授权
        assertThatThrownBy(() -> serviceService.bindResource(new AiServiceResourceSaveDTO()
                        .setServiceId(serviceId)
                        .setResourceType("REPORT")
                        .setResourceKey("report-1")
                        .setActions(List.of("READ"))))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_AUTHORIZATION_DENIED.getCode());

        // 授予应用级授权后绑定成功
        grantService.createGrant(appId, "APP", null, "REPORT", "report-1", Set.of("READ"));
        Long bindingId = serviceService.bindResource(new AiServiceResourceSaveDTO()
                .setServiceId(serviceId)
                .setResourceType("REPORT")
                .setResourceKey("report-1")
                .setActions(List.of("READ")));
        assertThat(serviceService.listDraftBindings(serviceId)).hasSize(1);

        // 能力门禁：所需能力 TEXT 已被探测确认 → 允许标记可发布
        assertThat(serviceService.checkCapabilities(serviceId).isSatisfied()).isTrue();
        var service = serviceService.getService(serviceId);
        serviceService.markReady(serviceId, service.getVersion());
        assertThat(serviceService.getService(serviceId).getStatus()).isEqualTo("READY");

        // 并发编辑：用过期版本更新必须 409
        var current = serviceService.getService(serviceId);
        assertThatThrownBy(() -> serviceService.updateDraft(
                        draft(appId, endpointId).setId(serviceId).setVersion(current.getVersion() - 1)))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_STATE_CONFLICT.getCode());

        // 配置变更后回到 DRAFT 且修订号递增
        serviceService.updateDraft(draft(appId, endpointId).setId(serviceId).setVersion(current.getVersion()));
        var updated = serviceService.getService(serviceId);
        assertThat(updated.getStatus()).isEqualTo("DRAFT");
        assertThat(updated.getDraftRevision()).isEqualTo(current.getDraftRevision() + 1);

        // 分页与版本列表（真实 Mapper 默认方法路径）
        assertThat(serviceService
                        .getServicePage(new com.basicframework.framework.common.pojo.PageParam(), appId, "order", null)
                        .getList())
                .hasSize(1);
        assertThat(serviceService.listReleases(serviceId)).isEmpty();

        // 解绑后才能删除
        serviceService.unbindResource(bindingId, 0);
        serviceService.deleteDraft(serviceId, updated.getVersion());
        assertThat(jdbcTemplate.queryForList(
                        "SELECT id FROM ai_service WHERE id = ? AND deleted = 0", Long.class, serviceId))
                .isEmpty();
    }

    @Test
    void capabilityGateBlocksReadyWhenProbeDidNotConfirm() {
        Long appId = createApplication();
        Long endpointId = createProbedEndpoint(false);
        Long serviceId = serviceService.createDraft(draft(appId, endpointId));

        var capability = serviceService.checkCapabilities(serviceId);
        assertThat(capability.isSatisfied()).isFalse();
        assertThat(capability.getMissing()).containsExactly("TEXT");

        var service = serviceService.getService(serviceId);
        assertThatThrownBy(() -> serviceService.markReady(serviceId, service.getVersion()))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_MODEL_CAPABILITY_UNSUPPORTED.getCode());
        // 能力不足时草稿仍可保存（未被标记为可发布）
        assertThat(serviceService.getService(serviceId).getStatus()).isEqualTo("DRAFT");
        assertThat(serviceService.checkCapabilities(serviceId).getPublishable())
                .as("未确认能力不进可发布范围")
                .doesNotContain("TEXT");
    }
}
