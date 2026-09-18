package com.basicframework.module.ai.service.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import com.basicframework.module.ai.domain.identity.AiExecutionContext;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import com.basicframework.module.ai.service.subject.dto.AiSubjectScopeDTO;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A06 执行上下文重建：每次重建都重新解析、空范围即拒绝，同一线程先后两个任务互不继承。
 */
class AiExecutionContextFactoryImplTest {

    private final AiApplicationService applicationService = mock(AiApplicationService.class);

    private final AiSubjectService subjectService = mock(AiSubjectService.class);

    private final AiExecutionContextFactoryImpl factory =
            new AiExecutionContextFactoryImpl(applicationService, subjectService);

    private static AiApplicationDO enabledApplication() {
        return new AiApplicationDO().setId(5L).setEnabled(true);
    }

    private static AiSubjectScopeDTO scope(String externalUserId, Set<Long> organizations, Set<String> resources) {
        return new AiSubjectScopeDTO()
                .setApplicationId(5L)
                .setSubjectType("USER")
                .setExternalUserId(externalUserId)
                .setDenied(false)
                .setOrganizationIds(organizations)
                .setResourceKeys(resources)
                .setScopeSource("crm-auth")
                .setScopeVersion(3L);
    }

    @Test
    void rebuildReturnsFreshIdentityAndScope() {
        when(applicationService.getApplication(org.mockito.ArgumentMatchers.any()))
                .thenReturn(enabledApplication());
        when(subjectService.resolveScope(eq(5L), eq(AiSubjectType.USER), eq("alice"), any()))
                .thenReturn(scope("alice", Set.of(10L), Set.of("report-1")));

        AiExecutionContext context = factory.rebuild(5L, AiSubjectType.USER, "alice", List.of("report-1"));

        assertThat(context.isDeny()).isFalse();
        assertThat(context.externalUserId()).isEqualTo("alice");
        assertThat(context.allowsOrganization(10L)).isTrue();
        assertThat(context.allowsOrganization(99L)).isFalse();
        assertThat(context.allowsResource("report-1")).isTrue();
        assertThat(context.scopeVersion()).isEqualTo(3L);
    }

    @Test
    void denyScopeStopsTheTaskInsteadOfWidening() {
        when(applicationService.getApplication(org.mockito.ArgumentMatchers.any()))
                .thenReturn(enabledApplication());
        when(subjectService.resolveScope(eq(5L), eq(AiSubjectType.USER), eq("alice"), any()))
                .thenReturn(new AiSubjectScopeDTO().setDenied(true).setDenyReason("EMPTY_SCOPE"));

        assertThatThrownBy(() -> factory.rebuild(5L, AiSubjectType.USER, "alice", List.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_AUTHORIZATION_DENIED.getCode());
    }

    @Test
    void revokedApplicationStopsRebuildEvenWhenSubjectStillActive() {
        when(applicationService.getApplication(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AiApplicationDO().setId(5L).setEnabled(false));

        assertThatThrownBy(() -> factory.rebuild(5L, AiSubjectType.USER, "alice", List.of("report-1")))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_AUTHORIZATION_DENIED.getCode());
    }

    @Test
    void twoTasksOnSameThreadDoNotInheritPreviousIdentityOrScope() {
        when(applicationService.getApplication(org.mockito.ArgumentMatchers.any()))
                .thenReturn(enabledApplication());
        when(subjectService.resolveScope(eq(5L), eq(AiSubjectType.USER), eq("alice"), any()))
                .thenReturn(scope("alice", Set.of(10L), Set.of("report-1")));
        when(subjectService.resolveScope(eq(6L), eq(AiSubjectType.USER), eq("bob"), any()))
                .thenReturn(scope("bob", Set.of(20L), Set.of("report-2")));

        AiExecutionContext first = factory.rebuild(5L, AiSubjectType.USER, "alice", List.of("report-1"));
        AiExecutionContext second = factory.rebuild(6L, AiSubjectType.USER, "bob", List.of("report-2"));

        // 第二个任务只看到自己的身份与范围：没有任何线程级残留
        assertThat(second.applicationId()).isEqualTo(6L);
        assertThat(second.externalUserId()).isEqualTo("bob");
        assertThat(second.allowsResource("report-2")).isTrue();
        assertThat(second.allowsResource("report-1")).as("不得继承上一任务的对象范围").isFalse();
        assertThat(second.organizationIds()).containsExactly(20L);
        assertThat(first.allowsResource("report-2")).isFalse();
    }

    @Test
    void rebuildRejectsMissingIdentityFacts() {
        when(applicationService.getApplication(org.mockito.ArgumentMatchers.any()))
                .thenReturn(enabledApplication());

        assertThatThrownBy(() -> factory.rebuild(null, AiSubjectType.USER, "alice", List.of()))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> factory.rebuild(5L, null, "alice", List.of())).isInstanceOf(ServiceException.class);
    }
}
