package com.basicframework.framework.mybatis.core.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import com.baomidou.mybatisplus.annotation.DbType;
import com.basicframework.framework.mybatis.core.util.JdbcUtils;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;

class QueryWrapperXTest {

    @Test
    void ignoresAbsentOptionalValues() {
        QueryWrapperX<Object> wrapper = new QueryWrapperX<>();

        wrapper.likeIfPresent("name", " ")
                .inIfPresent("id", List.of())
                .inIfPresent("status", new Object[0])
                .eqIfPresent("enabled", null)
                .neIfPresent("deleted", null)
                .gtIfPresent("score", null)
                .geIfPresent("created_at", null)
                .ltIfPresent("updated_at", null)
                .leIfPresent("expired_at", null);

        assertThat(wrapper.getSqlSegment()).isEmpty();
    }

    @Test
    void appendsPresentOptionalValuesAndKeepsFluentType() {
        QueryWrapperX<Object> wrapper = new QueryWrapperX<>();

        assertThat(wrapper.likeIfPresent("name", "admin")).isSameAs(wrapper);
        assertThat(wrapper.inIfPresent("id", List.of(1, 2))).isSameAs(wrapper);
        assertThat(wrapper.inIfPresent("status", 1, 2)).isSameAs(wrapper);
        assertThat(wrapper.eqIfPresent("enabled", true)).isSameAs(wrapper);
        assertThat(wrapper.neIfPresent("deleted", true)).isSameAs(wrapper);
        assertThat(wrapper.gtIfPresent("score", 60)).isSameAs(wrapper);
        assertThat(wrapper.geIfPresent("created_at", 1)).isSameAs(wrapper);
        assertThat(wrapper.ltIfPresent("updated_at", 10)).isSameAs(wrapper);
        assertThat(wrapper.leIfPresent("expired_at", 20)).isSameAs(wrapper);
        assertThat(wrapper.eq(false, "ignored", 1)).isSameAs(wrapper);
        assertThat(wrapper.eq("role", "admin")).isSameAs(wrapper);
        assertThat(wrapper.orderByDesc("id")).isSameAs(wrapper);
        assertThat(wrapper.in("level", List.of(1, 2))).isSameAs(wrapper);
        assertThat(wrapper.last("LIMIT 1")).isSameAs(wrapper);

        assertThat(wrapper.getSqlSegment())
                .contains("name LIKE", "id IN", "status IN", "enabled =", "deleted <>", "score >")
                .contains("created_at >=", "updated_at <", "expired_at <=", "role =", "ORDER BY id DESC", "LIMIT 1")
                .doesNotContain("ignored");
    }

    @ParameterizedTest
    @MethodSource("betweenCases")
    void handlesPartialAndShortBetweenArrays(Object[] values, String expectedSql) {
        QueryWrapperX<Object> wrapper = new QueryWrapperX<>();

        wrapper.betweenIfPresent("created_at", values);

        if (expectedSql == null) {
            assertThat(wrapper.getSqlSegment()).isEmpty();
        } else {
            assertThat(wrapper.getSqlSegment()).contains(expectedSql);
        }
    }

    @Test
    void appliesLimitForEachSupportedDialectFamily() {
        try (MockedStatic<JdbcUtils> jdbcUtils = mockStatic(JdbcUtils.class)) {
            jdbcUtils.when(JdbcUtils::getDbType).thenReturn(DbType.ORACLE);
            assertThat(new QueryWrapperX<>().limitN(2).getSqlSegment()).contains("ROWNUM <=");

            jdbcUtils.when(JdbcUtils::getDbType).thenReturn(DbType.SQL_SERVER);
            assertThat(new QueryWrapperX<>().limitN(2).getSqlSelect()).contains("TOP 2 *");

            jdbcUtils.when(JdbcUtils::getDbType).thenReturn(DbType.MYSQL);
            assertThat(new QueryWrapperX<>().limitN(2).getSqlSegment()).endsWith("LIMIT 2");
        }
    }

    private static Stream<Arguments> betweenCases() {
        return Stream.of(
                Arguments.of(null, null),
                Arguments.of(new Object[0], null),
                Arguments.of(new Object[] {1}, "created_at >="),
                Arguments.of(new Object[] {null, 2}, "created_at <="),
                Arguments.of(new Object[] {1, 2}, "created_at BETWEEN"));
    }
}
