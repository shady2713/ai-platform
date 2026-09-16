package com.basicframework.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.exception.ErrorCode;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;

/**
 * 错误码全库唯一性门禁
 *
 * 扫描 classpath 上所有 com.basicframework.**.enums 包下的 *ErrorCodeConstants 类，
 * 断言其中 ErrorCode 类型静态字段的数值全库唯一，且 code=0 仅属于全局 SUCCESS。
 * 错误码由所属模块分段维护；本测试阻断跨模块或模块内重复编号。
 *
 * 本测试放在 server 模块：它是唯一依赖全部业务模块的装配入口，
 * 能覆盖到所有模块的 ErrorCodeConstants。
 */
class ErrorCodeUniquenessTest {

    private static final String SCAN_PATTERN = "classpath*:com/basicframework/**/enums/*ErrorCodeConstants.class";

    private static final int SUCCESS_CODE = 0;

    private static final String GLOBAL_SUCCESS_HOLDER =
            "com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants#SUCCESS";

    @Test
    void errorCodes_areGloballyUnique() throws Exception {
        Map<Integer, List<String>> holdersByCode = collectErrorCodeHolders();
        // 防呆：扫描不到或数量异常说明扫描模式失效，门禁必须失败而不是静默放行
        assertThat(holdersByCode).as("应扫描到错误码注册表类").isNotEmpty();
        assertThat(countConstants(holdersByCode)).as("应扫描到全部模块的错误码").isGreaterThan(100);

        Map<Integer, List<String>> duplicates = findDuplicates(holdersByCode);
        assertThat(duplicates).as("错误码数值必须全库唯一，重复项：%s", duplicates).isEmpty();

        List<String> successHolders = holdersByCode.getOrDefault(SUCCESS_CODE, List.of());
        assertThat(successHolders)
                .as("code=0 仅允许出现在 GlobalErrorCodeConstants.SUCCESS，实际：%s", successHolders)
                .allMatch(GLOBAL_SUCCESS_HOLDER::equals);
    }

    private Map<Integer, List<String>> collectErrorCodeHolders() throws Exception {
        Map<Integer, List<String>> holdersByCode = new LinkedHashMap<>();
        for (String className : scanRegistryClassNames()) {
            Class<?> registryClass = Class.forName(className);
            for (Field field : registryClass.getDeclaredFields()) {
                if (isErrorCodeConstant(field)) {
                    ErrorCode errorCode = (ErrorCode) field.get(null);
                    holdersByCode
                            .computeIfAbsent(errorCode.getCode(), key -> new ArrayList<>())
                            .add(className + '#' + field.getName());
                }
            }
        }
        return holdersByCode;
    }

    private Set<String> scanRegistryClassNames() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory readerFactory = new CachingMetadataReaderFactory(resolver);
        // 用 Set 去重：reactor 中同一类可能同时来自 target/classes 与已安装 jar
        Set<String> classNames = new TreeSet<>();
        for (Resource resource : resolver.getResources(SCAN_PATTERN)) {
            classNames.add(
                    readerFactory.getMetadataReader(resource).getClassMetadata().getClassName());
        }
        return classNames;
    }

    private static boolean isErrorCodeConstant(Field field) {
        return Modifier.isStatic(field.getModifiers()) && ErrorCode.class.isAssignableFrom(field.getType());
    }

    private static Map<Integer, List<String>> findDuplicates(Map<Integer, List<String>> holdersByCode) {
        Map<Integer, List<String>> duplicates = new LinkedHashMap<>();
        holdersByCode.forEach((code, holders) -> {
            if (holders.size() > 1) {
                duplicates.put(code, holders);
            }
        });
        return duplicates;
    }

    private static int countConstants(Map<Integer, List<String>> holdersByCode) {
        return holdersByCode.values().stream().mapToInt(List::size).sum();
    }
}
