package com.basicframework.module.ai.service.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.grant.AiResourceGrantDO;
import com.basicframework.module.ai.dal.mysql.grant.AiResourceGrantMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A03 授权目录管理：动作白名单校验、唯一性、撤销即递增授权版本（旧判定与旧指纹失效）。
 */
class AiResourceGrantServiceImplTest {

    private AiResourceGrantMapper grantMapper;

    private AiResourceGrantServiceImpl service;

    @BeforeEach
    void setUp() {
        grantMapper = mock(AiResourceGrantMapper.class);
        service = new AiResourceGrantServiceImpl(grantMapper);
    }

    private static AiResourceGrantDO grant(long revision) {
        return new AiResourceGrantDO()
                .setId(3L)
                .setApplicationId(5L)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setResourceType("REPORT")
                .setResourceKey("report-1")
                .setActions("READ")
                .setStatus(AiResourceGrantDO.STATUS_ACTIVE)
                .setAuthzRevision(revision)
                .setVersion(1);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, int code) {
        assertThatThrownBy(callable)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(code);
    }

    @Test
    void createNormalizesActionWhiteListAlphabetically() {
        when(grantMapper.selectGrant(5L, "USER", "alice", "REPORT", "report-1")).thenReturn(null);
        when(grantMapper.insert(any(AiResourceGrantDO.class))).thenAnswer(invocation -> {
            ((AiResourceGrantDO) invocation.getArgument(0)).setId(3L);
            return 1;
        });

        service.createGrant(5L, "USER", "alice", "REPORT", "report-1", Set.of("export", "READ", "read"));

        ArgumentCaptor<AiResourceGrantDO> captor = ArgumentCaptor.forClass(AiResourceGrantDO.class);
        verify(grantMapper).insert(captor.capture());
        assertThat(captor.getValue().getActions()).isEqualTo("EXPORT,READ");
        assertThat(captor.getValue().getAuthzRevision()).isEqualTo(1L);
        assertThat(captor.getValue().getStatus()).isEqualTo(AiResourceGrantDO.STATUS_ACTIVE);
    }

    @Test
    void createRejectsDuplicateUnknownActionsAndAppSubjectWithUserId() {
        when(grantMapper.selectGrant(5L, "USER", "alice", "REPORT", "report-1")).thenReturn(grant(1L));
        assertCode(
                () -> service.createGrant(5L, "USER", "alice", "REPORT", "report-1", Set.of("READ")),
                AiErrorCodeConstants.AI_RESOURCE_GRANT_DUPLICATE.getCode());

        when(grantMapper.selectGrant(5L, "USER", "alice", "REPORT", "report-1")).thenReturn(null);
        assertCode(
                () -> service.createGrant(5L, "USER", "alice", "REPORT", "report-1", Set.of("DELETE")),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                () -> service.createGrant(5L, "APP", "alice", "REPORT", "report-1", Set.of("READ")),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                () -> service.createGrant(5L, "USER", null, "REPORT", "report-1", Set.of("READ")),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                () -> service.createGrant(5L, "USER", "alice", "UNKNOWN", "report-1", Set.of("READ")),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
    }

    @Test
    void updateAndRevokeBumpAuthzRevisionSoOldDecisionsExpire() {
        when(grantMapper.selectById(3L)).thenReturn(grant(4L));
        when(grantMapper.updateWithVersion(any(AiResourceGrantDO.class), any())).thenReturn(1);

        service.updateGrant(3L, 1, Set.of("READ", "EXECUTE"));

        ArgumentCaptor<AiResourceGrantDO> updated = ArgumentCaptor.forClass(AiResourceGrantDO.class);
        verify(grantMapper).updateWithVersion(updated.capture(), any());
        assertThat(updated.getValue().getAuthzRevision()).isEqualTo(5L);
        assertThat(updated.getValue().getActions()).isEqualTo("EXECUTE,READ");

        service.revokeGrant(3L, 2);
        ArgumentCaptor<AiResourceGrantDO> revoked = ArgumentCaptor.forClass(AiResourceGrantDO.class);
        verify(grantMapper, org.mockito.Mockito.times(2)).updateWithVersion(revoked.capture(), any());
        assertThat(revoked.getAllValues().get(1).getStatus()).isEqualTo(AiResourceGrantDO.STATUS_REVOKED);
        assertThat(revoked.getAllValues().get(1).getAuthzRevision()).isEqualTo(5L);

        // CAS 冲突
        when(grantMapper.updateWithVersion(any(AiResourceGrantDO.class), any())).thenReturn(0);
        assertCode(() -> service.revokeGrant(3L, 9), AiErrorCodeConstants.AI_STATE_CONFLICT.getCode());
    }
}
