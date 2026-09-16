package com.basicframework.module.system.service.dict;

import static com.basicframework.module.system.testutil.ServiceExceptionAssert.assertServiceException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.dal.dataobject.dict.DictDataDO;
import com.basicframework.module.system.dal.dataobject.dict.DictTypeDO;
import com.basicframework.module.system.dal.mysql.dict.DictDataMapper;
import com.basicframework.module.system.dal.mysql.dict.DictTypeMapper;
import com.basicframework.module.system.dal.redis.RedisKeyConstants;
import com.basicframework.module.system.enums.ErrorCodeConstants;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;

/**
 * 字典数据 Service 单元测试
 *
 * 覆盖字典数据的查询/创建/更新/删除主链路与三处业务校验：
 * 字典类型必须存在且启用、字典数据值唯一、批量校验字典数据有效（存在 + 启用）。
 */
@ExtendWith(MockitoExtension.class)
class DictDataServiceImplTest {

    private static final String DICT_TYPE = "dict_type";
    private static final String DICT_VALUE = "dict_value";

    @InjectMocks
    private DictDataServiceImpl dictDataService;

    @Mock
    private DictTypeMapper dictTypeMapper;

    @Mock
    private DictDataMapper dictDataMapper;

    private DictDataDO dictData;
    private DictTypeDO enabledDictType;

    @BeforeEach
    void setUp() {
        dictData = dictData(1L, DICT_TYPE, DICT_VALUE, CommonStatusEnum.ENABLE.getStatus(), 1);
        enabledDictType = dictType(1L, DICT_TYPE, CommonStatusEnum.ENABLE.getStatus());
    }

    @Test
    void getDictDataList_sortsByDictTypeThenSort() {
        DictDataDO a1 = dictData(11L, "b", "v", CommonStatusEnum.ENABLE.getStatus(), 5);
        DictDataDO a2 = dictData(12L, "a", "v", CommonStatusEnum.ENABLE.getStatus(), 3);
        DictDataDO a3 = dictData(13L, "a", "v", CommonStatusEnum.ENABLE.getStatus(), 1);
        when(dictDataMapper.selectListByStatusAndDictType(CommonStatusEnum.ENABLE.getStatus(), DICT_TYPE))
                .thenReturn(new ArrayList<>(List.of(a1, a2, a3)));

        List<DictDataDO> result = dictDataService.getDictDataList(CommonStatusEnum.ENABLE.getStatus(), DICT_TYPE);

        assertThat(result).containsExactly(a3, a2, a1);
    }

    @Test
    void getDictDataPage_delegatesAndReturns() {
        PageParam pageParam = new PageParam();
        PageResult<DictDataDO> pageResult = new PageResult<>(List.of(dictData), 1L);
        when(dictDataMapper.selectPage(pageParam, null, DICT_TYPE, CommonStatusEnum.ENABLE.getStatus()))
                .thenReturn(pageResult);

        PageResult<DictDataDO> result =
                dictDataService.getDictDataPage(pageParam, null, DICT_TYPE, CommonStatusEnum.ENABLE.getStatus());

        assertThat(result).isSameAs(pageResult);
    }

    @Test
    void getDictData_byId_returns() {
        when(dictDataMapper.selectById(1L)).thenReturn(dictData);

        assertThat(dictDataService.getDictData(1L)).isSameAs(dictData);
    }

    @Test
    void createDictData_insertsAndReturnsId() {
        when(dictTypeMapper.selectByTypeForShare(DICT_TYPE)).thenReturn(enabledDictType);
        when(dictDataMapper.selectByDictTypeAndValue(DICT_TYPE, DICT_VALUE)).thenReturn(null);

        Long id = dictDataService.createDictData(dictData);

        assertThat(id).isEqualTo(dictData.getId());
        verify(dictDataMapper).insert(dictData);
    }

    @Test
    void createDictData_typeNotExists_throws() {
        when(dictTypeMapper.selectByTypeForShare(DICT_TYPE)).thenReturn(null);

        assertServiceException(
                ErrorCodeConstants.DICT_TYPE_NOT_EXISTS.getCode(), () -> dictDataService.createDictData(dictData));
        verify(dictDataMapper, never()).insert(any(DictDataDO.class));
    }

    @Test
    void createDictData_typeDisabled_throws() {
        when(dictTypeMapper.selectByTypeForShare(DICT_TYPE))
                .thenReturn(dictType(1L, DICT_TYPE, CommonStatusEnum.DISABLE.getStatus()));

        assertServiceException(
                ErrorCodeConstants.DICT_TYPE_NOT_ENABLE.getCode(), () -> dictDataService.createDictData(dictData));
        verify(dictDataMapper, never()).insert(any(DictDataDO.class));
    }

    @Test
    void createDictData_valueDuplicate_throws() {
        when(dictTypeMapper.selectByTypeForShare(DICT_TYPE)).thenReturn(enabledDictType);
        when(dictDataMapper.selectByDictTypeAndValue(DICT_TYPE, DICT_VALUE))
                .thenReturn(dictData(2L, DICT_TYPE, DICT_VALUE, CommonStatusEnum.ENABLE.getStatus(), 2));

        assertServiceException(
                ErrorCodeConstants.DICT_DATA_VALUE_DUPLICATE.getCode(), () -> dictDataService.createDictData(dictData));
        verify(dictDataMapper, never()).insert(any(DictDataDO.class));
    }

    @Test
    void updateDictData_notExists_throws() {
        when(dictDataMapper.selectByIdForUpdate(1L)).thenReturn(null);

        assertServiceException(
                ErrorCodeConstants.DICT_DATA_NOT_EXISTS.getCode(), () -> dictDataService.updateDictData(dictData));
    }

    @Test
    void updateDictData_valueTakenByOther_throws() {
        when(dictDataMapper.selectByIdForUpdate(1L)).thenReturn(dictData);
        when(dictTypeMapper.selectByTypeForShare(DICT_TYPE)).thenReturn(enabledDictType);
        when(dictDataMapper.selectByDictTypeAndValue(DICT_TYPE, DICT_VALUE))
                .thenReturn(dictData(2L, DICT_TYPE, DICT_VALUE, CommonStatusEnum.ENABLE.getStatus(), 2));

        assertServiceException(
                ErrorCodeConstants.DICT_DATA_VALUE_DUPLICATE.getCode(), () -> dictDataService.updateDictData(dictData));
        verify(dictDataMapper, never()).updateById(any(DictDataDO.class));
    }

    @Test
    void updateDictData_success() {
        when(dictDataMapper.selectByIdForUpdate(1L)).thenReturn(dictData);
        when(dictTypeMapper.selectByTypeForShare(DICT_TYPE)).thenReturn(enabledDictType);
        when(dictDataMapper.selectByDictTypeAndValue(DICT_TYPE, DICT_VALUE)).thenReturn(dictData);

        dictDataService.updateDictData(dictData);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(dictDataMapper, dictTypeMapper);
        inOrder.verify(dictDataMapper).selectByIdForUpdate(1L);
        inOrder.verify(dictTypeMapper).selectByTypeForShare(DICT_TYPE);
        verify(dictDataMapper).updateById(dictData);
    }

    @Test
    void deleteDictData_notExists_throws() {
        when(dictDataMapper.selectByIdForUpdate(1L)).thenReturn(null);

        assertServiceException(
                ErrorCodeConstants.DICT_DATA_NOT_EXISTS.getCode(), () -> dictDataService.deleteDictData(1L));
    }

    @Test
    void deleteDictData_success() {
        when(dictDataMapper.selectByIdForUpdate(1L)).thenReturn(dictData);

        dictDataService.deleteDictData(1L);

        verify(dictDataMapper).deleteById(1L);
    }

    @Test
    void deleteDictDataList_batchDeletes() {
        List<Long> ids = List.of(2L, 1L, 2L);
        DictDataDO second = dictData(2L, DICT_TYPE, "second", CommonStatusEnum.ENABLE.getStatus(), 2);
        when(dictDataMapper.selectByIdForUpdate(1L)).thenReturn(dictData);
        when(dictDataMapper.selectByIdForUpdate(2L)).thenReturn(second);

        dictDataService.deleteDictDataList(ids);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(dictDataMapper);
        inOrder.verify(dictDataMapper).selectByIdForUpdate(1L);
        inOrder.verify(dictDataMapper).selectByIdForUpdate(2L);
        verify(dictDataMapper).deleteByIds(List.of(1L, 2L));
    }

    @Test
    void getDictDataCountByDictType_returnsCount() {
        when(dictDataMapper.selectCountByDictType(DICT_TYPE)).thenReturn(5L);

        assertThat(dictDataService.getDictDataCountByDictType(DICT_TYPE)).isEqualTo(5L);
    }

    @Test
    void validateDictDataList_empty_doesNothing() {
        assertThatCode(() -> dictDataService.validateDictDataList(DICT_TYPE, List.of()))
                .doesNotThrowAnyException();
        verify(dictDataMapper, never()).selectByDictTypeAndValues(eq(DICT_TYPE), any());
    }

    @Test
    void validateDictDataList_valueNotExists_throws() {
        DictDataDO exist = dictData(1L, DICT_TYPE, "exist", CommonStatusEnum.ENABLE.getStatus(), 1);
        List<String> values = List.of("exist", "missing");
        when(dictDataMapper.selectByDictTypeAndValues(DICT_TYPE, values)).thenReturn(List.of(exist));

        assertServiceException(
                ErrorCodeConstants.DICT_DATA_NOT_EXISTS.getCode(),
                () -> dictDataService.validateDictDataList(DICT_TYPE, values));
    }

    @Test
    void validateDictDataList_valueDisabled_throws() {
        DictDataDO disabled = dictData(1L, DICT_TYPE, "value", CommonStatusEnum.DISABLE.getStatus(), 1);
        List<String> values = List.of("value");
        when(dictDataMapper.selectByDictTypeAndValues(DICT_TYPE, values)).thenReturn(List.of(disabled));

        assertServiceException(
                ErrorCodeConstants.DICT_DATA_NOT_ENABLE.getCode(),
                () -> dictDataService.validateDictDataList(DICT_TYPE, values));
    }

    @Test
    void validateDictDataList_allValid_passes() {
        DictDataDO a = dictData(1L, DICT_TYPE, "a", CommonStatusEnum.ENABLE.getStatus(), 1);
        DictDataDO b = dictData(2L, DICT_TYPE, "b", CommonStatusEnum.ENABLE.getStatus(), 2);
        List<String> values = List.of("a", "b");
        when(dictDataMapper.selectByDictTypeAndValues(DICT_TYPE, values)).thenReturn(List.of(a, b));

        assertThatCode(() -> dictDataService.validateDictDataList(DICT_TYPE, values))
                .doesNotThrowAnyException();
    }

    @Test
    void getDictData_byTypeAndValue_returns() {
        when(dictDataMapper.selectByDictTypeAndValue(DICT_TYPE, DICT_VALUE)).thenReturn(dictData);

        assertThat(dictDataService.getDictData(DICT_TYPE, DICT_VALUE)).isSameAs(dictData);
    }

    @Test
    void parseDictData_returns() {
        when(dictDataMapper.selectByDictTypeAndLabel(DICT_TYPE, "label")).thenReturn(dictData);

        assertThat(dictDataService.parseDictData(DICT_TYPE, "label")).isSameAs(dictData);
    }

    @Test
    void getDictDataListByDictType_sortsBySort() {
        DictDataDO low = dictData(1L, DICT_TYPE, "v", CommonStatusEnum.ENABLE.getStatus(), 1);
        DictDataDO high = dictData(2L, DICT_TYPE, "v", CommonStatusEnum.ENABLE.getStatus(), 3);
        when(dictDataMapper.selectList(org.mockito.ArgumentMatchers.<SFunction<DictDataDO, ?>>any(), eq(DICT_TYPE)))
                .thenReturn(new ArrayList<>(List.of(high, low)));

        List<DictDataDO> result = dictDataService.getDictDataListByDictType(DICT_TYPE);

        assertThat(result).containsExactly(low, high);
    }

    @Test
    void validateDictDataExists_nullId_doesNothing() {
        assertThatCode(() -> dictDataService.validateDictDataExists(null)).doesNotThrowAnyException();
        verify(dictDataMapper, never()).selectById(any());
    }

    @Test
    void validateDictDataValueUnique_sameId_passes() {
        when(dictDataMapper.selectByDictTypeAndValue(DICT_TYPE, DICT_VALUE)).thenReturn(dictData);

        assertThatCode(() -> dictDataService.validateDictDataValueUnique(1L, DICT_TYPE, DICT_VALUE))
                .doesNotThrowAnyException();
    }

    // ---------- helpers ----------

    private DictDataDO dictData(Long id, String dictType, String value, Integer status, Integer sort) {
        DictDataDO data = new DictDataDO();
        data.setId(id);
        data.setDictType(dictType);
        data.setValue(value);
        data.setStatus(status);
        data.setSort(sort);
        return data;
    }

    private DictTypeDO dictType(Long id, String type, Integer status) {
        DictTypeDO dictType = new DictTypeDO();
        dictType.setId(id);
        dictType.setType(type);
        dictType.setStatus(status);
        return dictType;
    }

    @Test
    void deleteDictDataList_emptyOrNull_doesNothing() {
        assertThatCode(() -> dictDataService.deleteDictDataList(List.of())).doesNotThrowAnyException();
        assertThatCode(() -> dictDataService.deleteDictDataList(null)).doesNotThrowAnyException();

        verify(dictDataMapper, never()).deleteByIds(any());
    }

    // ---------- 缓存契约：钉住读缓存与写失效的注解约定，行为验证见 CacheAndProtectionIT ----------

    @Test
    void listQueries_declareRedisCacheContracts() throws Exception {
        Cacheable listCache = DictDataServiceImpl.class
                .getMethod("getDictDataList", Integer.class, String.class)
                .getAnnotation(Cacheable.class);
        assertThat(listCache).isNotNull();
        assertThat(listCache.cacheNames()).containsExactly(RedisKeyConstants.DICT_DATA_LIST);
        assertThat(listCache.key()).isEqualTo("#status + ':' + #dictType");

        Cacheable typeListCache = DictDataServiceImpl.class
                .getMethod("getDictDataListByDictType", String.class)
                .getAnnotation(Cacheable.class);
        assertThat(typeListCache).isNotNull();
        assertThat(typeListCache.cacheNames()).containsExactly(RedisKeyConstants.DICT_DATA_LIST_BY_TYPE);
        assertThat(typeListCache.key()).isEqualTo("#dictType");
    }

    @Test
    void writeOperations_evictBothListCacheRegions() throws Exception {
        assertEvictsBothListCaches(DictDataServiceImpl.class.getMethod("createDictData", DictDataDO.class));
        assertEvictsBothListCaches(DictDataServiceImpl.class.getMethod("updateDictData", DictDataDO.class));
        assertEvictsBothListCaches(DictDataServiceImpl.class.getMethod("deleteDictData", Long.class));
        assertEvictsBothListCaches(DictDataServiceImpl.class.getMethod("deleteDictDataList", List.class));
    }

    private static void assertEvictsBothListCaches(Method method) {
        Caching caching = method.getAnnotation(Caching.class);
        assertThat(caching).as("%s 应声明 @Caching 失效", method.getName()).isNotNull();
        assertThat(caching.evict())
                .as("%s 应整体失效两个字典列表缓存", method.getName())
                .allSatisfy(evict -> {
                    assertThat(evict.allEntries()).isTrue();
                    assertThat(evict.value())
                            .containsAnyOf(RedisKeyConstants.DICT_DATA_LIST, RedisKeyConstants.DICT_DATA_LIST_BY_TYPE);
                })
                .hasSize(2);
    }
}
