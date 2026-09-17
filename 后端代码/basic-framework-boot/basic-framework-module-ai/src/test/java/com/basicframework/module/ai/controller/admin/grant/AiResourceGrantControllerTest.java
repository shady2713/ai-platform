package com.basicframework.module.ai.controller.admin.grant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.grant.vo.AiResourceGrantPageReqVO;
import com.basicframework.module.ai.controller.admin.grant.vo.AiResourceGrantRespVO;
import com.basicframework.module.ai.controller.admin.grant.vo.AiResourceGrantSaveReqVO;
import com.basicframework.module.ai.controller.admin.grant.vo.AiResourceGrantUpdateReqVO;
import com.basicframework.module.ai.dal.dataobject.grant.AiResourceGrantDO;
import com.basicframework.module.ai.service.authorization.AiResourceGrantService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/** A03 授权管理 API 契约：权限码与 V52 种子一致、动作白名单原样委派、响应含授权版本。 */
class AiResourceGrantControllerTest {

    private final AiResourceGrantService grantService = mock(AiResourceGrantService.class);

    private final AiResourceGrantController controller = new AiResourceGrantController(grantService);

    private static AiResourceGrantDO grant() {
        return new AiResourceGrantDO()
                .setId(3L)
                .setApplicationId(5L)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setResourceType("REPORT")
                .setResourceKey("report-1")
                .setActions("EXECUTE,READ")
                .setStatus(AiResourceGrantDO.STATUS_ACTIVE)
                .setAuthzRevision(4L)
                .setVersion(1);
    }

    @Test
    void createUpdateRevokeDelegateWithWhiteListActions() {
        when(grantService.createGrant(any(), any(), any(), any(), any(), any())).thenReturn(3L);
        AiResourceGrantSaveReqVO saveReqVO = new AiResourceGrantSaveReqVO()
                .setApplicationId(5L)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setResourceType("REPORT")
                .setResourceKey("report-1")
                .setActions(Set.of("READ", "EXECUTE"));

        assertThat(controller.createGrant(saveReqVO).getData()).isEqualTo(3L);
        verify(grantService).createGrant(5L, "USER", "alice", "REPORT", "report-1", Set.of("READ", "EXECUTE"));

        AiResourceGrantUpdateReqVO updateReqVO =
                new AiResourceGrantUpdateReqVO().setId(3L).setVersion(1).setActions(Set.of("READ"));
        controller.updateGrant(updateReqVO);
        verify(grantService).updateGrant(3L, 1, Set.of("READ"));

        controller.revokeGrant(3L, 1);
        verify(grantService).revokeGrant(3L, 1);
    }

    @Test
    void detailAndPageExposeWhiteListAndAuthzRevision() {
        when(grantService.getGrant(3L)).thenReturn(grant());
        when(grantService.getGrantPage(any(), any(), any(), any(), any()))
                .thenReturn(new PageResult<>(List.of(grant()), 1L));

        AiResourceGrantRespVO detail = controller.getGrant(3L).getData();
        assertThat(detail.getActions()).containsExactlyInAnyOrder("READ", "EXECUTE");
        assertThat(detail.getAuthzRevision()).isEqualTo(4L);
        assertThat(detail.getStatus()).isEqualTo(AiResourceGrantDO.STATUS_ACTIVE);

        AiResourceGrantPageReqVO pageReqVO = new AiResourceGrantPageReqVO();
        pageReqVO.setApplicationId(5L);
        assertThat(controller.getGrantPage(pageReqVO).getData().getList()).hasSize(1);
    }

    @Test
    void permissionsMatchMigrationSeeds() throws Exception {
        assertThat(permissionOf("createGrant", AiResourceGrantSaveReqVO.class)).isEqualTo("ai:grant:create");
        assertThat(permissionOf("updateGrant", AiResourceGrantUpdateReqVO.class))
                .isEqualTo("ai:grant:update");
        assertThat(permissionOf("revokeGrant", Long.class, Integer.class)).isEqualTo("ai:grant:revoke");
        assertThat(permissionOf("getGrant", Long.class)).isEqualTo("ai:grant:query");
        assertThat(permissionOf("getGrantPage", AiResourceGrantPageReqVO.class)).isEqualTo("ai:grant:query");
    }

    private static String permissionOf(String methodName, Class<?>... parameterTypes) throws Exception {
        PreAuthorize annotation = AiResourceGrantController.class
                .getMethod(methodName, parameterTypes)
                .getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("%s 必须声明服务端权限表达式", methodName).isNotNull();
        return annotation
                .value()
                .replace("@ss.hasPermission(", "")
                .replace(")", "")
                .replace("'", "");
    }
}
