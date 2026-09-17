package com.basicframework.module.ai.service.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import com.basicframework.module.ai.dal.dataobject.grant.AiResourceGrantDO;
import com.basicframework.module.ai.dal.mysql.grant.AiResourceGrantMapper;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.authorization.dto.AiAuthorizationDecisionDTO;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import com.basicframework.module.ai.service.subject.dto.AiSubjectScopeDTO;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A03 统一授权：应用/主体/范围/授权/动作五要素缺一不可；不同资源类型同 ID 不串权；
 * 撤销后授权版本变化使旧指纹失效（历史产物必须重新鉴权）。
 */
class AiAuthorizationServiceImplTest {

    private AiApplicationService applicationService;

    private AiSubjectService subjectService;

    private AiResourceGrantMapper grantMapper;

    private AiAuthorizationServiceImpl service;

    @BeforeEach
    void setUp() {
        applicationService = mock(AiApplicationService.class);
        subjectService = mock(AiSubjectService.class);
        grantMapper = mock(AiResourceGrantMapper.class);
        service = new AiAuthorizationServiceImpl(applicationService, subjectService, grantMapper);
    }

    private static AiApplicationDO application(boolean enabled) {
        return new AiApplicationDO().setId(5L).setAppCode("crm-portal").setEnabled(enabled);
    }

    private static AiSubjectScopeDTO scope() {
        return new AiSubjectScopeDTO()
                .setApplicationId(5L)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setDenied(false)
                .setOrganizationIds(Set.of(10L))
                .setResourceKeys(Set.of("report-1"))
                .setScopeSource("crm-auth")
                .setScopeVersion(3L);
    }

    private static AiResourceGrantDO grant(String resourceType, String resourceKey, String actions, long revision) {
        return new AiResourceGrantDO()
                .setId(1L)
                .setApplicationId(5L)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setResourceType(resourceType)
                .setResourceKey(resourceKey)
                .setActions(actions)
                .setStatus(AiResourceGrantDO.STATUS_ACTIVE)
                .setAuthzRevision(revision);
    }

    @Test
    void allowsOnlyWhenAllFiveElementsMatch() {
        when(applicationService.getApplication(5L)).thenReturn(application(true));
        when(subjectService.resolveScope(eq(5L), any(), eq("alice"), any())).thenReturn(scope());
        when(grantMapper.selectGrant(5L, "USER", "alice", "REPORT", "report-1"))
                .thenReturn(grant("REPORT", "report-1", "READ,EXECUTE", 4L));

        AiAuthorizationDecisionDTO decision = service.authorize(
                5L, "USER", "alice", AiResourceType.REPORT, "report-1", AiAction.READ, List.of("report-1"));

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.getAuthzRevision()).isEqualTo(4L);
        assertThat(decision.getGrantedActions()).containsExactlyInAnyOrder("READ", "EXECUTE");
        assertThat(decision.getScopeFingerprint()).hasSize(64);
    }

    @Test
    void deniesWhenApplicationDisabledOrSubjectScopeDenied() {
        when(applicationService.getApplication(5L)).thenReturn(application(false));
        assertThat(service.authorize(5L, "USER", "alice", AiResourceType.REPORT, "report-1", AiAction.READ, List.of())
                        .getDenyReason())
                .isEqualTo("APPLICATION_DISABLED");
        verify(grantMapper, never()).selectGrant(any(), anyString(), anyString(), anyString(), anyString());

        when(applicationService.getApplication(5L)).thenReturn(application(true));
        when(subjectService.resolveScope(eq(5L), any(), eq("alice"), any()))
                .thenReturn(new AiSubjectScopeDTO().setDenied(true).setDenyReason("EMPTY_SCOPE"));
        assertThat(service.authorize(5L, "USER", "alice", AiResourceType.REPORT, "report-1", AiAction.READ, List.of())
                        .getDenyReason())
                .isEqualTo("SUBJECT_SCOPE_DENIED");
    }

    @Test
    void deniesWhenGrantMissingForThatResourceTypeEvenIfSameIdExistsElsewhere() {
        when(applicationService.getApplication(5L)).thenReturn(application(true));
        when(subjectService.resolveScope(eq(5L), any(), eq("alice"), any())).thenReturn(scope());
        // 同 ID 但不同类型：查询按类型精确匹配，不会命中
        when(grantMapper.selectGrant(5L, "USER", "alice", "FILE", "report-1")).thenReturn(null);

        assertThat(service.authorize(5L, "USER", "alice", AiResourceType.FILE, "report-1", AiAction.READ, List.of())
                        .getDenyReason())
                .isEqualTo("GRANT_NOT_FOUND");
    }

    @Test
    void deniesWhenActionNotInWhiteListOrGrantRevoked() {
        when(applicationService.getApplication(5L)).thenReturn(application(true));
        when(subjectService.resolveScope(eq(5L), any(), eq("alice"), any())).thenReturn(scope());
        when(grantMapper.selectGrant(5L, "USER", "alice", "REPORT", "report-1"))
                .thenReturn(grant("REPORT", "report-1", "READ", 1L));

        assertThat(service.authorize(5L, "USER", "alice", AiResourceType.REPORT, "report-1", AiAction.EXPORT, List.of())
                        .getDenyReason())
                .isEqualTo("ACTION_NOT_GRANTED");

        when(grantMapper.selectGrant(5L, "USER", "alice", "REPORT", "report-1"))
                .thenReturn(grant("REPORT", "report-1", "READ", 2L).setStatus(AiResourceGrantDO.STATUS_REVOKED));
        assertThat(service.authorize(5L, "USER", "alice", AiResourceType.REPORT, "report-1", AiAction.READ, List.of())
                        .getDenyReason())
                .isEqualTo("GRANT_NOT_FOUND");
    }

    @Test
    void historicalReauthorizationFailsWhenScopeFingerprintChanges() {
        when(applicationService.getApplication(5L)).thenReturn(application(true));
        when(subjectService.resolveScope(eq(5L), any(), eq("alice"), any()))
                .thenReturn(scope())
                .thenReturn(scope().setOrganizationIds(Set.of(10L, 11L)).setScopeVersion(4L));
        when(grantMapper.selectGrant(5L, "USER", "alice", "REPORT", "report-1"))
                .thenReturn(grant("REPORT", "report-1", "READ", 1L))
                .thenReturn(grant("REPORT", "report-1", "READ", 2L));

        String fingerprint = service.authorize(
                        5L, "USER", "alice", AiResourceType.REPORT, "report-1", AiAction.READ, List.of())
                .getScopeFingerprint();

        // 范围或授权版本变化后，同一份旧指纹不能再通过
        AiAuthorizationDecisionDTO changed = service.reauthorizeHistorical(
                5L, "USER", "alice", AiResourceType.REPORT, "report-1", AiAction.READ, fingerprint);
        assertThat(changed.isAllowed()).isFalse();
        assertThat(changed.getDenyReason()).isEqualTo("SCOPE_FINGERPRINT_CHANGED");

        // 指纹一致时允许（数据集仍获授权）
        AiAuthorizationDecisionDTO current =
                service.authorize(5L, "USER", "alice", AiResourceType.REPORT, "report-1", AiAction.READ, List.of());
        assertThat(service.reauthorizeHistorical(
                                5L,
                                "USER",
                                "alice",
                                AiResourceType.REPORT,
                                "report-1",
                                AiAction.READ,
                                current.getScopeFingerprint())
                        .isAllowed())
                .isTrue();
    }
}
