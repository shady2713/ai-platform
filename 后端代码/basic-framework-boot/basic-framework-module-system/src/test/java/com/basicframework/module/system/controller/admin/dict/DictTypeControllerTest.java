package com.basicframework.module.system.controller.admin.dict;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.framework.excel.core.util.ExcelUtils;
import com.basicframework.module.system.controller.admin.dict.vo.type.DictTypePageReqVO;
import com.basicframework.module.system.controller.admin.dict.vo.type.DictTypeRespVO;
import com.basicframework.module.system.controller.admin.dict.vo.type.DictTypeSaveReqVO;
import com.basicframework.module.system.dal.dataobject.dict.DictTypeDO;
import com.basicframework.module.system.service.dict.DictTypeService;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

class DictTypeControllerTest {

    private final DictTypeService dictTypeService = mock(DictTypeService.class);
    private final DictTypeController controller = new DictTypeController(dictTypeService);

    @Test
    void mutationsMapRequestsAndDelegateExactTargets() {
        DictTypeSaveReqVO request = saveRequest();
        when(dictTypeService.createDictType(any(DictTypeDO.class))).thenReturn(9L);

        assertThat(controller.createDictType(request).getData()).isEqualTo(9L);
        assertThat(controller.updateDictType(request).getData()).isTrue();
        assertThat(controller.deleteDictType(7L).getData()).isTrue();
        assertThat(controller.deleteDictTypeList(List.of(7L, 8L)).getData()).isTrue();

        ArgumentCaptor<DictTypeDO> captor = ArgumentCaptor.forClass(DictTypeDO.class);
        verify(dictTypeService).createDictType(captor.capture());
        assertThat(captor.getValue())
                .extracting(DictTypeDO::getId, DictTypeDO::getName, DictTypeDO::getType, DictTypeDO::getStatus)
                .containsExactly(7L, "业务状态", "business_status", 0);
        verify(dictTypeService).updateDictType(any(DictTypeDO.class));
        verify(dictTypeService).deleteDictType(7L);
        verify(dictTypeService).deleteDictTypeList(List.of(7L, 8L));
    }

    @Test
    void queriesMapResultsAndPreserveEveryFilter() {
        DictTypeDO type = type();
        DictTypePageReqVO request = pageRequest();
        when(dictTypeService.getDictType(7L)).thenReturn(type);
        when(dictTypeService.getDictTypeList()).thenReturn(List.of(type));
        when(dictTypeService.getDictTypePage(any(), any(), any(), any(), any()))
                .thenReturn(new PageResult<>(List.of(type), 1L));

        assertThat(controller.getDictType(7L).getData().getType()).isEqualTo("business_status");
        assertThat(controller.getSimpleDictTypeList().getData())
                .extracting("id")
                .containsExactly(7L);
        assertThat(controller.pageDictTypes(request).getData().getTotal()).isEqualTo(1L);

        verify(dictTypeService)
                .getDictTypePage(
                        same(request),
                        same(request.getName()),
                        same(request.getType()),
                        same(request.getStatus()),
                        same(request.getCreateTime()));
    }

    @Test
    void exportAppliesHardLimitAndWritesThePublishedSpreadsheet() throws Exception {
        DictTypePageReqVO request = pageRequest();
        DictTypeDO type = type();
        List<DictTypeRespVO> expectedRows = BeanUtils.toBean(List.of(type), DictTypeRespVO.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(dictTypeService.getDictTypePage(any(), any(), any(), any(), any()))
                .thenReturn(new PageResult<>(List.of(type), 1L));

        try (MockedStatic<ExcelUtils> excelUtils = mockStatic(ExcelUtils.class)) {
            controller.export(response, request);

            excelUtils.verify(() -> ExcelUtils.write(response, "字典类型.xls", "数据", DictTypeRespVO.class, expectedRows));
        }

        assertThat(request.getPageSize()).isEqualTo(PageParam.EXPORT_MAX_PAGE_SIZE);
    }

    @Test
    void publicContractUsesCanonicalSimpleRouteAndDedicatedExportPermission() throws Exception {
        Method simpleList = DictTypeController.class.getMethod("getSimpleDictTypeList");
        Method export =
                DictTypeController.class.getMethod("export", HttpServletResponse.class, DictTypePageReqVO.class);

        assertThat(simpleList.getAnnotation(GetMapping.class).value()).containsExactly("/simple-list");
        assertThat(export.getAnnotation(PreAuthorize.class).value()).contains("system:dict:export");
    }

    private static DictTypeSaveReqVO saveRequest() {
        DictTypeSaveReqVO request = new DictTypeSaveReqVO();
        request.setId(7L);
        request.setName("业务状态");
        request.setType("business_status");
        request.setStatus(0);
        request.setRemark("业务状态字典");
        return request;
    }

    private static DictTypeDO type() {
        return new DictTypeDO()
                .setId(7L)
                .setName("业务状态")
                .setType("business_status")
                .setStatus(0);
    }

    private static DictTypePageReqVO pageRequest() {
        DictTypePageReqVO request = new DictTypePageReqVO();
        request.setName("业务");
        request.setType("business_status");
        request.setStatus(0);
        request.setCreateTime(new LocalDateTime[] {LocalDateTime.now().minusDays(1), LocalDateTime.now()});
        return request;
    }
}
