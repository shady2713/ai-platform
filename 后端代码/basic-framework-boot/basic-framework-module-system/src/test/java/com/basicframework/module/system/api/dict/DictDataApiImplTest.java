package com.basicframework.module.system.api.dict;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.system.api.dict.dto.DictDataRespDTO;
import com.basicframework.module.system.dal.dataobject.dict.DictDataDO;
import com.basicframework.module.system.service.dict.DictDataService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link DictDataApiImpl} 单元测试
 *
 */
@ExtendWith(MockitoExtension.class)
class DictDataApiImplTest {

    @InjectMocks
    private DictDataApiImpl dictDataApi;

    @Mock
    private DictDataService dictDataService;

    @Test
    void getDictDataList_convertsDataObjectsToDtos() {
        DictDataDO enabled = dictData("启用", "1");
        DictDataDO disabled = dictData("禁用", "0");
        when(dictDataService.getDictDataListByDictType("common_status")).thenReturn(List.of(enabled, disabled));

        List<DictDataRespDTO> result = dictDataApi.getDictDataList("common_status");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(DictDataRespDTO::getLabel).containsExactly("启用", "禁用");
        assertThat(result).extracting(DictDataRespDTO::getValue).containsExactly("1", "0");
        assertThat(result).extracting(DictDataRespDTO::getDictType).containsOnly("common_status");
        verify(dictDataService).getDictDataListByDictType("common_status");
    }

    @Test
    void getDictDataList_returnsEmptyListWhenNoData() {
        when(dictDataService.getDictDataListByDictType("empty_type")).thenReturn(List.of());

        assertThat(dictDataApi.getDictDataList("empty_type")).isEmpty();
    }

    private static DictDataDO dictData(String label, String value) {
        DictDataDO data = new DictDataDO();
        data.setLabel(label);
        data.setValue(value);
        data.setDictType("common_status");
        return data;
    }
}
