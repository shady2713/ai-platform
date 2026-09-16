package com.basicframework.module.system.framework.operatelog.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.service.user.AdminUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminUserParseFunctionTest {

    @Mock
    private AdminUserService adminUserService;

    private AdminUserParseFunction parseFunction;

    @BeforeEach
    void setUp() {
        parseFunction = new AdminUserParseFunction(adminUserService);
    }

    @Test
    void functionName_returnsRegisteredName() {
        assertEquals(AdminUserParseFunction.NAME, parseFunction.functionName());
    }

    @Test
    void apply_returnsEmptyForBlankValueWithoutQueryingService() {
        assertEquals("", parseFunction.apply(""));
        verifyNoInteractions(adminUserService);
    }

    @Test
    void apply_returnsEmptyWhenUserDoesNotExist() {
        when(adminUserService.getUser(7L)).thenReturn(null);

        assertEquals("", parseFunction.apply(7L));
    }

    @Test
    void apply_returnsNicknameWhenMobileIsBlank() {
        AdminUserDO user = AdminUserDO.builder().nickname("管理员").mobile("").build();
        when(adminUserService.getUser(7L)).thenReturn(user);

        assertEquals("管理员", parseFunction.apply(7L));
    }

    @Test
    void apply_returnsNicknameAndMaskedMobileWhenMobileExists() {
        AdminUserDO user =
                AdminUserDO.builder().nickname("管理员").mobile("13800000000").build();
        when(adminUserService.getUser(7L)).thenReturn(user);

        assertEquals("管理员(138****0000)", parseFunction.apply("7"));
    }
}
