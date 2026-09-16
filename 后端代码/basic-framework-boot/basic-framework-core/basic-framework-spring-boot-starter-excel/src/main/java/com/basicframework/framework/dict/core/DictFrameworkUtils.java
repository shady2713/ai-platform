package com.basicframework.framework.dict.core;

import static com.basicframework.framework.common.util.collection.CollectionUtils.convertList;

import cn.hutool.core.collection.CollUtil;
import com.basicframework.framework.common.util.cache.CacheUtils;
import com.basicframework.module.system.api.dict.DictDataCommonApi;
import com.basicframework.module.system.api.dict.dto.DictDataRespDTO;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * 字典工具类
 *
 */
@Slf4j
public class DictFrameworkUtils {

    /**
     * 针对 dictType 的字段数据缓存
     */
    private static volatile LoadingCache<String, List<DictDataRespDTO>> dictDataCache;

    public static synchronized void init(DictDataCommonApi dictDataApi, Duration refreshAfterWrite) {
        Objects.requireNonNull(dictDataApi, "dictDataApi must not be null");
        Objects.requireNonNull(refreshAfterWrite, "refreshAfterWrite must not be null");
        if (refreshAfterWrite.isZero() || refreshAfterWrite.isNegative()) {
            throw new IllegalArgumentException("refreshAfterWrite must be positive");
        }
        dictDataCache = CacheUtils.buildAsyncReloadingCache(
                refreshAfterWrite, new CacheLoader<String, List<DictDataRespDTO>>() {

                    @Override
                    public List<DictDataRespDTO> load(String dictType) {
                        return dictDataApi.getDictDataList(dictType);
                    }
                });
        log.info("[init][初始化 DictFrameworkUtils 成功]");
    }

    public static void clearCache() {
        cache().invalidateAll();
    }

    @SneakyThrows
    public static String parseDictDataLabel(String dictType, Integer value) {
        if (value == null) {
            return null;
        }
        return parseDictDataLabel(dictType, String.valueOf(value));
    }

    @SneakyThrows
    public static String parseDictDataLabel(String dictType, String value) {
        List<DictDataRespDTO> dictDatas = cache().get(dictType);
        DictDataRespDTO dictData = CollUtil.findOne(dictDatas, data -> Objects.equals(data.getValue(), value));
        return dictData != null ? dictData.getLabel() : null;
    }

    @SneakyThrows
    public static List<String> getDictDataLabelList(String dictType) {
        List<DictDataRespDTO> dictDatas = cache().get(dictType);
        return convertList(dictDatas, DictDataRespDTO::getLabel);
    }

    @SneakyThrows
    public static String parseDictDataValue(String dictType, String label) {
        List<DictDataRespDTO> dictDatas = cache().get(dictType);
        DictDataRespDTO dictData = CollUtil.findOne(dictDatas, data -> Objects.equals(data.getLabel(), label));
        return dictData != null ? dictData.getValue() : null;
    }

    @SneakyThrows
    public static List<String> getDictDataValueList(String dictType) {
        List<DictDataRespDTO> dictDatas = cache().get(dictType);
        return convertList(dictDatas, DictDataRespDTO::getValue);
    }

    private static LoadingCache<String, List<DictDataRespDTO>> cache() {
        return Objects.requireNonNull(dictDataCache, "DictFrameworkUtils 尚未初始化");
    }
}
