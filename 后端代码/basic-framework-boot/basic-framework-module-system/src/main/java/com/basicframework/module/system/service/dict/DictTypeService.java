package com.basicframework.module.system.service.dict;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.dal.dataobject.dict.DictTypeDO;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 字典类型 Service 接口
 *
 */
public interface DictTypeService {

    /**
     * 创建字典类型
     *
     * @param dictType 字典类型信息
     * @return 字典类型编号
     */
    Long createDictType(DictTypeDO dictType);

    /**
     * 更新字典类型
     *
     * @param updateObj 字典类型信息
     */
    void updateDictType(DictTypeDO updateObj);

    /**
     * 删除字典类型
     *
     * @param id 字典类型编号
     */
    void deleteDictType(Long id);

    /**
     * 批量删除字典类型
     *
     * @param ids 字典类型编号列表
     */
    void deleteDictTypeList(List<Long> ids);

    /**
     * 获得字典类型分页列表
     *
     * @param pageParam  分页参数
     * @param name       字典类型名称，模糊匹配
     * @param type       字典类型，模糊匹配
     * @param status     展示状态
     * @param createTime 创建时间区间
     * @return 字典类型分页列表
     */
    PageResult<DictTypeDO> getDictTypePage(
            PageParam pageParam, String name, String type, Integer status, LocalDateTime[] createTime);

    /**
     * 获得字典类型详情
     *
     * @param id 字典类型编号
     * @return 字典类型
     */
    DictTypeDO getDictType(Long id);

    /**
     * 获得字典类型详情
     *
     * @param type 字典类型
     * @return 字典类型详情
     */
    DictTypeDO getDictType(String type);

    /**
     * 获得全部字典类型列表
     *
     * @return 字典类型列表
     */
    List<DictTypeDO> getDictTypeList();
}
