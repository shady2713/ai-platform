package com.basicframework.module.system.service.dict;

import static com.basicframework.module.system.testutil.ServiceExceptionAssert.assertServiceException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.dal.dataobject.dict.DictTypeDO;
import com.basicframework.module.system.dal.mysql.dict.DictDataMapper;
import com.basicframework.module.system.dal.mysql.dict.DictTypeMapper;
import com.basicframework.module.system.enums.ErrorCodeConstants;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 字典类型 Service 单元测试
 *
 * 覆盖字典类型的查询/创建/更新/删除主链路与四处业务校验：
 * 名字唯一、类型唯一、删除前校验无子字典数据、状态校验。
 */
@ExtendWith(MockitoExtension.class)
class DictTypeServiceImplTest {

    @InjectMocks
    private DictTypeServiceImpl dictTypeService;

    @Mock
    private DictDataMapper dictDataMapper;

    @Mock
    private DictTypeMapper dictTypeMapper;

    private DictTypeDO dictType;

    @BeforeEach
    void setUp() {
        dictType = dictType(1L, "测试字典", "dict_type", CommonStatusEnum.ENABLE.getStatus());
    }

    @Test
    void getDictTypePage_delegatesAndReturns() {
        PageParam pageParam = new PageParam();
        PageResult<DictTypeDO> pageResult = new PageResult<>(List.of(dictType), 1L);
        when(dictTypeMapper.selectPage(pageParam, null, null, CommonStatusEnum.ENABLE.getStatus(), null))
                .thenReturn(pageResult);

        PageResult<DictTypeDO> result =
                dictTypeService.getDictTypePage(pageParam, null, null, CommonStatusEnum.ENABLE.getStatus(), null);

        assertThat(result).isSameAs(pageResult);
    }

    @Test
    void getDictType_byId_returns() {
        when(dictTypeMapper.selectById(1L)).thenReturn(dictType);

        assertThat(dictTypeService.getDictType(1L)).isSameAs(dictType);
    }

    @Test
    void getDictType_byType_returns() {
        when(dictTypeMapper.selectByType("dict_type")).thenReturn(dictType);

        assertThat(dictTypeService.getDictType("dict_type")).isSameAs(dictType);
    }

    @Test
    void createDictType_insertsAndReturnsId() {
        when(dictTypeMapper.selectByName(dictType.getName())).thenReturn(null);
        when(dictTypeMapper.selectByType(dictType.getType())).thenReturn(null);

        Long id = dictTypeService.createDictType(dictType);

        assertThat(id).isEqualTo(dictType.getId());
        verify(dictTypeMapper).insert(dictType);
    }

    @Test
    void createDictType_nameDuplicate_throws() {
        when(dictTypeMapper.selectByName(dictType.getName()))
                .thenReturn(dictType(2L, dictType.getName(), "other", CommonStatusEnum.ENABLE.getStatus()));

        assertServiceException(
                ErrorCodeConstants.DICT_TYPE_NAME_DUPLICATE.getCode(), () -> dictTypeService.createDictType(dictType));
        verify(dictTypeMapper, never()).insert(any(DictTypeDO.class));
    }

    @Test
    void createDictType_typeDuplicate_throws() {
        when(dictTypeMapper.selectByName(dictType.getName())).thenReturn(null);
        when(dictTypeMapper.selectByType(dictType.getType()))
                .thenReturn(dictType(2L, "其他", dictType.getType(), CommonStatusEnum.ENABLE.getStatus()));

        assertServiceException(
                ErrorCodeConstants.DICT_TYPE_TYPE_DUPLICATE.getCode(), () -> dictTypeService.createDictType(dictType));
        verify(dictTypeMapper, never()).insert(any(DictTypeDO.class));
    }

    @Test
    void updateDictType_notExists_throws() {
        when(dictTypeMapper.selectByIdForUpdate(1L)).thenReturn(null);

        assertServiceException(
                ErrorCodeConstants.DICT_TYPE_NOT_EXISTS.getCode(), () -> dictTypeService.updateDictType(dictType));
    }

    @Test
    void updateDictType_nameTakenByOther_throws() {
        when(dictTypeMapper.selectByIdForUpdate(1L)).thenReturn(dictType);
        when(dictTypeMapper.selectByName(dictType.getName()))
                .thenReturn(dictType(2L, dictType.getName(), "other", CommonStatusEnum.ENABLE.getStatus()));

        assertServiceException(
                ErrorCodeConstants.DICT_TYPE_NAME_DUPLICATE.getCode(), () -> dictTypeService.updateDictType(dictType));
    }

    @Test
    void updateDictType_typeTakenByOther_throws() {
        when(dictTypeMapper.selectByIdForUpdate(1L)).thenReturn(dictType);
        when(dictTypeMapper.selectByName(dictType.getName())).thenReturn(null);
        when(dictTypeMapper.selectByType(dictType.getType()))
                .thenReturn(dictType(2L, "其他", dictType.getType(), CommonStatusEnum.ENABLE.getStatus()));

        assertServiceException(
                ErrorCodeConstants.DICT_TYPE_TYPE_DUPLICATE.getCode(), () -> dictTypeService.updateDictType(dictType));
    }

    @Test
    void updateDictType_success() {
        when(dictTypeMapper.selectByIdForUpdate(1L)).thenReturn(dictType);
        when(dictTypeMapper.selectByName(dictType.getName())).thenReturn(dictType);
        when(dictTypeMapper.selectByType(dictType.getType())).thenReturn(dictType);

        dictTypeService.updateDictType(dictType);

        verify(dictTypeMapper).updateById(dictType);
    }

    @Test
    void updateDictType_changedTypeWithPhysicalChildren_throwsStableError() {
        DictTypeDO update = dictType(1L, dictType.getName(), "renamed_type", dictType.getStatus());
        when(dictTypeMapper.selectByIdForUpdate(1L)).thenReturn(dictType);
        when(dictTypeMapper.selectByName(update.getName())).thenReturn(dictType);
        when(dictTypeMapper.selectByType(update.getType())).thenReturn(null);
        when(dictDataMapper.selectPhysicalCountByDictType(dictType.getType())).thenReturn(1L);

        assertServiceException(
                ErrorCodeConstants.DICT_TYPE_CHANGE_HAS_CHILDREN.getCode(),
                () -> dictTypeService.updateDictType(update));
        verify(dictTypeMapper, never()).updateById(any(DictTypeDO.class));
    }

    @Test
    void updateDictType_changedTypeWithoutChildren_updates() {
        DictTypeDO update = dictType(1L, dictType.getName(), "renamed_type", dictType.getStatus());
        when(dictTypeMapper.selectByIdForUpdate(1L)).thenReturn(dictType);
        when(dictTypeMapper.selectByName(update.getName())).thenReturn(dictType);
        when(dictTypeMapper.selectByType(update.getType())).thenReturn(null);
        when(dictDataMapper.selectPhysicalCountByDictType(dictType.getType())).thenReturn(0L);

        dictTypeService.updateDictType(update);

        verify(dictTypeMapper).updateById(update);
    }

    @Test
    void deleteDictType_notExists_throws() {
        when(dictTypeMapper.selectByIdForUpdate(1L)).thenReturn(null);

        assertServiceException(
                ErrorCodeConstants.DICT_TYPE_NOT_EXISTS.getCode(), () -> dictTypeService.deleteDictType(1L));
    }

    @Test
    void deleteDictType_hasChildren_throws() {
        when(dictTypeMapper.selectByIdForUpdate(1L)).thenReturn(dictType);
        when(dictDataMapper.selectCountByDictType(dictType.getType())).thenReturn(1L);

        assertServiceException(
                ErrorCodeConstants.DICT_TYPE_HAS_CHILDREN.getCode(), () -> dictTypeService.deleteDictType(1L));
        verify(dictTypeMapper, never()).updateToDelete(any(Long.class), any(LocalDateTime.class));
    }

    @Test
    void deleteDictType_success() {
        when(dictTypeMapper.selectByIdForUpdate(1L)).thenReturn(dictType);
        when(dictDataMapper.selectCountByDictType(dictType.getType())).thenReturn(0L);

        dictTypeService.deleteDictType(1L);

        verify(dictTypeMapper).updateToDelete(eq(1L), any(LocalDateTime.class));
    }

    @Test
    void deleteDictTypeList_hasChildren_throws() {
        List<Long> ids = List.of(1L, 2L);
        when(dictTypeMapper.selectByIdForUpdate(1L)).thenReturn(dictType);
        when(dictTypeMapper.selectByIdForUpdate(2L))
                .thenReturn(dictType(2L, "二", "two", CommonStatusEnum.ENABLE.getStatus()));
        when(dictDataMapper.selectCountByDictType("dict_type")).thenReturn(1L);

        assertServiceException(
                ErrorCodeConstants.DICT_TYPE_HAS_CHILDREN.getCode(), () -> dictTypeService.deleteDictTypeList(ids));
        verify(dictTypeMapper, never()).updateToDelete(any(Long.class), any(LocalDateTime.class));
    }

    @Test
    void deleteDictTypeList_batchDeletes() {
        List<Long> ids = List.of(2L, 1L, 2L);
        when(dictTypeMapper.selectByIdForUpdate(1L)).thenReturn(dictType);
        when(dictTypeMapper.selectByIdForUpdate(2L))
                .thenReturn(dictType(2L, "二", "two", CommonStatusEnum.ENABLE.getStatus()));
        when(dictDataMapper.selectCountByDictType("dict_type")).thenReturn(0L);
        when(dictDataMapper.selectCountByDictType("two")).thenReturn(0L);

        dictTypeService.deleteDictTypeList(ids);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(dictTypeMapper);
        inOrder.verify(dictTypeMapper).selectByIdForUpdate(1L);
        inOrder.verify(dictTypeMapper).selectByIdForUpdate(2L);
        verify(dictTypeMapper, times(2)).updateToDelete(any(Long.class), any(LocalDateTime.class));
    }

    @Test
    void getDictTypeList_returnsList() {
        List<DictTypeDO> list = List.of(dictType);
        when(dictTypeMapper.selectList()).thenReturn(list);

        assertThat(dictTypeService.getDictTypeList()).isSameAs(list);
    }

    @Test
    void validateDictTypeNameUnique_sameId_passes() {
        when(dictTypeMapper.selectByName(dictType.getName())).thenReturn(dictType);

        assertThatCode(() -> dictTypeService.validateDictTypeNameUnique(1L, dictType.getName()))
                .doesNotThrowAnyException();
    }

    @Test
    void validateDictTypeUnique_emptyType_passes() {
        assertThatCode(() -> dictTypeService.validateDictTypeUnique(null, "")).doesNotThrowAnyException();
        verify(dictTypeMapper, never()).selectByType(any());
    }

    @Test
    void validateDictTypeExists_nullId_returnsNull() {
        assertThat(dictTypeService.validateDictTypeExists(null)).isNull();
        verify(dictTypeMapper, never()).selectById(any());
    }

    // ---------- helpers ----------

    private DictTypeDO dictType(Long id, String name, String type, Integer status) {
        DictTypeDO dictType = new DictTypeDO();
        dictType.setId(id);
        dictType.setName(name);
        dictType.setType(type);
        dictType.setStatus(status);
        return dictType;
    }
}
