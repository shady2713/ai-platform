package com.basicframework.module.ai.service.query.crosssource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import org.junit.jupiter.api.Test;

/**
 * 跨源实体键与预算的形状校验（Y04）。
 *
 * <p>这两类是纯函数，且**每个 throw 分支都可能被门禁按行覆盖率算成漏测**，
 * 因此这里把非法输入逐条打掉：键值为空/超长/含特殊字符、版本非正数、
 * 跨版本合并、预算各字段越界与自相矛盾。
 */
class CrossSourceEntityKeyTest {

    @Test
    void acceptsAWellFormedKey() {
        CrossSourceEntityKey key = new CrossSourceEntityKey("C-1001", 1L);

        assertThat(key.keyValue()).isEqualTo("C-1001");
        assertThat(key.mappingRevision()).isEqualTo(1L);
        assertThat(key.describe()).isEqualTo("C-1001@r1");
    }

    @Test
    void rejectsMissingOrMalformedKeyValues() {
        assertCode(
                () -> new CrossSourceEntityKey(null, 1L),
                AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_MISSING_CONFLICT);
        assertCode(
                () -> new CrossSourceEntityKey("", 1L),
                AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_MISSING_CONFLICT);
        assertCode(
                () -> new CrossSourceEntityKey("a".repeat(129), 1L),
                AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_MISSING_CONFLICT);
        // 注入面：键值只允许字面量标识符字符
        assertCode(
                () -> new CrossSourceEntityKey("C-1' OR '1'='1", 1L),
                AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_MISSING_CONFLICT);
        assertCode(
                () -> new CrossSourceEntityKey("C 1001;DROP", 1L),
                AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_MISSING_CONFLICT);
    }

    @Test
    void rejectsNonPositiveMappingRevision() {
        assertCode(
                () -> new CrossSourceEntityKey("C-1001", null),
                AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_MISSING_CONFLICT);
        assertCode(
                () -> new CrossSourceEntityKey("C-1001", 0L),
                AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_MISSING_CONFLICT);
        assertCode(
                () -> new CrossSourceEntityKey("C-1001", -1L),
                AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_MISSING_CONFLICT);
    }

    @Test
    void joinAcceptsSameKeyAtSameRevision() {
        CrossSourceEntityKey left = new CrossSourceEntityKey("C-1001", 1L);
        CrossSourceEntityKey right = new CrossSourceEntityKey("C-1001", 1L);

        assertThat(CrossSourceEntityKey.join(left, right)).isEqualTo(left);
        assertThat(left.sameMappingRevision(right)).isTrue();
        assertThat(left.sameMappingRevision(null)).isFalse();
    }

    @Test
    void joinRefusesDifferentMappingRevisions() {
        // 同一编号在两个映射版本下可能指向不同统一对象：跨版本合并会并掉两份事实
        CrossSourceEntityKey v1 = new CrossSourceEntityKey("C-1001", 1L);
        CrossSourceEntityKey v2 = new CrossSourceEntityKey("C-1001", 2L);

        assertThat(v1.sameMappingRevision(v2)).isFalse();
        assertCode(
                () -> CrossSourceEntityKey.join(v1, v2),
                AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_REVISION_CONFLICT);
        assertCode(
                () -> CrossSourceEntityKey.join(v1, null),
                AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_MISSING_CONFLICT);
        assertCode(
                () -> CrossSourceEntityKey.join(null, v1),
                AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_MISSING_CONFLICT);
        assertCode(
                () -> CrossSourceEntityKey.join(v1, new CrossSourceEntityKey("C-1002", 1L)),
                AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_MISSING_CONFLICT);
    }

    @Test
    void requireSingleMappingRevisionDetectsMixedRevisions() {
        assertThat(CrossSourceEntityKey.requireSingleMappingRevision(
                        java.util.List.of(new CrossSourceEntityKey("C-1", 2L))))
                .isEqualTo(2L);
        // 空集合不放行：没有键就没有可关联的事实，放行会把空结果误报成完整结果
        assertCode(
                () -> CrossSourceEntityKey.requireSingleMappingRevision(java.util.List.of()),
                AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_MISSING_CONFLICT);
        assertCode(
                () -> CrossSourceEntityKey.requireSingleMappingRevision(null),
                AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_MISSING_CONFLICT);
        assertCode(
                () -> CrossSourceEntityKey.requireSingleMappingRevision(
                        java.util.List.of(new CrossSourceEntityKey("C-1", 1L), new CrossSourceEntityKey("C-2", 2L))),
                AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_REVISION_CONFLICT);
    }

    private static void assertCode(
            Runnable operation, com.basicframework.framework.common.exception.ErrorCode expected) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(expected.getCode()));
    }
}
