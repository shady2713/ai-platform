package com.basicframework.module.ai.service.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import com.basicframework.module.ai.dal.dataobject.federation.AiSubjectFederationDO;
import com.basicframework.module.ai.dal.dataobject.grant.AiResourceGrantDO;
import com.basicframework.module.ai.dal.dataobject.subject.AiSubjectDO;
import com.basicframework.module.ai.dal.mysql.grant.AiResourceGrantMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.application.dto.AiSystemCatalogDTO;
import com.basicframework.module.ai.service.application.dto.AiSystemCatalogQueryDTO;
import com.basicframework.module.ai.service.application.dto.AiSystemEntryDTO;
import com.basicframework.module.ai.service.authorization.AiSubjectFederationService;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import com.basicframework.module.ai.service.subject.dto.AiSubjectScopeDTO;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Y01 多系统授权发现：无权系统不出现、同 externalUserId 跨应用隔离、超预算拒绝、拒绝不可区分。
 *
 * <p>授权读取按"每次 discover 的读取顺序"排队（当前系统在前，随后按映射编号升序），
 * 因此测试用显式的分页队列而不是宽泛的参数匹配——发现路径必须精确到主体，宽匹配会让
 * "跨应用串权"这类缺陷测不出来。
 */
class AiSystemCatalogServiceImplTest {

    private static final long CURRENT_APP = 7L;

    private static final long OTHER_APP = 9L;

    private AiApplicationService applicationService;

    private AiSubjectService subjectService;

    private AiSubjectFederationService federationService;

    private AiResourceGrantMapper grantMapper;

    private AiSystemCatalogServiceImpl service;

    private final Deque<List<AiResourceGrantDO>> grantPages = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        applicationService = mock(AiApplicationService.class);
        subjectService = mock(AiSubjectService.class);
        federationService = mock(AiSubjectFederationService.class);
        grantMapper = mock(AiResourceGrantMapper.class);
        service = new AiSystemCatalogServiceImpl(applicationService, subjectService, federationService, grantMapper);
        when(applicationService.getApplication(CURRENT_APP)).thenReturn(application(CURRENT_APP, "crm", true));
        when(applicationService.getApplication(OTHER_APP)).thenReturn(application(OTHER_APP, "erp", true));
        when(subjectService.findActiveSubject(eq(CURRENT_APP), any(), any()))
                .thenReturn(Optional.of(subject(CURRENT_APP, "alice")));
        when(subjectService.findActiveSubject(eq(OTHER_APP), any(), any()))
                .thenReturn(Optional.of(subject(OTHER_APP, "alice")));
        when(subjectService.resolveScope(eq(CURRENT_APP), any(), any(), any())).thenReturn(scope(CURRENT_APP, "alice"));
        when(subjectService.resolveScope(eq(OTHER_APP), any(), any(), any())).thenReturn(scope(OTHER_APP, "alice"));
        when(federationService.listApprovedTargets(any(), any(), any())).thenReturn(List.of());
        when(grantMapper.selectPage(any(PageParam.class), any())).thenAnswer(invocation -> {
            // 每次读取（当前系统 → 各联邦目标）从队列取一页；队列空即"没有更多授权"
            List<AiResourceGrantDO> page = grantPages.poll();
            return new PageResult<>(page == null ? List.of() : page, page == null ? 0L : (long) page.size());
        });
    }

    @SafeVarargs
    private void stubGrantPages(List<AiResourceGrantDO>... pages) {
        grantPages.clear();
        grantPages.addAll(Arrays.asList(pages));
    }

    @Test
    void singleSystemCatalogContainsOnlyCurrentSystemAndStableFingerprint() {
        stubGrantPages(List.of(grant("REPORT", "q3", "EXECUTE,READ")));

        AiSystemCatalogDTO catalog = service.discover(query());

        assertThat(catalog.isDenied()).isFalse();
        assertThat(catalog.getEntries()).hasSize(1);
        AiSystemEntryDTO entry = catalog.getEntries().get(0);
        assertThat(entry.getAppCode()).isEqualTo("crm");
        assertThat(entry.isCurrentSystem()).isTrue();
        assertThat(entry.getExternalUserId()).isEqualTo("alice");
        assertThat(entry.getScopes()).hasSize(1);
        assertThat(entry.getScopes().get(0).getActions()).containsExactly("EXECUTE", "READ");
        assertThat(entry.getFederationId()).isNull();
        assertThat(entry.getSystemFingerprint()).hasSize(64);
        assertThat(catalog.getCatalogFingerprint()).hasSize(64);
        // 模型目录只含可访问系统，且不含外部用户标识
        assertThat(catalog.getModelCatalog()).contains("\"system\":\"crm\"").doesNotContain("alice");
    }

    @Test
    void fingerprintIsDeterministicAndChangesWithFacts() {
        stubGrantPages(List.of(grant("REPORT", "q3", "READ")));
        String first = service.discover(query()).getCatalogFingerprint();
        stubGrantPages(List.of(grant("REPORT", "q3", "READ")));
        assertThat(service.discover(query()).getCatalogFingerprint()).isEqualTo(first);

        stubGrantPages(List.of(grant("REPORT", "q3", "READ"), grant("TOOL", "refund", "EXECUTE")));
        assertThat(service.discover(query()).getCatalogFingerprint()).isNotEqualTo(first);
    }

    @Test
    void subjectDisableScopeDenyAndNoGrantProduceTheSameDeniedCatalog() {
        when(subjectService.findActiveSubject(eq(CURRENT_APP), any(), any())).thenReturn(Optional.empty());
        AiSystemCatalogDTO unavailable = service.discover(query());

        when(subjectService.findActiveSubject(eq(CURRENT_APP), any(), any()))
                .thenReturn(Optional.of(subject(CURRENT_APP, "alice")));
        when(subjectService.resolveScope(eq(CURRENT_APP), any(), any(), any()))
                .thenReturn(new AiSubjectScopeDTO().setDenied(true).setDenyReason("EMPTY_SCOPE"));
        AiSystemCatalogDTO deniedScope = service.discover(query());

        when(subjectService.resolveScope(eq(CURRENT_APP), any(), any(), any())).thenReturn(scope(CURRENT_APP, "alice"));
        stubGrantPages(List.of());
        AiSystemCatalogDTO noGrant = service.discover(query());

        // 三种"无法认定可访问"的情况返回完全一致的拒绝目录（不枚举主体、不枚举系统）
        assertThat(unavailable.isDenied()).isTrue();
        assertThat(unavailable.getEntries()).isEmpty();
        assertThat(unavailable.getModelCatalog()).isEqualTo("[]");
        assertThat(deniedScope.getCatalogFingerprint()).isEqualTo(unavailable.getCatalogFingerprint());
        assertThat(noGrant.getCatalogFingerprint()).isEqualTo(unavailable.getCatalogFingerprint());
    }

    @Test
    void federatedSystemAppearsOnlyWhenApprovedAndStaysIsolatedPerApplication() {
        stubGrantPages(List.of(grant("REPORT", "q3", "READ")), List.of(grant("DATASET", "orders", "READ,EXPORT")));
        when(federationService.listApprovedTargets(CURRENT_APP, AiSubjectType.USER, "alice"))
                .thenReturn(List.of(link(11L, 4L)));

        AiSystemCatalogDTO catalog = service.discover(query());

        assertThat(catalog.getEntries())
                .extracting(AiSystemEntryDTO::getAppCode)
                .containsExactly("crm", "erp");
        AiSystemEntryDTO federated = catalog.getEntries().get(1);
        assertThat(federated.isCurrentSystem()).isFalse();
        assertThat(federated.getFederationId()).isEqualTo(11L);
        assertThat(federated.getFederationRevision()).isEqualTo(4L);
        assertThat(catalog.getModelCatalog()).contains("\"system\":\"erp\"").contains("\"system\":\"crm\"");

        // 目标应用里没有登记这个主体（同 externalUserId 无效）：该系统直接不出现
        when(subjectService.findActiveSubject(eq(OTHER_APP), any(), any())).thenReturn(Optional.empty());
        stubGrantPages(List.of(grant("REPORT", "q3", "READ")));
        AiSystemCatalogDTO isolated = service.discover(query());
        assertThat(isolated.getEntries())
                .extracting(AiSystemEntryDTO::getAppCode)
                .containsExactly("crm");
        assertThat(isolated.getModelCatalog()).doesNotContain("erp");
    }

    @Test
    void federatedSystemWithoutGrantsOrWithDeniedScopeOrDirtyTypeIsAbsent() {
        when(federationService.listApprovedTargets(any(), any(), any())).thenReturn(List.of(link(11L, 1L)));
        stubGrantPages(List.of(grant("REPORT", "q3", "READ")), List.of());
        assertThat(service.discover(query()).getEntries()).hasSize(1);

        stubGrantPages(List.of(grant("REPORT", "q3", "READ")), List.of(grant("DATASET", "orders", "READ")));
        when(subjectService.resolveScope(eq(OTHER_APP), any(), any(), any()))
                .thenReturn(new AiSubjectScopeDTO().setDenied(true).setDenyReason("RESOLVER_FAILED"));
        assertThat(service.discover(query()).getEntries()).hasSize(1);

        when(subjectService.resolveScope(eq(OTHER_APP), any(), any(), any())).thenReturn(scope(OTHER_APP, "alice"));
        AiSubjectFederationDO dirty = link(11L, 1L).setTargetSubjectType("SERVICE");
        when(federationService.listApprovedTargets(any(), any(), any())).thenReturn(List.of(dirty));
        stubGrantPages(List.of(grant("REPORT", "q3", "READ")));
        assertThat(service.discover(query()).getEntries()).hasSize(1);
    }

    @Test
    void duplicateFederatedLinksToOneSystemKeepOnlyTheLowestId() {
        stubGrantPages(
                List.of(grant("REPORT", "q3", "READ")),
                List.of(grant("DATASET", "orders", "READ")),
                List.of(grant("DATASET", "orders", "READ")));
        when(federationService.listApprovedTargets(any(), any(), any()))
                .thenReturn(List.of(link(11L, 1L), link(12L, 1L)));

        AiSystemCatalogDTO catalog = service.discover(query());

        assertThat(catalog.getEntries()).hasSize(2);
        assertThat(catalog.getEntries().get(1).getFederationId()).isEqualTo(11L);
    }

    @Test
    void disabledTargetApplicationIsSkippedWhileBrokenCurrentApplicationFails() {
        when(applicationService.getApplication(OTHER_APP)).thenReturn(application(OTHER_APP, "erp", false));
        when(federationService.listApprovedTargets(any(), any(), any())).thenReturn(List.of(link(11L, 1L)));
        stubGrantPages(List.of(grant("REPORT", "q3", "READ")));
        assertThat(service.discover(query()).getEntries()).hasSize(1);

        when(applicationService.getApplication(CURRENT_APP)).thenReturn(application(CURRENT_APP, "crm", false));
        assertCode(() -> service.discover(query()), AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());

        when(applicationService.getApplication(CURRENT_APP))
                .thenThrow(new ServiceException(AiErrorCodeConstants.AI_APPLICATION_NOT_FOUND));
        assertCode(() -> service.discover(query()), AiErrorCodeConstants.AI_APPLICATION_NOT_FOUND.getCode());
    }

    @Test
    void deletedFederatedApplicationIsSkippedInsteadOfFailingWholeDiscovery() {
        when(federationService.listApprovedTargets(any(), any(), any())).thenReturn(List.of(link(11L, 1L)));
        when(applicationService.getApplication(OTHER_APP))
                .thenThrow(new ServiceException(AiErrorCodeConstants.AI_APPLICATION_NOT_FOUND));
        stubGrantPages(List.of(grant("REPORT", "q3", "READ")));

        assertThat(service.discover(query()).getEntries())
                .extracting(AiSystemEntryDTO::getAppCode)
                .containsExactly("crm");
    }

    @Test
    void unknownActionsAreDroppedAndGrantWithoutValidActionsDoesNotAuthorize() {
        stubGrantPages(List.of(grant("REPORT", "q3", "READ,TELEPORT")));
        assertThat(service.discover(query())
                        .getEntries()
                        .get(0)
                        .getScopes()
                        .get(0)
                        .getActions())
                .containsExactly("READ");

        stubGrantPages(List.of(grant("REPORT", "q3", "TELEPORT")));
        assertThat(service.discover(query()).isDenied()).isTrue();

        stubGrantPages(List.of(grant("REPORT", "q3", null)));
        assertThat(service.discover(query()).isDenied()).isTrue();
    }

    @Test
    void grantsBeyondBudgetAreRejectedInsteadOfReturningPartialCatalog() {
        when(grantMapper.selectPage(any(PageParam.class), any()))
                .thenReturn(new PageResult<>(
                        List.of(grant("REPORT", "q3", "READ")), (long) AiSystemCatalogServiceImpl.GRANT_BUDGET + 1));

        assertCode(() -> service.discover(query()), AiErrorCodeConstants.AI_SYSTEM_CATALOG_BUDGET_EXCEEDED.getCode());
    }

    @Test
    void grantPagingReadsEveryPage() {
        List<AiResourceGrantDO> fullPage = IntStream.range(0, AiSystemCatalogServiceImpl.GRANT_PAGE_SIZE)
                .mapToObj(index -> grant("REPORT", "r" + index, "READ"))
                .toList();
        stubGrantPages(fullPage, List.of(grant("TOOL", "refund", "EXECUTE")));

        AiSystemEntryDTO entry = service.discover(query()).getEntries().get(0);

        assertThat(entry.getScopes()).hasSize(AiSystemCatalogServiceImpl.GRANT_PAGE_SIZE + 1);
    }

    @Test
    void queryValidationRejectsBrokenShapes() {
        assertCode(() -> service.discover(null), AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                () -> service.discover(
                        new AiSystemCatalogQueryDTO().setSubjectType("USER").setExternalUserId("a")),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                () -> service.discover(new AiSystemCatalogQueryDTO()
                        .setApplicationId(0L)
                        .setSubjectType("USER")
                        .setExternalUserId("a")),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                () -> service.discover(new AiSystemCatalogQueryDTO()
                        .setApplicationId(CURRENT_APP)
                        .setSubjectType("USER")),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                () -> service.discover(new AiSystemCatalogQueryDTO()
                        .setApplicationId(CURRENT_APP)
                        .setSubjectType("ADMIN")
                        .setExternalUserId("a")),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                () -> service.discover(new AiSystemCatalogQueryDTO()
                        .setApplicationId(CURRENT_APP)
                        .setSubjectType("USER")
                        .setExternalUserId("x".repeat(129))),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
    }

    @Test
    void appSubjectUsesEmptyExternalUserId() {
        when(subjectService.findActiveSubject(CURRENT_APP, AiSubjectType.APP, ""))
                .thenReturn(Optional.of(subject(CURRENT_APP, "")));

        when(subjectService.resolveScope(eq(CURRENT_APP), eq(AiSubjectType.APP), eq(""), any()))
                .thenReturn(scope(CURRENT_APP, ""));
        stubGrantPages(List.of(grant("REPORT", "q3", "READ")));

        AiSystemCatalogDTO catalog = service.discover(
                new AiSystemCatalogQueryDTO().setApplicationId(CURRENT_APP).setSubjectType("APP"));

        assertThat(catalog.getEntries()).hasSize(1);
        assertThat(catalog.getEntries().get(0).getExternalUserId()).isEmpty();
    }

    private AiSystemCatalogQueryDTO query() {
        return new AiSystemCatalogQueryDTO()
                .setApplicationId(CURRENT_APP)
                .setSubjectType("USER")
                .setExternalUserId("alice");
    }

    private static AiApplicationDO application(Long id, String appCode, boolean enabled) {
        return new AiApplicationDO()
                .setId(id)
                .setAppCode(appCode)
                .setName(appCode + " 系统")
                .setEnabled(enabled)
                .setVersion(0);
    }

    private static AiSubjectDO subject(Long applicationId, String externalUserId) {
        return new AiSubjectDO()
                .setId(applicationId * 10)
                .setApplicationId(applicationId)
                .setSubjectType("USER")
                .setExternalUserId(externalUserId)
                .setStatus(AiSubjectDO.STATUS_ACTIVE)
                .setScopeSource("crm-auth")
                .setScopeVersion(2L)
                .setVersion(0);
    }

    private static AiSubjectScopeDTO scope(Long applicationId, String externalUserId) {
        return new AiSubjectScopeDTO()
                .setApplicationId(applicationId)
                .setSubjectType("USER")
                .setExternalUserId(externalUserId)
                .setDenied(false)
                .setOrganizationIds(Set.of(10L))
                .setResourceKeys(Set.of("q3"))
                .setScopeSource("crm-auth")
                .setScopeVersion(2L);
    }

    private static AiResourceGrantDO grant(String resourceType, String resourceKey, String actions) {
        return new AiResourceGrantDO()
                .setId((long) Math.abs(resourceKey.hashCode()))
                .setApplicationId(CURRENT_APP)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setResourceType(resourceType)
                .setResourceKey(resourceKey)
                .setActions(actions)
                .setStatus(AiResourceGrantDO.STATUS_ACTIVE)
                .setAuthzRevision(1L)
                .setVersion(0);
    }

    private static AiSubjectFederationDO link(Long id, Long revision) {
        return new AiSubjectFederationDO()
                .setId(id)
                .setSourceApplicationId(CURRENT_APP)
                .setSourceSubjectType("USER")
                .setSourceExternalUserId("alice")
                .setTargetApplicationId(OTHER_APP)
                .setTargetSubjectType("USER")
                .setTargetExternalUserId("alice")
                .setStatus(AiSubjectFederationDO.STATUS_APPROVED)
                .setRevision(revision)
                .setVersion(1);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, Integer code) {
        assertThatThrownBy(callable)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(code);
    }
}
