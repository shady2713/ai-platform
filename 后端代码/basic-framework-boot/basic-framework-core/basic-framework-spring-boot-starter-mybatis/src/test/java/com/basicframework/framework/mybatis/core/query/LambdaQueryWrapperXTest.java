package com.basicframework.framework.mybatis.core.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LambdaQueryWrapperXTest {

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), TestEntity.class);
    }

    @Test
    void conditionalMethods_skipAbsentValues() {
        LambdaQueryWrapperX<TestEntity> wrapper = new LambdaQueryWrapperX<>();

        wrapper.likeIfPresent(TestEntity::getName, " ")
                .inIfPresent(TestEntity::getId, List.of())
                .inIfPresent(TestEntity::getId, new Object[0])
                .eqIfPresent(TestEntity::getName, "")
                .neIfPresent(TestEntity::getName, null)
                .gtIfPresent(TestEntity::getId, null)
                .geIfPresent(TestEntity::getId, null)
                .ltIfPresent(TestEntity::getId, null)
                .leIfPresent(TestEntity::getId, null)
                .betweenIfPresent(TestEntity::getId, null, null);

        assertThat(wrapper.getSqlSegment()).isEmpty();
    }

    @Test
    void conditionalMethods_addPresentValues() {
        LambdaQueryWrapperX<TestEntity> wrapper = new LambdaQueryWrapperX<>();

        wrapper.likeIfPresent(TestEntity::getName, "Alice")
                .inIfPresent(TestEntity::getId, List.of(1L, 2L))
                .inIfPresent(TestEntity::getId, 3L, 4L)
                .eqIfPresent(TestEntity::getName, "Alice")
                .neIfPresent(TestEntity::getName, "Bob")
                .gtIfPresent(TestEntity::getId, 0L)
                .geIfPresent(TestEntity::getId, 1L)
                .ltIfPresent(TestEntity::getId, 10L)
                .leIfPresent(TestEntity::getId, 9L);

        assertThat(wrapper.getSqlSegment())
                .contains("name LIKE", "id IN", "name =", "name <>", "id >", "id >=", "id <", "id <=");
    }

    @Test
    void betweenIfPresent_handlesAllBoundCombinations() {
        assertThat(new LambdaQueryWrapperX<TestEntity>()
                        .betweenIfPresent(TestEntity::getId, 1L, 2L)
                        .getSqlSegment())
                .contains("id BETWEEN");
        assertThat(new LambdaQueryWrapperX<TestEntity>()
                        .betweenIfPresent(TestEntity::getId, 1L, null)
                        .getSqlSegment())
                .contains("id >=");
        assertThat(new LambdaQueryWrapperX<TestEntity>()
                        .betweenIfPresent(TestEntity::getId, null, 2L)
                        .getSqlSegment())
                .contains("id <=");
        assertThat(new LambdaQueryWrapperX<TestEntity>()
                        .betweenIfPresent(TestEntity::getId, new Object[] {1L, 2L})
                        .getSqlSegment())
                .contains("id BETWEEN");
    }

    @Test
    void overriddenMethods_keepFluentWrapperType() {
        LambdaQueryWrapperX<TestEntity> wrapper = new LambdaQueryWrapperX<>();

        assertThat(wrapper.eq(false, TestEntity::getName, "ignored")).isSameAs(wrapper);
        assertThat(wrapper.eq(TestEntity::getName, "Alice")).isSameAs(wrapper);
        assertThat(wrapper.orderByDesc(TestEntity::getId)).isSameAs(wrapper);
        assertThat(wrapper.in(TestEntity::getId, List.of(1L))).isSameAs(wrapper);
        assertThat(wrapper.last("LIMIT 1")).isSameAs(wrapper);
        assertThat(wrapper.getSqlSegment()).contains("ORDER BY id DESC", "LIMIT 1");
    }

    @TableName("test_entity")
    static class TestEntity {

        @TableId
        private Long id;

        @TableField("name")
        private String name;

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }
}
