package com.basicframework.module.system.controller.admin.dict;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.framework.excel.core.util.ExcelUtils;
import com.basicframework.module.system.controller.admin.dict.vo.data.DictDataPageReqVO;
import com.basicframework.module.system.controller.admin.dict.vo.data.DictDataRespVO;
import com.basicframework.module.system.controller.admin.dict.vo.data.DictDataSaveReqVO;
import com.basicframework.module.system.dal.dataobject.dict.DictDataDO;
import com.basicframework.module.system.service.dict.DictDataService;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;

class DictDataControllerTest {

    private final DictDataService dictDataService = mock(DictDataService.class);
    private final DictDataController controller = new DictDataController(dictDataService);

    @Test
    void mutationsMapRequestsAndDelegateExactTargets() {
        DictDataSaveReqVO request = saveRequest();
        when(dictDataService.createDictData(any(DictDataDO.class))).thenReturn(9L);

        assertThat(controller.createDictData(request).getData()).isEqualTo(9L);
        assertThat(controller.updateDictData(request).getData()).isTrue();
        assertThat(controller.deleteDictData(7L).getData()).isTrue();
        assertThat(controller.deleteDictDataList(List.of(7L, 8L)).getData()).isTrue();

        ArgumentCaptor<DictDataDO> captor = ArgumentCaptor.forClass(DictDataDO.class);
        verify(dictDataService).createDictData(captor.capture());
        assertThat(captor.getValue())
                .extracting(DictDataDO::getId, DictDataDO::getLabel, DictDataDO::getValue, DictDataDO::getDictType)
                .containsExactly(7L, "启用", "enabled", "business_status");
        verify(dictDataService).updateDictData(any(DictDataDO.class));
        verify(dictDataService).deleteDictData(7L);
        verify(dictDataService).deleteDictDataList(List.of(7L, 8L));
    }

    @Test
    void queriesMapResultsAndSimpleListIncludesOnlyEnabledData() {
        DictDataDO data = data();
        DictDataPageReqVO request = pageRequest();
        when(dictDataService.getDictData(7L)).thenReturn(data);
        when(dictDataService.getDictDataList(CommonStatusEnum.ENABLE.getStatus(), null))
                .thenReturn(List.of(data));
        when(dictDataService.getDictDataPage(any(), any(), any(), any()))
                .thenReturn(new PageResult<>(List.of(data), 1L));

        assertThat(controller.getDictData(7L).getData().getValue()).isEqualTo("enabled");
        assertThat(controller.getSimpleDictDataList().getData())
                .extracting("value")
                .containsExactly("enabled");
        assertThat(controller.getDictDataPage(request).getData().getTotal()).isEqualTo(1L);

        verify(dictDataService).getDictDataList(CommonStatusEnum.ENABLE.getStatus(), null);
        verify(dictDataService)
                .getDictDataPage(
                        same(request),
                        same(request.getLabel()),
                        same(request.getDictType()),
                        same(request.getStatus()));
    }

    @Test
    void exportAppliesHardLimitAndWritesThePublishedSpreadsheet() throws Exception {
        DictDataPageReqVO request = pageRequest();
        DictDataDO data = data();
        List<DictDataRespVO> expectedRows = BeanUtils.toBean(List.of(data), DictDataRespVO.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(dictDataService.getDictDataPage(any(), any(), any(), any()))
                .thenReturn(new PageResult<>(List.of(data), 1L));

        try (MockedStatic<ExcelUtils> excelUtils = mockStatic(ExcelUtils.class)) {
            controller.export(response, request);

            excelUtils.verify(() -> ExcelUtils.write(response, "字典数据.xls", "数据", DictDataRespVO.class, expectedRows));
        }

        assertThat(request.getPageSize()).isEqualTo(PageParam.EXPORT_MAX_PAGE_SIZE);
    }

    @Test
    void publicContractUsesCanonicalSimpleRouteAndDataSpecificPageMethod() throws Exception {
        Method simpleList = DictDataController.class.getMethod("getSimpleDictDataList");
        Method page = DictDataController.class.getMethod("getDictDataPage", DictDataPageReqVO.class);

        assertThat(simpleList.getAnnotation(GetMapping.class).value()).containsExactly("/simple-list");
        assertThat(page.getName()).isEqualTo("getDictDataPage");
    }

    private static DictDataSaveReqVO saveRequest() {
        DictDataSaveReqVO request = new DictDataSaveReqVO();
        request.setId(7L);
        request.setSort(1);
        request.setLabel("启用");
        request.setValue("enabled");
        request.setDictType("business_status");
        request.setStatus(CommonStatusEnum.ENABLE.getStatus());
        return request;
    }

    private static DictDataDO data() {
        return new DictDataDO()
                .setId(7L)
                .setSort(1)
                .setLabel("启用")
                .setValue("enabled")
                .setDictType("business_status")
                .setStatus(CommonStatusEnum.ENABLE.getStatus());
    }

    private static DictDataPageReqVO pageRequest() {
        DictDataPageReqVO request = new DictDataPageReqVO();
        request.setLabel("启用");
        request.setDictType("business_status");
        request.setStatus(CommonStatusEnum.ENABLE.getStatus());
        return request;
    }
}
