package com.basicframework.module.ai.service.subject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.subject.AiSubjectDO;
import com.basicframework.module.ai.dal.mysql.subject.AiSubjectMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.identity.SubjectScope;
import com.basicframework.module.ai.domain.identity.SubjectScopeResolver;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.subject.dto.AiSubjectScopeDTO;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * A02 主体与范围：唯一键语义、业务侧撤销同步，以及 DENY 的五条路径
 * （主体缺失、解析器缺失、解析器失败、空集合、超预算）都不放行为全部数据。
 */
class AiSubjectServiceImplTest {

    private AiSubjectMapper subjectMapper;

    private ObjectProvider<SubjectScopeResolver> provider;

    private AiSubjectServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        subjectMapper = mock(AiSubjectMapper.class);
        provider = mock(ObjectProvider.class);
        service = new AiSubjectServiceImpl(subjectMapper, provider);
    }

    private static AiSubjectDO subject(Long applicationId, String externalUserId) {
        return new AiSubjectDO()
                .setId(7L)
                .setApplicationId(applicationId)
                .setSubjectType(AiSubjectType.USER.name())
                .setExternalUserId(externalUserId)
                .setStatus(AiSubjectDO.STATUS_ACTIVE)
                .setScopeSource("crm-auth")
                .setScopeVersion(3L)
                .setVersion(1);
    }

    @Test
    void sameExternalUserInDifferentApplicationsIsNotConflicting() {
        when(subjectMapper.selectByIdentity(1L, "USER", "alice")).thenReturn(subject(1L, "alice"));
        when(subjectMapper.selectByIdentity(2L, "USER", "alice")).thenReturn(null);
        when(subjectMapper.insert(any(AiSubjectDO.class))).thenAnswer(invocation -> {
            ((AiSubjectDO) invocation.getArgument(0)).setId(8L);
            return 1;
        });

        assertThat(service.findActiveSubject(1L, AiSubjectType.USER, "alice")).isPresent();
        assertThat(service.findActiveSubject(2L, AiSubjectType.USER, "alice")).isEmpty();
        // 在另一个应用里登记同一个用户名会新建主体，而不是复用
        AiSubjectDO created = service.syncSubject(2L, AiSubjectType.USER, "alice", "Alice", "portal-auth", 1L);
        assertThat(created.getApplicationId()).isEqualTo(2L);
        verify(subjectMapper).insert(any(AiSubjectDO.class));
    }

    @Test
    void syncSubjectKeepsScopeVersionMonotonicAndAppSubjectUsesEmptyExternalId() {
        when(subjectMapper.selectByIdentity(5L, "APP", "")).thenReturn(null);
        when(subjectMapper.selectByIdentity(5L, "USER", "bob")).thenReturn(subject(5L, "bob"));
        when(subjectMapper.selectById(7L)).thenReturn(subject(5L, "bob"));
        when(subjectMapper.updateWithVersion(any(AiSubjectDO.class), any())).thenReturn(1);
        when(subjectMapper.insert(any(AiSubjectDO.class))).thenAnswer(invocation -> {
            ((AiSubjectDO) invocation.getArgument(0)).setId(9L);
            return 1;
        });

        assertThat(service.syncSubject(5L, AiSubjectType.APP, null, "CRM", "crm-auth", null)
                        .getExternalUserId())
                .isEmpty();

        service.syncSubject(5L, AiSubjectType.USER, "bob", "Bob", "crm-auth", 2L);
        verify(subjectMapper)
                .updateWithVersion(
                        org.mockito.ArgumentMatchers.argThat(update -> update.getScopeVersion() == 3L), eq(1));
    }

    @Test
    void disablingSubjectMakesItUnavailableImmediately() {
        when(subjectMapper.selectByIdentity(5L, "USER", "bob")).thenReturn(subject(5L, "bob"));
        when(subjectMapper.updateWithVersion(any(AiSubjectDO.class), any())).thenReturn(1);

        service.disableSubject(5L, AiSubjectType.USER, "bob");

        verify(subjectMapper)
                .updateWithVersion(
                        org.mockito.ArgumentMatchers.argThat(
                                update -> AiSubjectDO.STATUS_DISABLED.equals(update.getStatus())),
                        eq(1));
    }

    @Test
    void scopeIsDeniedWhenSubjectMissingOrDisabled() {
        when(subjectMapper.selectByIdentity(5L, "USER", "bob")).thenReturn(null);

        AiSubjectScopeDTO dto = service.resolveScope(5L, AiSubjectType.USER, "bob", List.of());
        assertThat(dto.isDenied()).isTrue();
        assertThat(dto.getDenyReason()).isEqualTo("SUBJECT_NOT_FOUND");
        verify(provider, never()).getIfAvailable();

        when(subjectMapper.selectByIdentity(5L, "USER", "bob"))
                .thenReturn(subject(5L, "bob").setStatus(AiSubjectDO.STATUS_DISABLED));
        assertThat(service.resolveScope(5L, AiSubjectType.USER, "bob", List.of())
                        .isDenied())
                .isTrue();
    }

    @Test
    void scopeIsDeniedWhenResolverMissingFailingEmptyOrOverBudget() {
        when(subjectMapper.selectByIdentity(5L, "USER", "bob")).thenReturn(subject(5L, "bob"));
        SubjectScopeResolver resolver = mock(SubjectScopeResolver.class);
        when(provider.getIfAvailable()).thenReturn(resolver);

        // 未装配解析器
        when(provider.getIfAvailable()).thenReturn(null);
        assertThat(service.resolveScope(5L, AiSubjectType.USER, "bob", List.of())
                        .getDenyReason())
                .isEqualTo("RESOLVER_UNAVAILABLE");

        when(provider.getIfAvailable()).thenReturn(resolver);
        // 解析器返回空（无法判定）
        when(resolver.resolve(any())).thenReturn(Optional.empty());
        assertThat(service.resolveScope(5L, AiSubjectType.USER, "bob", List.of())
                        .getDenyReason())
                .isEqualTo("RESOLVER_FAILED");

        // 解析器抛异常
        org.mockito.Mockito.doThrow(new IllegalStateException("业务侧超时"))
                .when(resolver)
                .resolve(any());
        assertThat(service.resolveScope(5L, AiSubjectType.USER, "bob", List.of())
                        .getDenyReason())
                .isEqualTo("RESOLVER_FAILED");

        // 空集合：必须 DENY，不得当成"不过滤"
        org.mockito.Mockito.doReturn(Optional.of(new SubjectScope(Set.of(), Set.of(), "crm-auth", 3L)))
                .when(resolver)
                .resolve(any());
        assertThat(service.resolveScope(5L, AiSubjectType.USER, "bob", List.of())
                        .getDenyReason())
                .isEqualTo("EMPTY_SCOPE");

        // 超预算：同样 DENY
        Set<Long> overBudget = LongStream.range(0, AiSubjectServiceImpl.SCOPE_BUDGET + 1)
                .boxed()
                .collect(java.util.stream.Collectors.toSet());
        org.mockito.Mockito.doReturn(Optional.of(new SubjectScope(overBudget, Set.of(), "crm-auth", 3L)))
                .when(resolver)
                .resolve(any());
        assertThat(service.resolveScope(5L, AiSubjectType.USER, "bob", List.of())
                        .getDenyReason())
                .isEqualTo("SCOPE_OVER_BUDGET");
    }

    @Test
    void validScopeIsReturnedWithSourceAndVersionAndOnlyServerSideHintsArePassed() {
        when(subjectMapper.selectByIdentity(5L, "USER", "bob")).thenReturn(subject(5L, "bob"));
        SubjectScopeResolver resolver = mock(SubjectScopeResolver.class);
        when(provider.getIfAvailable()).thenReturn(resolver);
        org.mockito.Mockito.doReturn(
                        Optional.of(new SubjectScope(Set.of(10L, 11L), Set.of("report-1"), "crm-auth", 4L)))
                .when(resolver)
                .resolve(any());

        AiSubjectScopeDTO dto = service.resolveScope(5L, AiSubjectType.USER, "bob", List.of("report-1"));

        assertThat(dto.isDenied()).isFalse();
        assertThat(dto.getOrganizationIds()).containsExactlyInAnyOrder(10L, 11L);
        assertThat(dto.getResourceKeys()).containsExactly("report-1");
        assertThat(dto.getScopeVersion()).isEqualTo(4L);
        // 传给解析器的只有服务端事实与业务侧提示，没有客户端可伪造的角色/部门字段
        org.mockito.ArgumentCaptor<SubjectScopeResolver.SubjectScopeRequest> captor =
                org.mockito.ArgumentCaptor.forClass(SubjectScopeResolver.SubjectScopeRequest.class);
        verify(resolver).resolve(captor.capture());
        assertThat(captor.getValue().externalUserId()).isEqualTo("bob");
        assertThat(captor.getValue().scopeSource()).isEqualTo("crm-auth");
        assertThat(captor.getValue().resourceHints()).containsExactly("report-1");
    }

    @Test
    void scopeRecordTreatsEmptyWhiteListAsDeny() {
        SubjectScope empty = new SubjectScope(Set.of(), Set.of(), "crm-auth", 1L);
        assertThat(empty.isDeny()).isTrue();
        assertThat(empty.allowsOrganization(1L)).isFalse();
        assertThat(empty.allowsResource("report-1")).isFalse();

        SubjectScope scoped = new SubjectScope(Set.of(1L), Set.of("report-1"), "crm-auth", 2L);
        assertThat(scoped.allowsOrganization(1L)).isTrue();
        assertThat(scoped.allowsOrganization(2L)).isFalse();
        assertThat(scoped.allowsResource("report-1")).isTrue();
        assertThat(scoped.allowsResource("report-2")).isFalse();
    }

    @Test
    void subjectTypeVocabularyParsesOnlyKnownValues() {
        assertThat(AiSubjectType.parse("user")).contains(AiSubjectType.USER);
        assertThat(AiSubjectType.parse(" APP ")).contains(AiSubjectType.APP);
        assertThat(AiSubjectType.parse("admin")).isEmpty();
        assertThat(AiSubjectType.parse(null)).isEmpty();
        assertThat(AiSubjectType.parse("  ")).isEmpty();
    }

    @Test
    void userSubjectRequiresExternalUserId() {
        assertThatThrownBy(() -> service.syncSubject(5L, AiSubjectType.USER, null, "x", "crm", 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertThatThrownBy(() -> service.syncSubject(5L, AiSubjectType.USER, "bob", "x", null, 1L))
                .isInstanceOf(ServiceException.class);
    }
}
