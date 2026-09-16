package com.basicframework.module.system.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.system.api.logger.OperateLogCommonApi;
import com.basicframework.module.system.api.logger.dto.OperateLogCreateReqDTO;
import com.basicframework.module.system.api.permission.dto.DeptDataPermissionRespDTO;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SystemApiContractTest {

    @Test
    void shouldDelegateAsyncOperationLogCreation() {
        AtomicReference<OperateLogCreateReqDTO> captured = new AtomicReference<>();
        OperateLogCommonApi api = captured::set;
        OperateLogCreateReqDTO request = new OperateLogCreateReqDTO();

        api.createOperateLogAsync(request);

        assertThat(captured).hasValue(request);
    }

    @Test
    void shouldCreateRestrictiveDataPermissionByDefault() {
        DeptDataPermissionRespDTO permission = new DeptDataPermissionRespDTO();

        assertThat(permission.getAll()).isFalse();
        assertThat(permission.getSelf()).isFalse();
        assertThat(permission.getDeptIds()).isEmpty();
    }
}
