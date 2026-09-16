package com.basicframework.framework.mybatis.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.SortingField;
import java.util.List;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.schema.Table;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class MyBatisUtilsTest {

    @Test
    void buildPage_appliesPaginationAndValidatedSorting() {
        PageParam pageParam = new PageParam().setPageNo(2).setPageSize(20);

        Page<Object> page = MyBatisUtils.buildPage(
                pageParam,
                List.of(
                        new SortingField("createdAt", SortingField.ORDER_DESC),
                        new SortingField("id", SortingField.ORDER_ASC)));

        assertThat(page.getCurrent()).isEqualTo(2);
        assertThat(page.getSize()).isEqualTo(20);
        assertThat(page.optimizeJoinOfCountSql()).isFalse();
        assertThat(page.orders())
                .extracting(order -> order.getColumn() + ":" + order.isAsc())
                .containsExactly("created_at:false", "id:true");
        assertThat(MyBatisUtils.buildPage(pageParam).orders()).isEmpty();
    }

    @Test
    void sorting_rejectsUnsafeFieldAndUnknownDirection() {
        PageParam pageParam = new PageParam();

        assertThatThrownBy(() -> MyBatisUtils.buildPage(
                        pageParam, List.of(new SortingField("id; DROP TABLE users", SortingField.ORDER_ASC))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid sorting field");
        assertThatThrownBy(() -> MyBatisUtils.buildPage(pageParam, List.of(new SortingField("id", "sideways"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid sorting order");
    }

    @Test
    void addOrder_supportsQueryAndLambdaWrappers() {
        QueryWrapper<Object> queryWrapper = new QueryWrapper<>();
        LambdaQueryWrapper<Object> lambdaWrapper = new LambdaQueryWrapper<>();
        List<SortingField> sorting = List.of(
                new SortingField("createdAt", SortingField.ORDER_ASC), new SortingField("id", SortingField.ORDER_DESC));

        MyBatisUtils.addOrder(queryWrapper, sorting);
        MyBatisUtils.addOrder(lambdaWrapper, sorting);

        assertThat(queryWrapper.getSqlSegment()).contains("ORDER BY created_at ASC", "id DESC");
        assertThat(lambdaWrapper.getSqlSegment()).contains("ORDER BY created_at ASC, id DESC");
    }

    @Test
    void addOrder_ignoresEmptySortingAndRejectsUnsupportedWrapper() {
        QueryWrapper<Object> queryWrapper = new QueryWrapper<>();

        MyBatisUtils.addOrder(queryWrapper, List.of());

        assertThat(queryWrapper.getSqlSegment()).isEmpty();
        Wrapper<Object> unsupported = mock(Wrapper.class);
        assertThatThrownBy(() ->
                        MyBatisUtils.addOrder(unsupported, List.of(new SortingField("id", SortingField.ORDER_ASC))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported wrapper type");
    }

    @Test
    void interceptorAndSqlHelpers_preserveOrderAndIdentifiers() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        InnerInterceptor first = mock(InnerInterceptor.class);
        InnerInterceptor second = mock(InnerInterceptor.class);
        interceptor.addInnerInterceptor(first);

        MyBatisUtils.addInterceptor(interceptor, second, 0);

        assertThat(interceptor.getInterceptors()).containsExactly(second, first);
        assertThat(MyBatisUtils.getTableName(new Table("`system_users`"))).isEqualTo("system_users");
        assertThat(MyBatisUtils.getTableName(new Table("system_users"))).isEqualTo("system_users");
        assertThat(MyBatisUtils.buildColumn("system_users", new Alias("u"), "id")
                        .toString())
                .isEqualTo("u.id");
        assertThat(MyBatisUtils.buildColumn("system_users", null, "id").toString())
                .isEqualTo("system_users.id");
    }

    @Test
    void findInSet_usesCurrentDatabaseDialect() {
        try (MockedStatic<JdbcUtils> jdbcUtils = mockStatic(JdbcUtils.class)) {
            jdbcUtils.when(JdbcUtils::getDbType).thenReturn(DbType.MYSQL);
            assertThat(MyBatisUtils.findInSet("dept_ids", 12L)).isEqualTo("FIND_IN_SET('12', dept_ids) <> 0");

            jdbcUtils.when(JdbcUtils::getDbType).thenReturn(DbType.POSTGRE_SQL);
            assertThat(MyBatisUtils.findInSet("dept_ids", 12L)).isEqualTo("POSITION('12' IN dept_ids) <> 0");
        }
    }
}
