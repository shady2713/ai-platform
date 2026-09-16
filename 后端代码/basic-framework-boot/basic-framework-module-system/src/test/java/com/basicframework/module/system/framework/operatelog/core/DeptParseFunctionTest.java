package com.basicframework.module.system.framework.operatelog.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.module.system.dal.dataobject.dept.DeptDO;
import com.basicframework.module.system.service.dept.DeptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeptParseFunctionTest {

    @Mock
    private DeptService deptService;

    private DeptParseFunction parseFunction;

    @BeforeEach
    void setUp() {
        parseFunction = new DeptParseFunction(deptService);
    }

    @Test
    void functionName_returnsRegisteredName() {
        assertEquals(DeptParseFunction.NAME, parseFunction.functionName());
    }

    @Test
    void apply_returnsEmptyForNullValueWithoutQueryingService() {
        assertEquals("", parseFunction.apply(null));
        verifyNoInteractions(deptService);
    }

    @Test
    void apply_returnsEmptyWhenDepartmentDoesNotExist() {
        when(deptService.getDept(8L)).thenReturn(null);

        assertEquals("", parseFunction.apply(8L));
    }

    @Test
    void apply_returnsDepartmentName() {
        DeptDO dept = new DeptDO();
        dept.setName("研发部");
        when(deptService.getDept(8L)).thenReturn(dept);

        assertEquals("研发部", parseFunction.apply("8"));
    }
}
