package com.basicframework.framework.mybatis.core.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.pojo.SortingField;
import com.basicframework.framework.mybatis.core.util.JdbcUtils;
import com.github.yulichang.interfaces.MPJBaseJoin;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class BaseMapperXTest {

    @Test
    void selectPage_withoutPagination_returnsAllRowsAndAppliesSorting() {
        BaseMapperX<TestEntity> mapper = mapper();
        QueryWrapper<TestEntity> query = new QueryWrapper<>();
        TestEntity first = new TestEntity(1L);
        TestEntity second = new TestEntity(2L);
        when(mapper.selectList(query)).thenReturn(List.of(first, second));
        PageParam pageParam = new PageParam().setPageSize(PageParam.PAGE_SIZE_NONE);

        PageResult<TestEntity> result =
                mapper.selectPage(pageParam, List.of(new SortingField("id", SortingField.ORDER_DESC)), query);

        assertThat(result.getList()).containsExactly(first, second);
        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(query.getSqlSegment()).contains("ORDER BY id DESC");
    }

    @Test
    void selectPage_withPagination_usesDatabaseResult() {
        BaseMapperX<TestEntity> mapper = mapper();
        QueryWrapper<TestEntity> query = new QueryWrapper<>();
        TestEntity row = new TestEntity(3L);
        doAnswer(invocation -> {
                    IPage<TestEntity> page = invocation.getArgument(0);
                    page.setRecords(List.of(row));
                    page.setTotal(11L);
                    return page;
                })
                .when(mapper)
                .selectPage(any(IPage.class), same(query));
        PageParam pageParam = new PageParam().setPageNo(2).setPageSize(5);

        PageResult<TestEntity> result = mapper.selectPage(pageParam, query);

        assertThat(result.getList()).containsExactly(row);
        assertThat(result.getTotal()).isEqualTo(11L);
    }

    @Test
    void selectJoinPage_withoutPagination_returnsAllProjectedRows() {
        BaseMapperX<TestEntity> mapper = mapper();
        MPJLambdaWrapper<TestEntity> query = mock(MPJLambdaWrapper.class);
        TestDto row = new TestDto(7L);
        when(mapper.selectJoinList(TestDto.class, query)).thenReturn(List.of(row));
        PageParam pageParam = new PageParam().setPageSize(PageParam.PAGE_SIZE_NONE);

        PageResult<TestDto> result = mapper.selectJoinPage(pageParam, TestDto.class, query);

        assertThat(result.getList()).containsExactly(row);
        assertThat(result.getTotal()).isEqualTo(1L);
    }

    @Test
    void selectJoinPage_withPagination_usesProjectedDatabaseResult() {
        BaseMapperX<TestEntity> mapper = mapper();
        MPJLambdaWrapper<TestEntity> query = mock(MPJLambdaWrapper.class);
        TestDto row = new TestDto(8L);
        doAnswer(invocation -> {
                    IPage<TestDto> page = invocation.getArgument(0);
                    page.setRecords(List.of(row));
                    page.setTotal(13L);
                    return page;
                })
                .when(mapper)
                .selectJoinPage(any(IPage.class), eq(TestDto.class), same(query));

        PageResult<TestDto> result =
                mapper.selectJoinPage(new PageParam().setPageNo(2).setPageSize(5), TestDto.class, query);

        assertThat(result.getList()).containsExactly(row);
        assertThat(result.getTotal()).isEqualTo(13L);
    }

    @Test
    void selectJoinPage_withSortableParam_appliesSortingToDatabasePage() {
        BaseMapperX<TestEntity> mapper = mapper();
        MPJLambdaWrapper<TestEntity> query = mock(MPJLambdaWrapper.class);
        TestDto row = new TestDto(9L);
        doAnswer(invocation -> {
                    IPage<TestDto> page = invocation.getArgument(0);
                    page.setRecords(List.of(row));
                    page.setTotal(15L);
                    return page;
                })
                .when(mapper)
                .selectJoinPage(any(IPage.class), eq(TestDto.class), same(query));
        com.basicframework.framework.common.pojo.SortablePageParam pageParam =
                new com.basicframework.framework.common.pojo.SortablePageParam();
        pageParam.setPageNo(1);
        pageParam.setPageSize(5);
        pageParam.setSortingFields(List.of(new SortingField("id", SortingField.ORDER_DESC)));

        PageResult<TestDto> result = mapper.selectJoinPage(pageParam, TestDto.class, query);

        assertThat(result.getList()).containsExactly(row);
        assertThat(result.getTotal()).isEqualTo(15L);
    }

    @Test
    void selectJoinPage_withBaseJoin_usesDatabasePage() {
        BaseMapperX<TestEntity> mapper = mapper();
        MPJBaseJoin<TestEntity> query = mock(MPJBaseJoin.class);
        TestDto row = new TestDto(10L);
        doAnswer(invocation -> {
                    IPage<TestDto> page = invocation.getArgument(0);
                    page.setRecords(List.of(row));
                    page.setTotal(17L);
                    return page;
                })
                .when(mapper)
                .selectJoinPage(any(IPage.class), eq(TestDto.class), same(query));

        PageResult<TestDto> result =
                mapper.selectJoinPage(new PageParam().setPageNo(1).setPageSize(5), TestDto.class, query);

        assertThat(result.getList()).containsExactly(row);
        assertThat(result.getTotal()).isEqualTo(17L);
    }

    @Test
    void insertBatch_onSqlServer_insertsRowsIndividually() {
        BaseMapperX<TestEntity> mapper = mapper();
        List<TestEntity> rows = List.of(new TestEntity(1L), new TestEntity(2L));
        when(mapper.insert(any(TestEntity.class))).thenReturn(1);

        try (MockedStatic<JdbcUtils> jdbcUtils = mockStatic(JdbcUtils.class)) {
            jdbcUtils.when(JdbcUtils::getDbType).thenReturn(DbType.SQL_SERVER);
            jdbcUtils.when(() -> JdbcUtils.isSQLServer(DbType.SQL_SERVER)).thenReturn(true);

            assertThat(mapper.insertBatch(rows)).isTrue();
            assertThat(mapper.insertBatch(rows, 100)).isTrue();
        }

        verify(mapper, times(4)).insert(any(TestEntity.class));
    }

    @Test
    void insertBatch_onRegularDialect_delegatesToMyBatisPlus() {
        BaseMapperX<TestEntity> mapper = mapper();
        List<TestEntity> rows = List.of(new TestEntity(1L));

        try (MockedStatic<JdbcUtils> jdbcUtils = mockStatic(JdbcUtils.class);
                MockedStatic<Db> db = mockStatic(Db.class)) {
            jdbcUtils.when(JdbcUtils::getDbType).thenReturn(DbType.MYSQL);
            jdbcUtils.when(() -> JdbcUtils.isSQLServer(DbType.MYSQL)).thenReturn(false);
            db.when(() -> Db.saveBatch(rows)).thenReturn(true);
            db.when(() -> Db.saveBatch(rows, 100)).thenReturn(true);

            assertThat(mapper.insertBatch(rows)).isTrue();
            assertThat(mapper.insertBatch(rows, 100)).isTrue();

            db.verify(() -> Db.saveBatch(rows));
            db.verify(() -> Db.saveBatch(rows, 100));
        }
    }

    @Test
    void lambdaAndCollectionHelpers_delegateThroughTypedWrappers() {
        BaseMapperX<TestEntity> mapper = mapper();
        TestEntity row = new TestEntity(1L);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(row);
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(2L);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));
        when(mapper.delete(any(Wrapper.class))).thenReturn(1);

        assertThat(mapper.selectOne(TestEntity::getId, 1L)).isSameAs(row);
        assertThat(mapper.selectOne(TestEntity::getId, 1L, TestEntity::getId, 1L))
                .isSameAs(row);
        assertThat(mapper.selectOne(TestEntity::getId, 1L, TestEntity::getId, 1L, TestEntity::getId, 1L))
                .isSameAs(row);
        assertThat(mapper.selectFirstOne(TestEntity::getId, 1L)).isSameAs(row);
        assertThat(mapper.selectFirstOne(TestEntity::getId, 1L, TestEntity::getId, 1L))
                .isSameAs(row);
        assertThat(mapper.selectFirstOne(TestEntity::getId, 1L, TestEntity::getId, 1L, TestEntity::getId, 1L))
                .isSameAs(row);
        assertThat(mapper.selectCount(TestEntity::getId, 1L)).isEqualTo(2L);
        assertThat(mapper.selectList(TestEntity::getId, 1L)).containsExactly(row);
        assertThat(mapper.selectList(TestEntity::getId, List.of(1L))).containsExactly(row);
        assertThat(mapper.selectList(TestEntity::getId, 1L, TestEntity::getId, 1L))
                .containsExactly(row);
        assertThat(mapper.delete(TestEntity::getId, 1L)).isEqualTo(1);
        assertThat(mapper.deleteBatch(TestEntity::getId, List.of(1L))).isEqualTo(1);
    }

    @Test
    void updateBatch_delegatesToMyBatisPlus() {
        BaseMapperX<TestEntity> mapper = mapper();
        List<TestEntity> rows = List.of(new TestEntity(1L));

        try (MockedStatic<Db> db = mockStatic(Db.class)) {
            db.when(() -> Db.updateBatchById(rows)).thenReturn(true);
            db.when(() -> Db.updateBatchById(rows, 100)).thenReturn(true);

            assertThat(mapper.updateBatch(rows)).isTrue();
            assertThat(mapper.updateBatch(rows, 100)).isTrue();

            db.verify(() -> Db.updateBatchById(rows));
            db.verify(() -> Db.updateBatchById(rows, 100));
        }
    }

    @Test
    void collectionHelpers_shortCircuitEmptyInput() {
        BaseMapperX<TestEntity> mapper = mapper();

        assertThat(mapper.selectList("id", List.of())).isEmpty();
        assertThat(mapper.deleteBatch(TestEntity::getId, List.of())).isZero();

        verify(mapper, never()).selectList(any(Wrapper.class));
        verify(mapper, never()).delete(any(Wrapper.class));
    }

    @Test
    void simpleStringHelpers_delegateThroughValidatedWrappers() {
        BaseMapperX<TestEntity> mapper = mapper();
        TestEntity row = new TestEntity(1L);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(row);
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(2L);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));
        when(mapper.update(same(row), any(Wrapper.class))).thenReturn(1);
        when(mapper.delete(any(Wrapper.class))).thenReturn(1);

        assertThat(mapper.selectOne("id", 1L)).isSameAs(row);
        assertThat(mapper.selectOne("id", 1L, "status", 0)).isSameAs(row);
        assertThat(mapper.selectCount()).isEqualTo(2L);
        assertThat(mapper.selectCount("status", 0)).isEqualTo(2L);
        assertThat(mapper.selectList()).containsExactly(row);
        assertThat(mapper.selectList("status", 0)).containsExactly(row);
        assertThat(mapper.selectList("id", List.of(1L))).containsExactly(row);
        assertThat(mapper.updateBatch(row)).isEqualTo(1);
        assertThat(mapper.delete("id", "1")).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private static BaseMapperX<TestEntity> mapper() {
        return mock(BaseMapperX.class, CALLS_REAL_METHODS);
    }

    static class TestEntity {

        private final Long id;

        TestEntity(Long id) {
            this.id = id;
        }

        public Long getId() {
            return id;
        }
    }

    private record TestDto(Long id) {}
}
