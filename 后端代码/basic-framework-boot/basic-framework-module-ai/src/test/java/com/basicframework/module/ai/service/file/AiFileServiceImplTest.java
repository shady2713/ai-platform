package com.basicframework.module.ai.service.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.adapter.file.AiFileSubjectResolver;
import com.basicframework.module.ai.dal.dataobject.file.AiFileBindingDO;
import com.basicframework.module.ai.dal.mysql.file.AiFileBindingMapper;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.framework.security.AiUserSessionCommonApi;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.authorization.dto.AiAuthorizationDecisionDTO;
import com.basicframework.module.infra.api.file.FileCommonApi;
import com.basicframework.module.infra.api.file.dto.FileCreateReqDTO;
import com.basicframework.module.infra.api.file.dto.FileDeleteReqDTO;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * A07 业务文件服务：未知业务类型拒绝、报表按授权目录判定、会话附件仅所有者、
 * 解除引用只允许所有者且共享引用未释放不误删。
 */
class AiFileServiceImplTest {

    private AiFileBindingMapper bindingMapper;

    private AiAuthorizationService authorizationService;

    private FileCommonApi fileCommonApi;

    private AiFileServiceImpl service;

    @BeforeEach
    void setUp() {
        bindingMapper = mock(AiFileBindingMapper.class);
        authorizationService = mock(AiAuthorizationService.class);
        fileCommonApi = mock(FileCommonApi.class);
        service = new AiFileServiceImpl(
                bindingMapper,
                new AiFileSubjectResolver(
                        mock(com.basicframework.module.ai.dal.mysql.token.AiAccessTicketMapper.class)),
                authorizationService,
                fileCommonApi);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private static void loginAs(String externalUserId) {
        LoginUser loginUser = new LoginUser()
                .setId(21L)
                .setUserType(UserTypeEnum.MEMBER.getValue())
                .setInfo(Map.of(
                        AiUserSessionCommonApi.INFO_KEY_APPLICATION_ID, "5",
                        AiUserSessionCommonApi.INFO_KEY_SUBJECT_TYPE, "USER",
                        AiUserSessionCommonApi.INFO_KEY_EXTERNAL_USER_ID, externalUserId));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    private static AiFileBindingDO binding(String businessType, String businessKey, String owner) {
        return binding(3L, businessType, businessKey, owner);
    }

    private static AiFileBindingDO binding(long id, String businessType, String businessKey, String owner) {
        return new AiFileBindingDO()
                .setId(id)
                .setFileId(88L)
                .setBusinessType(businessType)
                .setBusinessKey(businessKey)
                .setApplicationId(5L)
                .setSubjectType("USER")
                .setExternalUserId(owner)
                .setStatus(AiFileBindingDO.STATUS_ACTIVE)
                .setVersion(1);
    }

    /** 该桩用于让"报表/知识库"路径通过授权判定。 */
    private void grantRead(String businessKey) {
        when(authorizationService.authorize(
                        eq(5L),
                        eq("USER"),
                        any(),
                        any(AiResourceType.class),
                        eq(businessKey),
                        eq(AiAction.READ),
                        any()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(true));
    }

    @Test
    void uploadRejectsUnknownBusinessTypeAndInvalidInput() {
        loginAs("alice");

        assertThatThrownBy(() -> service.upload("unknown_type", "k1", "a.txt", "text/plain", new byte[] {1}))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertThatThrownBy(() -> service.upload("ai_report", "  ", "a.txt", "text/plain", new byte[] {1}))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.upload("ai_report", "k1", "a.txt", "text/plain", new byte[0]))
                .isInstanceOf(ServiceException.class);
        verify(fileCommonApi, never()).createFile(any());
    }

    @Test
    void uploadRequiresCurrentGrantForReportAndBindsOwner() {
        loginAs("alice");
        grantRead("report-1");
        when(fileCommonApi.createFile(any())).thenReturn(88L);

        var result = service.upload("ai_report", "report-1", "a.txt", "text/plain", new byte[] {1, 2});

        assertThat(result.getFileId()).isEqualTo(88L);
        ArgumentCaptor<AiFileBindingDO> captor = ArgumentCaptor.forClass(AiFileBindingDO.class);
        verify(bindingMapper).insert(captor.capture());
        assertThat(captor.getValue().getApplicationId()).isEqualTo(5L);
        assertThat(captor.getValue().getExternalUserId()).isEqualTo("alice");
        assertThat(captor.getValue().getBusinessType()).isEqualTo("ai_report");
        // 传给 infra 的主体是 MEMBER + 票据编号，而不是系统用户编号
        ArgumentCaptor<FileCreateReqDTO> createCaptor = ArgumentCaptor.forClass(FileCreateReqDTO.class);
        verify(fileCommonApi).createFile(createCaptor.capture());
        assertThat(createCaptor.getValue().getSubject().getUserType()).isEqualTo(UserTypeEnum.MEMBER.getValue());
        assertThat(createCaptor.getValue().getSubject().getUserId()).isEqualTo(21L);
    }

    @Test
    void uploadRejectedWithoutGrant() {
        loginAs("alice");
        when(authorizationService.authorize(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(false));

        assertThatThrownBy(() -> service.upload("ai_report", "report-1", "a.txt", "text/plain", new byte[] {1}))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_AUTHORIZATION_DENIED.getCode());
        verify(fileCommonApi, never()).createFile(any());
    }

    @Test
    void chatSessionAttachmentIsOwnerOnlyForReadAndRelease() {
        // 所有者读取
        loginAs("alice");
        when(bindingMapper.selectActiveByFile(88L))
                .thenReturn(List.of(binding("ai_chat_session", "session-1", "alice")));
        when(fileCommonApi.getFileContent(any())).thenReturn(new byte[] {9});

        assertThat(service.read(88L)).containsExactly(9);

        // 其他人读取：按不存在处理
        loginAs("bob");
        assertThatThrownBy(() -> service.read(88L))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND.getCode());

        // 其他人解除引用：拒绝（所有者才可解除）
        assertThatThrownBy(() -> service.release(88L))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_AUTHORIZATION_DENIED.getCode());
        verify(bindingMapper, never()).updateWithVersion(any(), any());
    }

    @Test
    void releaseKeepsSharedFileUntilLastReferenceIsGone() {
        loginAs("alice");
        when(bindingMapper.selectActiveByFile(88L))
                .thenReturn(List.of(binding(3L, "ai_report", "report-1", "alice")))
                // 解除后仍有其他有效引用（不同 id）
                .thenReturn(List.of(
                        binding(3L, "ai_report", "report-1", "alice"),
                        binding(4L, "ai_chat_session", "session-1", "alice")));
        when(bindingMapper.updateWithVersion(any(), any())).thenReturn(1);

        service.release(88L);

        verify(bindingMapper).updateWithVersion(any(), eq(1));
        verify(fileCommonApi, never()).deleteFile(any());

        // 最后一次引用解除前才删除文件（此时删除动作仍在引用有效时执行）
        when(bindingMapper.selectActiveByFile(88L))
                .thenReturn(List.of(binding(3L, "ai_report", "report-1", "alice")))
                .thenReturn(List.of(binding(3L, "ai_report", "report-1", "alice")));
        service.release(88L);
        verify(fileCommonApi).deleteFile(any(FileDeleteReqDTO.class));
    }

    @Test
    void readRechecksAuthorizationEveryTime() {
        loginAs("alice");
        when(bindingMapper.selectActiveByFile(88L)).thenReturn(List.of(binding("ai_report", "report-1", "alice")));
        // 第一次授权通过、第二次被回收：读取必须即时反映
        when(authorizationService.authorize(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(true))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(false));
        when(fileCommonApi.getFileContent(any())).thenReturn(new byte[] {7});

        assertThat(service.read(88L)).containsExactly(7);
        assertThatThrownBy(() -> service.read(88L))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND.getCode());
    }

    @Test
    void listByBusinessReturnsActiveReferencesAndRejectsUnknownType() {
        var first = binding("ai_report", "report-1", "alice");
        var second = binding("ai_report", "report-1", "bob").setId(4L).setFileId(99L);
        when(bindingMapper.selectActiveByBusiness("ai_report", "report-1")).thenReturn(List.of(first, second));

        var references = service.listByBusiness("ai_report", "report-1");

        assertThat(references).extracting("fileId").containsExactly(88L, 99L);
        assertThat(references)
                .allSatisfy(reference -> assertThat(reference.getBusinessType()).isEqualTo("ai_report"));

        assertThatThrownBy(() -> service.listByBusiness("ai_unknown", "report-1"))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
    }

    @Test
    void missingBindingOrSubjectIsTreatedAsNotFound() {
        // 未登录：视为不存在
        assertThatThrownBy(() -> service.read(88L)).isInstanceOf(ServiceException.class);

        loginAs("alice");
        when(bindingMapper.selectActiveByFile(88L)).thenReturn(List.of());
        assertThatThrownBy(() -> service.read(88L))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND.getCode());
    }
}
