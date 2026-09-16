package com.basicframework.framework.web.core.handler;

import com.basicframework.framework.common.exception.ErrorCode;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;

/**
 * Registry mapping error codes to their constant names, built once by scanning every
 * {@code *ErrorCodeConstants} class on the classpath. It backs the suffix-based HTTP status
 * derivation of {@link GlobalExceptionHandler#resolveHttpStatus(Integer)} (ADR 0003).
 *
 * Global uniqueness of error codes is enforced by ErrorCodeUniquenessTest in the server
 * module; on a duplicate the first scanned name wins. An empty scan fails fast, because a
 * broken scan pattern must never degrade every business error to the 422 default silently.
 */
final class ErrorCodeNameRegistry {

    private static final String SCAN_PATTERN = "classpath*:com/basicframework/**/enums/*ErrorCodeConstants.class";

    private static final Map<Integer, String> NAMES_BY_CODE = scan();

    private ErrorCodeNameRegistry() {}

    static String nameOf(Integer code) {
        return code == null ? null : NAMES_BY_CODE.get(code);
    }

    /**
     * Force initialization, so a broken registry fails at startup instead of at first request.
     */
    static void init() {
        // Touch the holder; the static initializer does the work.
    }

    private static Map<Integer, String> scan() {
        Map<Integer, String> names = new HashMap<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            MetadataReaderFactory readerFactory = new CachingMetadataReaderFactory(resolver);
            for (Resource resource : resolver.getResources(SCAN_PATTERN)) {
                String className = readerFactory
                        .getMetadataReader(resource)
                        .getClassMetadata()
                        .getClassName();
                collectConstants(Class.forName(className), names);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to scan error code registries with pattern " + SCAN_PATTERN, ex);
        }
        if (names.isEmpty()) {
            throw new IllegalStateException("No error code registries found with pattern " + SCAN_PATTERN);
        }
        return names;
    }

    private static void collectConstants(Class<?> registryClass, Map<Integer, String> names) {
        for (Field field : registryClass.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !ErrorCode.class.isAssignableFrom(field.getType())) {
                continue;
            }
            ErrorCode errorCode;
            try {
                errorCode = (ErrorCode) field.get(null);
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException("Failed to read error code constant " + field, ex);
            }
            names.putIfAbsent(errorCode.getCode(), field.getName());
        }
    }
}
