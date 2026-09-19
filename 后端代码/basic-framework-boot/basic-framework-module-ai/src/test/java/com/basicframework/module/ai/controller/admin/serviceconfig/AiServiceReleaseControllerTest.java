package com.basicframework.module.ai.controller.admin.serviceconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServiceEvaluationRespVO;
import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServiceEvaluationSaveReqVO;
import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServiceReleaseActionReqVO;
import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServiceReleaseCreateReqVO;
import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServiceReleaseRespVO;
import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServiceResourceRespVO;
import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServiceRunSnapshotRespVO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseEvaluationDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceResourceDO;
import com.basicframework.module.ai.domain.runtime.AiRunSnapshot;
import com.basicframework.module.ai.service.serviceconfig.AiServiceReleaseService;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceRunSnapshotDTO;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/** S02 发布控制面契约：权限码与 V57 种子一致、响应映射与预检查透出正确。 */
class AiServiceReleaseControllerTest {

    private final AiServiceReleaseService releaseService = mock(AiServiceReleaseService.class);

    private final AiServiceReleaseController controller = new AiServiceReleaseController(releaseService);

    private static AiServiceReleaseDO release() {
        return new AiServiceReleaseDO()
                .setId(21L)
                .setServiceId(9L)
                .setReleaseVersion(3)
                .setModelEndpointId(1L)
                .setEndpointConfigRevision(7)
                .setRequiredCapabilities("TEXT")
                .setEvalThreshold(80)
                .setContentHash("a".repeat(64))
                .setStatus(AiServiceReleaseDO.STATUS_CANDIDATE)
                .setVersion(0);
    }

    @Test
    void createEvaluatePublishAndDisableDelegate() {
        when(releaseService.createCandidate(9L, 2)).thenReturn(21L);
        assertThat(controller
                        .createCandidate(new AiServiceReleaseCreateReqVO()
                                .setServiceId(9L)
                                .setVersion(2))
                        .getData())
                .isEqualTo(21L);

        when(releaseService.recordEvaluation(any())).thenReturn(31L);
        assertThat(controller
                        .evaluate(new AiServiceEvaluationSaveReqVO()
                                .setReleaseId(21L)
                                .setScore(90)
                                .setCaseCount(20))
                        .getData())
                .isEqualTo(31L);
        verify(releaseService).recordEvaluation(any());

        controller.publish(new AiServiceReleaseActionReqVO().setReleaseId(21L).setVersion(0));
        verify(releaseService).publish(21L, 0);

        controller.disable(new AiServiceReleaseActionReqVO().setServiceId(9L).setVersion(4));
        verify(releaseService).disable(9L, 4);

        controller.rollback(new AiServiceReleaseActionReqVO().setReleaseId(21L).setVersion(5));
        verify(releaseService).rollback(21L, 5);
    }

    @Test
    void resolveExposesPinnedVersionsOnly() {
        AiServiceReleaseDO release = release();
        AiServiceResourceDO binding = new AiServiceResourceDO()
                .setId(11L)
                .setServiceId(9L)
                .setReleaseId(21L)
                .setResourceType("REPORT")
                .setResourceKey("report-1")
                .setActions("READ")
                .setStatus(AiServiceResourceDO.STATUS_ACTIVE)
                .setVersion(2);
        when(releaseService.resolveForNewRun(9L))
                .thenReturn(new AiServiceRunSnapshotDTO()
                        .setRelease(release)
                        .setBindings(List.of(binding))
                        .setPin(AiRunSnapshot.of(release, List.of(binding)))
                        .setPinned(false));

        AiServiceRunSnapshotRespVO respVO = controller.resolve(9L).getData();
        assertThat(respVO.getReleaseId()).isEqualTo(21L);
        assertThat(respVO.getReleaseVersion()).isEqualTo(3);
        assertThat(respVO.getContentHash()).isEqualTo("a".repeat(64));
        assertThat(respVO.getModelRevision()).isEqualTo(7);
        assertThat(respVO.isPinned()).isFalse();
        assertThat(respVO.getResources()).singleElement().satisfies(resource -> {
            assertThat(resource.getId()).isEqualTo(11L);
            assertThat(resource.getResourceKey()).isEqualTo("report-1");
            assertThat(resource.getVersion()).isEqualTo(2);
        });
    }

    @Test
    void queriesMapReleasesBindingsEvaluationsAndBlockers() {
        when(releaseService.checkPublishReadiness(21L)).thenReturn(List.of("评测得分未达发布门槛"));
        assertThat(controller.checkPublish(21L).getData()).containsExactly("评测得分未达发布门槛");

        when(releaseService.listReleases(9L)).thenReturn(List.of(release()));
        List<AiServiceReleaseRespVO> releases = controller.listReleases(9L).getData();
        assertThat(releases).singleElement().satisfies(item -> {
            assertThat(item.getReleaseVersion()).isEqualTo(3);
            assertThat(item.getEndpointConfigRevision()).isEqualTo(7);
            assertThat(item.getEvalThreshold()).isEqualTo(80);
            assertThat(item.getStatus()).isEqualTo(AiServiceReleaseDO.STATUS_CANDIDATE);
        });

        when(releaseService.listReleaseBindings(21L))
                .thenReturn(List.of(new AiServiceResourceDO()
                        .setId(11L)
                        .setServiceId(9L)
                        .setReleaseId(21L)
                        .setResourceType("REPORT")
                        .setResourceKey("report-1")
                        .setActions("READ,EXECUTE")
                        .setStatus(AiServiceResourceDO.STATUS_ACTIVE)
                        .setVersion(0)));
        List<AiServiceResourceRespVO> bindings = controller.listBindings(21L).getData();
        assertThat(bindings).singleElement().satisfies(item -> {
            assertThat(item.getReleaseId()).isEqualTo(21L);
            assertThat(item.getActions()).containsExactly("READ", "EXECUTE");
        });

        when(releaseService.listEvaluations(21L))
                .thenReturn(List.of(new AiServiceReleaseEvaluationDO()
                        .setId(31L)
                        .setReleaseId(21L)
                        .setContentHash("a".repeat(64))
                        .setEndpointConfigRevision(7)
                        .setScore(90)
                        .setThreshold(80)
                        .setPassed(true)
                        .setCaseCount(20)));
        List<AiServiceEvaluationRespVO> evaluations =
                controller.listEvaluations(21L).getData();
        assertThat(evaluations).singleElement().satisfies(item -> {
            assertThat(item.getPassed()).isTrue();
            assertThat(item.getThreshold()).isEqualTo(80);
            assertThat(item.getCaseCount()).isEqualTo(20);
        });
    }

    @Test
    void permissionsMatchMigrationSeeds() throws Exception {
        assertThat(permissionOf("createCandidate", AiServiceReleaseCreateReqVO.class))
                .isEqualTo("ai:service:release");
        assertThat(permissionOf("evaluate", AiServiceEvaluationSaveReqVO.class)).isEqualTo("ai:service:evaluate");
        assertThat(permissionOf("publish", AiServiceReleaseActionReqVO.class)).isEqualTo("ai:service:activate");
        assertThat(permissionOf("disable", AiServiceReleaseActionReqVO.class)).isEqualTo("ai:service:activate");
        assertThat(permissionOf("rollback", AiServiceReleaseActionReqVO.class)).isEqualTo("ai:service:activate");
        assertThat(permissionOf("resolve", Long.class)).isEqualTo("ai:service:query");
        assertThat(permissionOf("checkPublish", Long.class)).isEqualTo("ai:service:query");
        assertThat(permissionOf("listReleases", Long.class)).isEqualTo("ai:service:query");
        assertThat(permissionOf("listBindings", Long.class)).isEqualTo("ai:service:query");
        assertThat(permissionOf("listEvaluations", Long.class)).isEqualTo("ai:service:query");
    }

    private static String permissionOf(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = AiServiceReleaseController.class.getMethod(methodName, parameterTypes);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("%s 必须声明服务端权限表达式", methodName).isNotNull();
        return annotation
                .value()
                .replace("@ss.hasPermission(", "")
                .replace(")", "")
                .replace("'", "");
    }
}
