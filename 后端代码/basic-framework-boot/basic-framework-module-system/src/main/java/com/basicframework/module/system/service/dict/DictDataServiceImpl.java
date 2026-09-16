package com.basicframework.module.system.service.dict;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.*;

import cn.hutool.core.collection.CollUtil;
import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.collection.CollectionUtils;
import com.basicframework.module.system.dal.dataobject.dict.DictDataDO;
import com.basicframework.module.system.dal.dataobject.dict.DictTypeDO;
import com.basicframework.module.system.dal.mysql.dict.DictDataMapper;
import com.basicframework.module.system.dal.mysql.dict.DictTypeMapper;
import com.basicframework.module.system.dal.redis.RedisKeyConstants;
import com.google.common.annotations.VisibleForTesting;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 字典数据 Service 实现类
 *
 * 读路径缓存：两个全量列表查询走 Redis 缓存（键见 {@link RedisKeyConstants}），
 * 写操作整体失效对应缓存区域。
 */
@Service
public class DictDataServiceImpl implements DictDataService {

    /**
     * 排序 dictType > sort
     */
    private static final Comparator<DictDataDO> COMPARATOR_TYPE_AND_SORT =
            Comparator.comparing(DictDataDO::getDictType).thenComparingInt(DictDataDO::getSort);

    private final DictTypeMapper dictTypeMapper;

    private final DictDataMapper dictDataMapper;

    public DictDataServiceImpl(DictTypeMapper dictTypeMapper, DictDataMapper dictDataMapper) {
        this.dictTypeMapper = dictTypeMapper;
        this.dictDataMapper = dictDataMapper;
    }

    @Override
    @Cacheable(cacheNames = RedisKeyConstants.DICT_DATA_LIST, key = "#status + ':' + #dictType")
    public List<DictDataDO> getDictDataList(Integer status, String dictType) {
        List<DictDataDO> list = dictDataMapper.selectListByStatusAndDictType(status, dictType);
        list.sort(COMPARATOR_TYPE_AND_SORT);
        return list;
    }

    @Override
    public PageResult<DictDataDO> getDictDataPage(PageParam pageParam, String label, String dictType, Integer status) {
        return dictDataMapper.selectPage(pageParam, label, dictType, status);
    }

    @Override
    public DictDataDO getDictData(Long id) {
        return dictDataMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    // 状态与类型的组合不可枚举，写操作直接清空两个列表缓存区域
    @Caching(
            evict = {
                @CacheEvict(value = RedisKeyConstants.DICT_DATA_LIST, allEntries = true),
                @CacheEvict(value = RedisKeyConstants.DICT_DATA_LIST_BY_TYPE, allEntries = true)
            })
    public Long createDictData(DictDataDO dictData) {
        // 校验字典类型有效
        validateAndLockDictType(dictData.getDictType());
        // 校验字典数据的值的唯一性
        validateDictDataValueUnique(null, dictData.getDictType(), dictData.getValue());

        // 插入字典类型
        dictDataMapper.insert(dictData);
        return dictData.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    // 状态与类型的组合不可枚举，写操作直接清空两个列表缓存区域
    @Caching(
            evict = {
                @CacheEvict(value = RedisKeyConstants.DICT_DATA_LIST, allEntries = true),
                @CacheEvict(value = RedisKeyConstants.DICT_DATA_LIST_BY_TYPE, allEntries = true)
            })
    public void updateDictData(DictDataDO updateObj) {
        // 校验自己存在
        validateAndLockDictData(updateObj.getId());
        // 校验字典类型有效
        validateAndLockDictType(updateObj.getDictType());
        // 校验字典数据的值的唯一性
        validateDictDataValueUnique(updateObj.getId(), updateObj.getDictType(), updateObj.getValue());

        // 更新字典类型
        dictDataMapper.updateById(updateObj);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    // 状态与类型的组合不可枚举，写操作直接清空两个列表缓存区域
    @Caching(
            evict = {
                @CacheEvict(value = RedisKeyConstants.DICT_DATA_LIST, allEntries = true),
                @CacheEvict(value = RedisKeyConstants.DICT_DATA_LIST_BY_TYPE, allEntries = true)
            })
    public void deleteDictData(Long id) {
        // 校验是否存在
        validateAndLockDictData(id);

        // 删除字典数据
        dictDataMapper.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    // 状态与类型的组合不可枚举，写操作直接清空两个列表缓存区域
    @Caching(
            evict = {
                @CacheEvict(value = RedisKeyConstants.DICT_DATA_LIST, allEntries = true),
                @CacheEvict(value = RedisKeyConstants.DICT_DATA_LIST_BY_TYPE, allEntries = true)
            })
    public void deleteDictDataList(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        if (ids.stream().anyMatch(Objects::isNull)) {
            throw exception(DICT_DATA_NOT_EXISTS);
        }
        List<Long> dictDataIds = ids.stream().distinct().sorted().toList();
        dictDataIds.forEach(this::validateAndLockDictData);
        dictDataMapper.deleteByIds(dictDataIds);
    }

    @Override
    public long getDictDataCountByDictType(String dictType) {
        return dictDataMapper.selectCountByDictType(dictType);
    }

    @VisibleForTesting
    public void validateDictDataValueUnique(Long id, String dictType, String value) {
        DictDataDO dictData = dictDataMapper.selectByDictTypeAndValue(dictType, value);
        if (dictData == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的字典数据
        if (id == null) {
            throw exception(DICT_DATA_VALUE_DUPLICATE);
        }
        if (!dictData.getId().equals(id)) {
            throw exception(DICT_DATA_VALUE_DUPLICATE);
        }
    }

    @VisibleForTesting
    public void validateDictDataExists(Long id) {
        if (id == null) {
            return;
        }
        DictDataDO dictData = dictDataMapper.selectById(id);
        if (dictData == null) {
            throw exception(DICT_DATA_NOT_EXISTS);
        }
    }

    @VisibleForTesting
    public void validateDictTypeExists(String type) {
        DictTypeDO dictType = dictTypeMapper.selectByType(type);
        validateDictTypeEnabled(dictType);
    }

    private void validateAndLockDictType(String type) {
        DictTypeDO dictType = dictTypeMapper.selectByTypeForShare(type);
        validateDictTypeEnabled(dictType);
    }

    private static void validateDictTypeEnabled(DictTypeDO dictType) {
        if (dictType == null) {
            throw exception(DICT_TYPE_NOT_EXISTS);
        }
        if (!CommonStatusEnum.ENABLE.getStatus().equals(dictType.getStatus())) {
            throw exception(DICT_TYPE_NOT_ENABLE);
        }
    }

    private void validateAndLockDictData(Long id) {
        if (id == null || dictDataMapper.selectByIdForUpdate(id) == null) {
            throw exception(DICT_DATA_NOT_EXISTS);
        }
    }

    @Override
    public void validateDictDataList(String dictType, Collection<String> values) {
        if (CollUtil.isEmpty(values)) {
            return;
        }
        Map<String, DictDataDO> dictDataMap = CollectionUtils.convertMap(
                dictDataMapper.selectByDictTypeAndValues(dictType, values), DictDataDO::getValue);
        // 校验
        values.forEach(value -> {
            DictDataDO dictData = dictDataMap.get(value);
            if (dictData == null) {
                throw exception(DICT_DATA_NOT_EXISTS);
            }
            if (!CommonStatusEnum.ENABLE.getStatus().equals(dictData.getStatus())) {
                throw exception(DICT_DATA_NOT_ENABLE, dictData.getLabel());
            }
        });
    }

    @Override
    public DictDataDO getDictData(String dictType, String value) {
        return dictDataMapper.selectByDictTypeAndValue(dictType, value);
    }

    @Override
    public DictDataDO parseDictData(String dictType, String label) {
        return dictDataMapper.selectByDictTypeAndLabel(dictType, label);
    }

    @Override
    @Cacheable(cacheNames = RedisKeyConstants.DICT_DATA_LIST_BY_TYPE, key = "#dictType")
    public List<DictDataDO> getDictDataListByDictType(String dictType) {
        List<DictDataDO> list = dictDataMapper.selectList(DictDataDO::getDictType, dictType);
        list.sort(Comparator.comparing(DictDataDO::getSort));
        return list;
    }
}
