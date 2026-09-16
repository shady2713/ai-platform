package com.basicframework.module.system.api.dict;

import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.module.system.api.dict.dto.DictDataRespDTO;
import com.basicframework.module.system.dal.dataobject.dict.DictDataDO;
import com.basicframework.module.system.service.dict.DictDataService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 字典数据 API 实现类
 *
 */
@Service
@RequiredArgsConstructor
public class DictDataApiImpl implements DictDataCommonApi {

    private final DictDataService dictDataService;

    @Override
    public List<DictDataRespDTO> getDictDataList(String dictType) {
        List<DictDataDO> list = dictDataService.getDictDataListByDictType(dictType);
        return BeanUtils.toBean(list, DictDataRespDTO.class);
    }
}
