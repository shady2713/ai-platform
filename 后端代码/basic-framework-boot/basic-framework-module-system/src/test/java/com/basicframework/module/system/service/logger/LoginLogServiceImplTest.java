package com.basicframework.module.system.service.logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.dal.dataobject.logger.LoginLogDO;
import com.basicframework.module.system.dal.mysql.logger.LoginLogMapper;
import com.basicframework.module.system.enums.logger.LoginResultEnum;
import com.basicframework.module.system.service.logger.dto.LoginLogCreateReqDTO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * {@link LoginLogServiceImpl} 单元测试
 *
 */
@ExtendWith(MockitoExtension.class)
class LoginLogServiceImplTest {

    @InjectMocks
    private LoginLogServiceImpl loginLogService;

    @Mock
    private LoginLogMapper loginLogMapper;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Test
    void getLoginLog_delegatesToMapper() {
        LoginLogDO loginLog = new LoginLogDO();
        loginLog.setId(1L);
        when(loginLogMapper.selectById(1L)).thenReturn(loginLog);

        assertThat(loginLogService.getLoginLog(1L)).isSameAs(loginLog);
    }

    @Test
    void getLoginLogPage_delegatesToMapper() {
        PageResult<LoginLogDO> pageResult = new PageResult<>(List.of(new LoginLogDO()), 1L);
        when(loginLogMapper.selectPage(any(PageParam.class), any(), any(), any(), any()))
                .thenReturn(pageResult);

        assertThat(loginLogService.getLoginLogPage(new PageParam(), "127.0.0.1", "shady", null, true))
                .isSameAs(pageResult);
    }

    @ParameterizedTest
    @EnumSource(
            value = LoginResultEnum.class,
            names = {"SUCCESS", "BAD_CREDENTIALS"})
    void createLoginLog_mapsFieldsAndCommitsWithTheRequiredAuditBoundary(LoginResultEnum result) {
        SimpleTransactionStatus transaction = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any())).thenReturn(transaction);
        LoginLogCreateReqDTO reqDTO = new LoginLogCreateReqDTO();
        reqDTO.setLogType(0);
        reqDTO.setTraceId("trace-1");
        reqDTO.setUserId(100L);
        reqDTO.setUserType(1);
        reqDTO.setUsername("shady");
        reqDTO.setResult(result.getResult());
        reqDTO.setUserIp("127.0.0.1");
        reqDTO.setUserAgent("curl/8.0");

        loginLogService.createLoginLog(reqDTO);

        ArgumentCaptor<LoginLogDO> captor = ArgumentCaptor.forClass(LoginLogDO.class);
        verify(loginLogMapper).insert(captor.capture());
        LoginLogDO saved = captor.getValue();
        assertThat(saved.getLogType()).isZero();
        assertThat(saved.getTraceId()).isEqualTo("trace-1");
        assertThat(saved.getUserId()).isEqualTo(100L);
        assertThat(saved.getUserType()).isEqualTo(1);
        assertThat(saved.getUsername()).isEqualTo("shady");
        assertThat(saved.getResult()).isEqualTo(result.getResult());
        assertThat(saved.getUserIp()).isEqualTo("127.0.0.1");
        assertThat(saved.getUserAgent()).isEqualTo("curl/8.0");
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(
                        result == LoginResultEnum.SUCCESS
                                ? TransactionDefinition.PROPAGATION_REQUIRED
                                : TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verify(transactionManager).commit(transaction);
        verify(transactionManager, never()).rollback(any());
    }

    @Test
    void createLoginLog_successAuditStorageFailureRollsBackAndRemainsVisibleToLogin() {
        SimpleTransactionStatus transaction = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any())).thenReturn(transaction);
        IllegalStateException failure = new IllegalStateException("login audit storage unavailable");
        when(loginLogMapper.insert(any(LoginLogDO.class))).thenThrow(failure);
        LoginLogCreateReqDTO request = new LoginLogCreateReqDTO();
        request.setResult(LoginResultEnum.SUCCESS.getResult());

        assertThatThrownBy(() -> loginLogService.createLoginLog(request)).isSameAs(failure);

        verify(transactionManager).rollback(transaction);
        verify(transactionManager, never()).commit(any());
    }
}
