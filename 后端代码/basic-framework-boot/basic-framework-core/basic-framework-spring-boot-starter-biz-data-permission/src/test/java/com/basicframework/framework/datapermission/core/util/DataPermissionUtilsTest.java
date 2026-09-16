package com.basicframework.framework.datapermission.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.datapermission.core.annotation.DataPermission;
import com.basicframework.framework.datapermission.core.aop.DataPermissionContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DataPermissionUtilsTest {

    @AfterEach
    void clearContext() {
        DataPermissionContextHolder.clear();
    }

    @Test
    void executeIgnoreRunnable_pushesDisableScopeAndAlwaysRemovesIt() {
        DataPermissionUtils.executeIgnore(() -> {
            DataPermission scope = DataPermissionContextHolder.get();
            assertThat(scope).isNotNull();
            assertThat(scope.enable()).isFalse();
        });

        assertThat(DataPermissionContextHolder.get()).isNull();
    }

    @Test
    void executeIgnoreCallable_returnsValueAndCleansUpAfterFailure() {
        assertThat(DataPermissionUtils.executeIgnore(() -> "result")).isEqualTo("result");

        assertThatThrownBy(() -> DataPermissionUtils.executeIgnore((java.util.concurrent.Callable<Void>) () -> {
                    throw new IllegalStateException("expected failure");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("expected failure");
        assertThat(DataPermissionContextHolder.get()).isNull();
    }
}
