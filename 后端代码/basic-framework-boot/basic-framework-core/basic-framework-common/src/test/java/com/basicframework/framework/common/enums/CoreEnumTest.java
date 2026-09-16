package com.basicframework.framework.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CoreEnumTest {

    @Test
    void commonStatus_predicatesHandleKnownUnknownAndNullValues() {
        assertThat(CommonStatusEnum.isEnable(CommonStatusEnum.ENABLE.getStatus()))
                .isTrue();
        assertThat(CommonStatusEnum.isDisable(CommonStatusEnum.DISABLE.getStatus()))
                .isTrue();
        assertThat(CommonStatusEnum.isEnable(99)).isFalse();
        assertThat(CommonStatusEnum.isDisable(null)).isFalse();
        assertThat(CommonStatusEnum.ENABLE.getName()).isEqualTo("开启");
    }

    @Test
    void commonStatus_arrayReturnsDefensiveCopy() {
        Integer[] first = CommonStatusEnum.ENABLE.array();
        first[0] = 99;

        assertThat(CommonStatusEnum.ENABLE.array()).containsExactly(0, 1);
    }

    @Test
    void userType_resolvesKnownValuesAndRejectsUnknownValues() {
        assertThat(UserTypeEnum.valueOf(UserTypeEnum.SYSTEM.getValue())).isEqualTo(UserTypeEnum.SYSTEM);
        assertThat(UserTypeEnum.valueOf(UserTypeEnum.MEMBER.getValue())).isEqualTo(UserTypeEnum.MEMBER);
        assertThat(UserTypeEnum.valueOf(UserTypeEnum.ADMIN.getValue())).isEqualTo(UserTypeEnum.ADMIN);
        assertThat(UserTypeEnum.valueOf(99)).isNull();
        assertThat(UserTypeEnum.valueOf((Integer) null)).isNull();
        assertThat(UserTypeEnum.ADMIN.getName()).isEqualTo("管理员");
    }

    @Test
    void userType_arrayReturnsDefensiveCopy() {
        Integer[] first = UserTypeEnum.ADMIN.array();
        first[0] = 99;

        assertThat(UserTypeEnum.ADMIN.array()).containsExactly(0, 1, 2);
    }
}
