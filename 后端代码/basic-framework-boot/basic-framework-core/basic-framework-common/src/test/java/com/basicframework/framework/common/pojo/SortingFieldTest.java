package com.basicframework.framework.common.pojo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SortingField 字段名与排序方向校验测试
 *
 * 覆盖 SQL 注入防护（字段名白名单）与封闭域排序方向
 * （ORDER_REGEX 与 ORDER_ASC/ORDER_DESC 单一来源，见阶段收尾治理）。
 */
class SortingFieldTest {

    @ParameterizedTest
    @ValueSource(strings = {"id", "createTime", "amount_1", "A1_b2"})
    void isValidField_acceptsPlainIdentifiers(String fieldName) {
        assertThat(SortingField.isValidField(fieldName)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "id;DROP TABLE", "user name", "中文", "a-b", "a.b", "a,b", "a b"})
    void isValidField_rejectsSqlInjectionAndMalformed(String fieldName) {
        assertThat(SortingField.isValidField(fieldName)).isFalse();
    }

    @Test
    void isValidField_rejectsNull() {
        assertThat(SortingField.isValidField(null)).isFalse();
    }

    @Test
    void isValidField_rejectsOverLength() {
        // FIELD_REGEX 上限 64 位
        assertThat(SortingField.isValidField("a".repeat(65))).isFalse();
        assertThat(SortingField.isValidField("a".repeat(64))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"asc", "desc", "ASC", "Desc"})
    void isValidOrder_acceptsBothDirectionsCaseInsensitive(String order) {
        assertThat(SortingField.isValidOrder(order)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "up", "down", "asc ", " desc", "ASC,DESC", "sort"})
    void isValidOrder_rejectsUnknownDirections(String order) {
        assertThat(SortingField.isValidOrder(order)).isFalse();
    }

    @Test
    void isValidOrder_rejectsNull() {
        assertThat(SortingField.isValidOrder(null)).isFalse();
    }

    @Test
    void orderRegexMatchesDomainConstants() {
        // ORDER_REGEX 与封闭域一致：两个常量都过、任意其他字符串不过
        assertThat(SortingField.ORDER_ASC).matches(SortingField.ORDER_REGEX);
        assertThat(SortingField.ORDER_DESC).matches(SortingField.ORDER_REGEX);
        assertThat("other").doesNotMatch(SortingField.ORDER_REGEX);
    }
}
