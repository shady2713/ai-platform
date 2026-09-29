package com.basicframework.module.ai.service.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import com.basicframework.module.ai.dal.dataobject.federation.AiSubjectFederationDO;
import com.basicframework.module.ai.dal.dataobject.subject.AiSubjectDO;
import com.basicframework.module.ai.dal.mysql.federation.AiSubjectFederationMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.authorization.dto.AiSubjectFederationSubmitDTO;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Y01 跨系统主体联邦映射：显式登记 + 独立审批 + 撤销即时生效 + 不按同名推断。
 *
 * <p>负向用例是主体：提交人不能批准自己的申请、停用主体不能提交/批准、自映射与缺失身份被拒、
 * 已撤销映射允许重提交（且清空上一次审批痕迹）、乐观锁冲突报 409。
 */
class AiSubjectFederationServiceImplTest {

    private static final long SOURCE_APP = 7L;

    private static final long TARGET_APP = 9L;

    private AiSubjectFederationMapper federationMapper;

    private AiApplicationService applicationService;

    private AiSubjectService subjectService;

    private AiSubjectFederationServiceImpl service;

    @BeforeEach
    void setUp() {
        federationMapper = mock(AiSubjectFederationMapper.class);
        applicationService = mock(AiApplicationService.class);
        subjectService = mock(AiSubjectService.class);
        service = new AiSubjectFederationServiceImpl(federationMapper, applicationService, subjectService);
        loginAs(1001L);
        when(applicationService.getApplication(SOURCE_APP)).thenReturn(application(SOURCE_APP, true));
        when(applicationService.getApplication(TARGET_APP)).thenReturn(application(TARGET_APP, true));
        when(subjectService.findActiveSubject(eq(SOURCE_APP), any(), any()))
                .thenReturn(Optional.of(subject(SOURCE_APP, "alice")));
        when(subjectService.findActiveSubject(eq(TARGET_APP), any(), any()))
                .thenReturn(Optional.of(subject(TARGET_APP, "alice")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void submitCreatesPendingLinkWithSubmitterAndRevisionOne() {
        when(federationMapper.selectByIdentity(any())).thenReturn(null);
        when(federationMapper.insert(any(AiSubjectFederationDO.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, AiSubjectFederationDO.class).setId(42L);
            return 1;
        });

        Long id = service.submit(submitDTO());

        assertThat(id).isEqualTo(42L);
        ArgumentCaptor<AiSubjectFederationDO> captor = ArgumentCaptor.forClass(AiSubjectFederationDO.class);
        verify(federationMapper).insert(captor.capture());
        AiSubjectFederationDO inserted = captor.getValue();
        assertThat(inserted.getStatus()).isEqualTo(AiSubjectFederationDO.STATUS_PENDING);
        assertThat(inserted.getRequestedBy()).isEqualTo(1001L);
        assertThat(inserted.getRevision()).isEqualTo(1L);
        assertThat(inserted.getVersion()).isZero();
        assertThat(inserted.getApprovedBy()).isNull();
        // 六段身份都写入：跨系统隔离依赖它（相同 externalUserId 在不同应用是不同行）
        assertThat(inserted.getSourceApplicationId()).isEqualTo(SOURCE_APP);
        assertThat(inserted.getTargetApplicationId()).isEqualTo(TARGET_APP);
        assertThat(inserted.getTargetExternalUserId()).isEqualTo("alice");
    }

    @Test
    void submitRequiresOperatorAndRejectsSelfLinkAndMissingIdentity() {
        assertCode(
                () -> {
                    SecurityContextHolder.clearContext();
                    service.submit(submitDTO());
                },
                AiErrorCodeConstants.AI_ACCESS_DENIED.getCode());

        loginAs(1001L);
        assertCode(
                () -> service.submit(submitDTO().setTargetApplicationId(SOURCE_APP)),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                () -> service.submit(submitDTO().setTargetExternalUserId(" ")),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                () -> service.submit(submitDTO().setSourceSubjectType("ADMIN")),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(() -> service.submit(null), AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        verify(federationMapper, never()).insert(any(AiSubjectFederationDO.class));
    }

    @Test
    void submitRejectsUnknownOrDisabledSubjectAndDisabledApplication() {
        when(subjectService.findActiveSubject(eq(TARGET_APP), any(), any())).thenReturn(Optional.empty());
        assertCode(
                () -> service.submit(submitDTO()),
                AiErrorCodeConstants.AI_SUBJECT_FEDERATION_SUBJECT_UNAVAILABLE.getCode());

        when(subjectService.findActiveSubject(eq(TARGET_APP), any(), any()))
                .thenReturn(Optional.of(subject(TARGET_APP, "alice")));
        when(applicationService.getApplication(TARGET_APP)).thenReturn(application(TARGET_APP, false));
        assertCode(() -> service.submit(submitDTO()), AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
    }

    @Test
    void submitRejectsDuplicateButAllowsResubmitAfterRevoke() {
        when(federationMapper.selectByIdentity(any()))
                .thenReturn(federation(1L, AiSubjectFederationDO.STATUS_APPROVED));
        assertCode(() -> service.submit(submitDTO()), AiErrorCodeConstants.AI_SUBJECT_FEDERATION_DUPLICATE.getCode());
        verify(federationMapper, never()).insert(any(AiSubjectFederationDO.class));

        when(federationMapper.selectByIdentity(any())).thenReturn(federation(5L, AiSubjectFederationDO.STATUS_REVOKED));
        when(federationMapper.resubmitAfterRevoke(any(), eq(3))).thenReturn(1);
        assertThat(service.submit(submitDTO())).isEqualTo(5L);
        ArgumentCaptor<AiSubjectFederationDO> captor = ArgumentCaptor.forClass(AiSubjectFederationDO.class);
        verify(federationMapper).resubmitAfterRevoke(captor.capture(), eq(3));
        // 映射版本继续递增（撤销前的 3 → 重提交 4）：范围选择的指纹据此判定映射事实已变化
        assertThat(captor.getValue().getRevision()).isEqualTo(4L);
        assertThat(captor.getValue().getRequestedBy()).isEqualTo(1001L);
    }

    @Test
    void resubmitConflictWhenRevokedRowChangedConcurrently() {
        when(federationMapper.selectByIdentity(any())).thenReturn(federation(5L, AiSubjectFederationDO.STATUS_REVOKED));
        when(federationMapper.resubmitAfterRevoke(any(), eq(3))).thenReturn(0);
        assertCode(
                () -> service.submit(submitDTO()), AiErrorCodeConstants.AI_SUBJECT_FEDERATION_STATE_CONFLICT.getCode());
    }

    @Test
    void approveRequiresAnotherOperatorAndPendingState() {
        when(federationMapper.selectById(5L)).thenReturn(federation(5L, AiSubjectFederationDO.STATUS_PENDING));
        // 提交人自己批准 → 独立审批冲突
        assertCode(
                () -> service.approve(5L, 3, "ok"),
                AiErrorCodeConstants.AI_SUBJECT_FEDERATION_APPROVER_CONFLICT.getCode());

        loginAs(2002L);
        when(federationMapper.updateWithVersion(any(), eq(7))).thenReturn(1);
        service.approve(5L, 7, "独立复核通过");
        ArgumentCaptor<AiSubjectFederationDO> captor = ArgumentCaptor.forClass(AiSubjectFederationDO.class);
        verify(federationMapper).updateWithVersion(captor.capture(), eq(7));
        assertThat(captor.getValue().getStatus()).isEqualTo(AiSubjectFederationDO.STATUS_APPROVED);
        assertThat(captor.getValue().getApprovedBy()).isEqualTo(2002L);
        assertThat(captor.getValue().getApprovedTime()).isNotNull();
        assertThat(captor.getValue().getRevision()).isEqualTo(4L);
        assertThat(captor.getValue().getVersion()).isEqualTo(8);
    }

    @Test
    void approveRejectsNonPendingSubjectGoneNoteTooLongAndVersionConflict() {
        when(federationMapper.selectById(5L)).thenReturn(federation(5L, AiSubjectFederationDO.STATUS_APPROVED));
        loginAs(2002L);
        assertCode(
                () -> service.approve(5L, 3, null),
                AiErrorCodeConstants.AI_SUBJECT_FEDERATION_STATE_CONFLICT.getCode());

        when(federationMapper.selectById(5L)).thenReturn(federation(5L, AiSubjectFederationDO.STATUS_PENDING));
        assertCode(() -> service.approve(5L, 3, "x".repeat(201)), AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(() -> service.approve(5L, -1, null), AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());

        when(subjectService.findActiveSubject(eq(TARGET_APP), any(), any())).thenReturn(Optional.empty());
        assertCode(
                () -> service.approve(5L, 3, null),
                AiErrorCodeConstants.AI_SUBJECT_FEDERATION_SUBJECT_UNAVAILABLE.getCode());

        when(subjectService.findActiveSubject(eq(TARGET_APP), any(), any()))
                .thenReturn(Optional.of(subject(TARGET_APP, "alice")));
        when(federationMapper.updateWithVersion(any(), eq(3))).thenReturn(0);
        assertCode(
                () -> service.approve(5L, 3, null),
                AiErrorCodeConstants.AI_SUBJECT_FEDERATION_STATE_CONFLICT.getCode());
        verify(federationMapper, never()).updateWithVersion(any(), eq(-1));
    }

    @Test
    void approveRequiresOperatorIdentityToo() {
        when(federationMapper.selectById(5L)).thenReturn(federation(5L, AiSubjectFederationDO.STATUS_PENDING));
        SecurityContextHolder.clearContext();
        assertCode(() -> service.approve(5L, 3, null), AiErrorCodeConstants.AI_ACCESS_DENIED.getCode());
    }

    @Test
    void revokeIsIdempotentAndUsesCasOtherwise() {
        when(federationMapper.selectById(5L)).thenReturn(federation(5L, AiSubjectFederationDO.STATUS_APPROVED));
        when(federationMapper.updateWithVersion(any(), eq(3))).thenReturn(1);
        service.revoke(5L, 3);
        ArgumentCaptor<AiSubjectFederationDO> captor = ArgumentCaptor.forClass(AiSubjectFederationDO.class);
        verify(federationMapper).updateWithVersion(captor.capture(), eq(3));
        assertThat(captor.getValue().getStatus()).isEqualTo(AiSubjectFederationDO.STATUS_REVOKED);
        assertThat(captor.getValue().getVersion()).isEqualTo(4);

        when(federationMapper.selectById(5L)).thenReturn(federation(5L, AiSubjectFederationDO.STATUS_REVOKED));
        service.revoke(5L, 99);
        verify(federationMapper, never()).updateWithVersion(any(), eq(99));

        when(federationMapper.selectById(5L)).thenReturn(federation(5L, AiSubjectFederationDO.STATUS_PENDING));
        when(federationMapper.updateWithVersion(any(), eq(11))).thenReturn(0);
        assertCode(() -> service.revoke(5L, 11), AiErrorCodeConstants.AI_SUBJECT_FEDERATION_STATE_CONFLICT.getCode());
    }

    @Test
    void getAndPageValidateIdVersionAndStatus() {
        when(federationMapper.selectById(any())).thenReturn(null);
        assertCode(() -> service.getFederation(null), AiErrorCodeConstants.AI_SUBJECT_FEDERATION_NOT_EXISTS.getCode());
        assertCode(() -> service.getFederation(5L), AiErrorCodeConstants.AI_SUBJECT_FEDERATION_NOT_EXISTS.getCode());
        assertCode(() -> service.revoke(5L, null), AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());

        PageParam pageParam = new PageParam();
        assertCode(
                () -> service.getFederationPage(null, SOURCE_APP, null),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                () -> service.getFederationPage(pageParam, SOURCE_APP, "UNKNOWN"),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        when(federationMapper.selectPage(pageParam, SOURCE_APP, AiSubjectFederationDO.STATUS_APPROVED))
                .thenReturn(new com.basicframework.framework.common.pojo.PageResult<>(List.of(), 0L));
        assertThat(service.getFederationPage(pageParam, SOURCE_APP, AiSubjectFederationDO.STATUS_APPROVED)
                        .getTotal())
                .isZero();
    }

    @Test
    void listApprovedTargetsNormalizesAppSubjectAndIgnoresIncompleteIdentity() {
        when(federationMapper.selectApprovedBySource(SOURCE_APP, "USER", "alice"))
                .thenReturn(List.of());
        assertThat(service.listApprovedTargets(SOURCE_APP, AiSubjectType.USER, "alice"))
                .isEmpty();

        assertThat(service.listApprovedTargets(null, AiSubjectType.USER, "alice"))
                .isEmpty();
        assertThat(service.listApprovedTargets(SOURCE_APP, null, "alice")).isEmpty();
        assertThat(service.listApprovedTargets(SOURCE_APP, AiSubjectType.USER, "  "))
                .isEmpty();

        when(federationMapper.selectApprovedBySource(SOURCE_APP, "APP", "")).thenReturn(List.of());
        assertThat(service.listApprovedTargets(SOURCE_APP, AiSubjectType.APP, "ignored"))
                .isEmpty();
        verify(federationMapper).selectApprovedBySource(SOURCE_APP, "APP", "");
    }

    private static AiSubjectFederationSubmitDTO submitDTO() {
        return new AiSubjectFederationSubmitDTO()
                .setSourceApplicationId(SOURCE_APP)
                .setSourceSubjectType("USER")
                .setSourceExternalUserId("alice")
                .setTargetApplicationId(TARGET_APP)
                .setTargetSubjectType("USER")
                .setTargetExternalUserId("alice");
    }

    private static AiSubjectFederationDO federation(Long id, String status) {
        return new AiSubjectFederationDO()
                .setId(id)
                .setSourceApplicationId(SOURCE_APP)
                .setSourceSubjectType("USER")
                .setSourceExternalUserId("alice")
                .setTargetApplicationId(TARGET_APP)
                .setTargetSubjectType("USER")
                .setTargetExternalUserId("alice")
                .setStatus(status)
                .setRequestedBy(1001L)
                .setRevision(3L)
                .setVersion(3);
    }

    private static AiApplicationDO application(Long id, boolean enabled) {
        return new AiApplicationDO()
                .setId(id)
                .setAppCode("app-" + id)
                .setName("应用 " + id)
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
                .setScopeVersion(1L)
                .setVersion(0);
    }

    private static void loginAs(Long userId) {
        LoginUser loginUser = new LoginUser().setId(userId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, Integer code) {
        assertThatThrownBy(callable)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(code);
    }
}
