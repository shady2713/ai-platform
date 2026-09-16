package com.basicframework.module.system.controller.app.dict;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.module.system.controller.app.dict.vo.AppDictDataRespVO;
import com.basicframework.module.system.dal.dataobject.dict.DictDataDO;
import com.basicframework.module.system.service.dict.DictDataService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link AppDictDataController} 单元测试
 *
 */
@ExtendWith(MockitoExtension.class)
class AppDictDataControllerTest {

    @InjectMocks
    private AppDictDataController controller;

    @Mock
    private DictDataService dictDataService;

    @Test
    void getDictDataListByType_returnsEnabledDictDataOfType() {
        DictDataDO enabled = dictData("启用", "1");
        when(dictDataService.getDictDataList(CommonStatusEnum.ENABLE.getStatus(), "common_status"))
                .thenReturn(List.of(enabled));

        CommonResult<List<AppDictDataRespVO>> result = controller.getDictDataListByType("common_status");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .singleElement()
                .extracting(AppDictDataRespVO::getLabel, AppDictDataRespVO::getValue)
                .containsExactly("启用", "1");
    }

    @Test
    void getDictDataListByType_returnsEmptyListForUnknownType() {
        when(dictDataService.getDictDataList(CommonStatusEnum.ENABLE.getStatus(), "unknown"))
                .thenReturn(List.of());

        CommonResult<List<AppDictDataRespVO>> result = controller.getDictDataListByType("unknown");

        assertThat(result.getData()).isEmpty();
    }

    private static DictDataDO dictData(String label, String value) {
        DictDataDO data = new DictDataDO();
        data.setLabel(label);
        data.setValue(value);
        data.setDictType("common_status");
        data.setStatus(CommonStatusEnum.ENABLE.getStatus());
        return data;
    }
}
