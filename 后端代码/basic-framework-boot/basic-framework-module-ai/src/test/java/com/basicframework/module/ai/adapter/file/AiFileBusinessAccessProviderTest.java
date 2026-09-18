package com.basicframework.module.ai.adapter.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.module.ai.dal.dataobject.file.AiFileBindingDO;
import com.basicframework.module.ai.dal.mysql.file.AiFileBindingMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.authorization.dto.AiAuthorizationDecisionDTO;
import com.basicframework.module.ai.service.file.dto.AiFileSubject;
import com.basicframework.module.infra.api.file.dto.FileBusinessAccessContext;
import com.basicframework.module.infra.api.file.dto.FileSubjectDTO;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A07 infra 业务授权 SPI：输入里没有管理权限（无法伪装）、主体必须可解析、
 * 报表按授权目录判定、会话附件仅所有者，删除一律只认所有者。
 */
class AiFileBusinessAccessProviderTest {

    private AiFileBindingMapper bindingMapper;

    private AiFileSubjectResolver subjectResolver;

    private AiAuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        bindingMapper = mock(AiFileBindingMapper.class);
        subjectResolver = mock(AiFileSubjectResolver.class);
        authorizationService = mock(AiAuthorizationService.class);
    }

    private AiFileBusinessAccessProvider provider(String businessType, AiResourceType resourceType) {
        return new AiFileBusinessAccessProvider(
                businessType, bindingMapper, subjectResolver, authorizationService, resourceType);
    }

    private static FileBusinessAccessContext context(String businessType) {
        FileSubjectDTO subject = new FileSubjectDTO();
        subject.setUserType(UserTypeEnum.MEMBER.getValue());
        subject.setUserId(21L);
        return new FileBusinessAccessContext()
                .setFileId(88L)
                .setBusinessType(businessType)
                .setBusinessId(0L)
                .setSubject(subject);
    }

    private static AiFileBindingDO binding(String businessType, String owner) {
        return new AiFileBindingDO()
                .setId(3L)
                .setFileId(88L)
                .setBusinessType(businessType)
                .setBusinessKey("report-1")
                .setApplicationId(5L)
                .setSubjectType("USER")
                .setExternalUserId(owner)
                .setStatus(AiFileBindingDO.STATUS_ACTIVE);
    }

    private static AiFileSubject subject(String externalUserId) {
        return new AiFileSubject(5L, AiSubjectType.USER, externalUserId, 21L);
    }

    @Test
    void reportAccessDelegatesToAuthorizationCatalogue() {
        AiFileBusinessAccessProvider provider = provider("ai_report", AiResourceType.REPORT);
        when(subjectResolver.resolveByTicket(21L)).thenReturn(Optional.of(subject("alice")));
        when(bindingMapper.selectActiveByFile(88L)).thenReturn(List.of(binding("ai_report", "alice")));
        when(authorizationService.authorize(
                        eq(5L),
                        eq("USER"),
                        eq("alice"),
                        eq(AiResourceType.REPORT),
                        eq("report-1"),
                        eq(AiAction.READ),
                        any()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(true));

        assertThat(provider.canRead(context("ai_report"))).isTrue();

        when(authorizationService.authorize(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(false));
        assertThat(provider.canRead(context("ai_report"))).isFalse();
    }

    @Test
    void chatSessionAccessIsOwnerOnlyForReadAndDelete() {
        AiFileBusinessAccessProvider provider = provider("ai_chat_session", null);
        when(bindingMapper.selectActiveByFile(88L)).thenReturn(List.of(binding("ai_chat_session", "alice")));

        when(subjectResolver.resolveByTicket(21L)).thenReturn(Optional.of(subject("alice")));
        assertThat(provider.canRead(context("ai_chat_session"))).isTrue();
        assertThat(provider.canDelete(context("ai_chat_session"))).isTrue();

        when(subjectResolver.resolveByTicket(21L)).thenReturn(Optional.of(subject("bob")));
        assertThat(provider.canRead(context("ai_chat_session"))).isFalse();
        assertThat(provider.canDelete(context("ai_chat_session"))).isFalse();
    }

    @Test
    void deleteIsNeverGrantedByReadCapabilityOrForeignSource() {
        AiFileBusinessAccessProvider provider = provider("ai_report", AiResourceType.REPORT);
        when(subjectResolver.resolveByTicket(21L)).thenReturn(Optional.of(subject("bob")));
        when(bindingMapper.selectActiveByFile(88L)).thenReturn(List.of(binding("ai_report", "alice")));
        // 即使授权目录允许 bob 读取，也不能删除（删除只认所有者）
        when(authorizationService.authorize(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(true));

        assertThat(provider.canRead(context("ai_report"))).isTrue();
        assertThat(provider.canDelete(context("ai_report"))).isFalse();
    }

    @Test
    void unresolvableSubjectWrongTypeOrMissingBindingIsDeniedWithoutSideEffects() {
        AiFileBusinessAccessProvider provider = provider("ai_report", AiResourceType.REPORT);

        // 主体无法解析（票据无效/不是 MEMBER）
        when(subjectResolver.resolveByTicket(21L)).thenReturn(Optional.empty());
        assertThat(provider.canRead(context("ai_report"))).isFalse();
        assertThat(provider.canDelete(context("ai_report"))).isFalse();

        // 业务类型不匹配
        when(subjectResolver.resolveByTicket(21L)).thenReturn(Optional.of(subject("alice")));
        assertThat(provider.canRead(context("ai_chat_session"))).isFalse();

        // 没有有效绑定
        when(bindingMapper.selectActiveByFile(88L)).thenReturn(List.of());
        assertThat(provider.canRead(context("ai_report"))).isFalse();

        // 上下文缺失
        assertThat(provider.canRead(null)).isFalse();
        assertThat(provider.canRead(context("ai_report").setFileId(null))).isFalse();

        verify(authorizationService, never()).authorize(any(), any(), any(), any(), any(), any(), any());
        assertThat(provider.getBusinessType()).isEqualTo("ai_report");
    }
}
