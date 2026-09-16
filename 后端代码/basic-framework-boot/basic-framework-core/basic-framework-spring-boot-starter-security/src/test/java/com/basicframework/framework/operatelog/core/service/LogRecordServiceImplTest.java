package com.basicframework.framework.operatelog.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;
import com.basicframework.module.system.api.logger.OperateLogCommonApi;
import com.basicframework.module.system.api.logger.dto.OperateLogCreateReqDTO;
import com.mzt.logapi.beans.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
class LogRecordServiceImplTest {

    @Mock
    private OperateLogCommonApi operateLogApi;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void record_authenticatedWebRequest_mapsAuditContext() {
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/admin-api/system/user/7");
        request.setRemoteAddr("192.0.2.8");
        request.addHeader("User-Agent", "audit-test-agent");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        SecurityFrameworkUtils.setLoginUser(
                new LoginUser().setId(7L).setUserType(UserTypeEnum.ADMIN.getValue()), request);
        LogRecord record = logRecord("7");

        new LogRecordServiceImpl(operateLogApi).record(record);

        ArgumentCaptor<OperateLogCreateReqDTO> captor = ArgumentCaptor.forClass(OperateLogCreateReqDTO.class);
        verify(operateLogApi).createOperateLogAsync(captor.capture());
        OperateLogCreateReqDTO dto = captor.getValue();
        assertThat(dto.getUserId()).isEqualTo(7L);
        assertThat(dto.getUserType()).isEqualTo(UserTypeEnum.ADMIN.getValue());
        assertThat(dto.getRequestMethod()).isEqualTo("PATCH");
        assertThat(dto.getRequestUrl()).isEqualTo("/admin-api/system/user/7");
        assertThat(dto.getUserIp()).isEqualTo("192.0.2.8");
        assertThat(dto.getUserAgent()).isEqualTo("audit-test-agent");
        assertThat(dto.getType()).isEqualTo("SYSTEM_USER");
        assertThat(dto.getSubType()).isEqualTo("UPDATE");
        assertThat(dto.getBizId()).isEqualTo(7L);
        assertThat(dto.getAction()).isEqualTo("updated user");
        assertThat(dto.getExtra()).isEqualTo("{\"source\":\"test\"}");
    }

    @Test
    void record_nonWebSystemAction_usesSystemActorAndNoRequestMetadata() {
        new LogRecordServiceImpl(operateLogApi).record(logRecord("9"));

        ArgumentCaptor<OperateLogCreateReqDTO> captor = ArgumentCaptor.forClass(OperateLogCreateReqDTO.class);
        verify(operateLogApi).createOperateLogAsync(captor.capture());
        assertThat(captor.getValue().getUserId()).isNull();
        assertThat(captor.getValue().getUserType()).isEqualTo(UserTypeEnum.SYSTEM.getValue());
        assertThat(captor.getValue().getRequestUrl()).isNull();
    }

    @Test
    void record_ordinaryAuditFailure_doesNotBreakBusinessFlow() {
        doThrow(new IllegalStateException("audit unavailable"))
                .when(operateLogApi)
                .createOperateLogAsync(any(OperateLogCreateReqDTO.class));

        assertThatCode(() -> new LogRecordServiceImpl(operateLogApi).record(logRecord("11")))
                .doesNotThrowAnyException();
    }

    @Test
    void record_jvmError_isNotSwallowed() {
        doThrow(new AssertionError("fatal"))
                .when(operateLogApi)
                .createOperateLogAsync(any(OperateLogCreateReqDTO.class));

        assertThatThrownBy(() -> new LogRecordServiceImpl(operateLogApi).record(logRecord("11")))
                .isInstanceOf(AssertionError.class)
                .hasMessage("fatal");
    }

    @Test
    void unsupportedSdkQueries_failExplicitly() {
        LogRecordServiceImpl service = new LogRecordServiceImpl(operateLogApi);

        assertThatThrownBy(() -> service.queryLog("7", "SYSTEM_USER"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> service.queryLogByBizNo("7", "SYSTEM_USER", "UPDATE"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static LogRecord logRecord(String bizNo) {
        LogRecord record = new LogRecord();
        record.setType("SYSTEM_USER");
        record.setSubType("UPDATE");
        record.setBizNo(bizNo);
        record.setAction("updated user");
        record.setExtra("{\"source\":\"test\"}");
        return record;
    }
}
