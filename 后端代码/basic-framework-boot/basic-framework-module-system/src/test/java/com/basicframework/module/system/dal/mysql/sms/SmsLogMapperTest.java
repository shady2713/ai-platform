package com.basicframework.module.system.dal.mysql.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.module.system.dal.dataobject.sms.SmsLogDO;
import com.basicframework.module.system.enums.sms.SmsReceiveStatusEnum;
import org.junit.jupiter.api.Test;

class SmsLogMapperTest {

    @Test
    void updateReceiveResultBuildsProviderBoundUpdateWithOptionalLocalId() {
        SmsLogMapper mapper = mock(SmsLogMapper.class, CALLS_REAL_METHODS);
        SmsLogDO update = new SmsLogDO().setReceiveStatus(10);
        doReturn(1).when(mapper).update(eq(update), any(LambdaUpdateWrapper.class));

        assertThat(mapper.updateReceiveResult(update, 7L, "ALIYUN", "provider-serial"))
                .isEqualTo(1);
        assertThat(mapper.updateReceiveResult(update, null, "ALIYUN", "provider-serial"))
                .isEqualTo(1);
        update.setReceiveStatus(SmsReceiveStatusEnum.FAILURE.getStatus());
        assertThat(mapper.updateReceiveResult(update, 8L, "ALIYUN", "provider-failure"))
                .isEqualTo(1);

        verify(mapper, org.mockito.Mockito.times(3)).update(eq(update), any(LambdaUpdateWrapper.class));
    }

    @Test
    void existsByProviderCorrelationRequiresOneMatchingLog() {
        SmsLogMapper mapper = mock(SmsLogMapper.class, CALLS_REAL_METHODS);
        doReturn(1L).when(mapper).selectCount(any(LambdaUpdateWrapper.class));

        assertThat(mapper.existsByProviderCorrelation(null, "ALIYUN", "provider-serial"))
                .isTrue();
    }

    @Test
    void updateReceiveResultRejectsNonTerminalStatus() {
        SmsLogMapper mapper = mock(SmsLogMapper.class, CALLS_REAL_METHODS);
        SmsLogDO update = new SmsLogDO().setReceiveStatus(SmsReceiveStatusEnum.INIT.getStatus());

        assertThatThrownBy(() -> mapper.updateReceiveResult(update, 7L, "ALIYUN", "provider-serial"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
