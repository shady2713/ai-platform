package com.basicframework.module.system.service.dict;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.*;

import cn.hutool.core.util.StrUtil;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.dal.dataobject.dict.DictTypeDO;
import com.basicframework.module.system.dal.mysql.dict.DictDataMapper;
import com.basicframework.module.system.dal.mysql.dict.DictTypeMapper;
import com.google.common.annotations.VisibleForTesting;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 字典类型 Service 实现类
 *
 */
@Service
@RequiredArgsConstructor
public class DictTypeServiceImpl implements DictTypeService {

    private static final LocalDateTime NOT_DELETED_TIME = LocalDateTime.of(1970, 1, 1, 0, 0);

    private final DictDataMapper dictDataMapper;
    private final DictTypeMapper dictTypeMapper;

    @Override
    public PageResult<DictTypeDO> getDictTypePage(
            PageParam pageParam, String name, String type, Integer status, LocalDateTime[] createTime) {
        return dictTypeMapper.selectPage(pageParam, name, type, status, createTime);
    }

    @Override
    public DictTypeDO getDictType(Long id) {
        return dictTypeMapper.selectById(id);
    }

    @Override
    public DictTypeDO getDictType(String type) {
        return dictTypeMapper.selectByType(type);
    }

    @Override
    public Long createDictType(DictTypeDO dictType) {
        // 校验字典类型的名字的唯一性
        validateDictTypeNameUnique(null, dictType.getName());
        // 校验字典类型的类型的唯一性
        validateDictTypeUnique(null, dictType.getType());

        // 插入字典类型
        dictType.setDeletedTime(NOT_DELETED_TIME); // 唯一索引要求未删除记录使用固定时间而非 null
        dictTypeMapper.insert(dictType);
        return dictType.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDictType(DictTypeDO updateObj) {
        // 校验自己存在
        DictTypeDO existing = validateAndLockDictType(updateObj.getId());
        // 校验字典类型的名字的唯一性
        validateDictTypeNameUnique(updateObj.getId(), updateObj.getName());
        // 校验字典类型的类型的唯一性
        validateDictTypeUnique(updateObj.getId(), updateObj.getType());
        if (!Objects.equals(existing.getType(), updateObj.getType())
                && dictDataMapper.selectPhysicalCountByDictType(existing.getType()) > 0) {
            throw exception(DICT_TYPE_CHANGE_HAS_CHILDREN);
        }

        // 更新字典类型
        dictTypeMapper.updateById(updateObj);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDictType(Long id) {
        // 校验是否存在
        DictTypeDO dictType = validateAndLockDictType(id);
        // 校验是否有字典数据
        if (dictDataMapper.selectCountByDictType(dictType.getType()) > 0) {
            throw exception(DICT_TYPE_HAS_CHILDREN);
        }
        // 删除字典类型
        dictTypeMapper.updateToDelete(id, LocalDateTime.now());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDictTypeList(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        if (ids.stream().anyMatch(Objects::isNull)) {
            throw exception(DICT_TYPE_NOT_EXISTS);
        }
        List<Long> dictTypeIds = ids.stream().distinct().sorted().toList();
        List<DictTypeDO> dictTypes =
                dictTypeIds.stream().map(this::validateAndLockDictType).toList();
        // 1. 校验是否有字典数据
        dictTypes.forEach(dictType -> {
            if (dictDataMapper.selectCountByDictType(dictType.getType()) > 0) {
                throw exception(DICT_TYPE_HAS_CHILDREN);
            }
        });

        // 2. 批量删除字典类型
        LocalDateTime now = LocalDateTime.now();
        dictTypeIds.forEach(id -> dictTypeMapper.updateToDelete(id, now));
    }

    @Override
    public List<DictTypeDO> getDictTypeList() {
        return dictTypeMapper.selectList();
    }

    @VisibleForTesting
    void validateDictTypeNameUnique(Long id, String name) {
        DictTypeDO dictType = dictTypeMapper.selectByName(name);
        if (dictType == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的字典类型
        if (id == null) {
            throw exception(DICT_TYPE_NAME_DUPLICATE);
        }
        if (!dictType.getId().equals(id)) {
            throw exception(DICT_TYPE_NAME_DUPLICATE);
        }
    }

    @VisibleForTesting
    void validateDictTypeUnique(Long id, String type) {
        if (StrUtil.isEmpty(type)) {
            return;
        }
        DictTypeDO dictType = dictTypeMapper.selectByType(type);
        if (dictType == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的字典类型
        if (id == null) {
            throw exception(DICT_TYPE_TYPE_DUPLICATE);
        }
        if (!dictType.getId().equals(id)) {
            throw exception(DICT_TYPE_TYPE_DUPLICATE);
        }
    }

    private DictTypeDO validateAndLockDictType(Long id) {
        if (id == null) {
            throw exception(DICT_TYPE_NOT_EXISTS);
        }
        DictTypeDO dictType = dictTypeMapper.selectByIdForUpdate(id);
        if (dictType == null) {
            throw exception(DICT_TYPE_NOT_EXISTS);
        }
        return dictType;
    }

    @VisibleForTesting
    DictTypeDO validateDictTypeExists(Long id) {
        if (id == null) {
            return null;
        }
        DictTypeDO dictType = dictTypeMapper.selectById(id);
        if (dictType == null) {
            throw exception(DICT_TYPE_NOT_EXISTS);
        }
        return dictType;
    }
}
