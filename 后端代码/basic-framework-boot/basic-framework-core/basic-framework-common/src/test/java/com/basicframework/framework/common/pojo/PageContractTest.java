package com.basicframework.framework.common.pojo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PageContractTest {

    @Test
    void pageParam_hasStableDefaultsAndSupportsExplicitValues() {
        PageParam pageParam = new PageParam();

        assertThat(pageParam.getPageNo()).isEqualTo(1);
        assertThat(pageParam.getPageSize()).isEqualTo(10);

        pageParam.setPageNo(3).setPageSize(50);
        assertThat(pageParam.getPageNo()).isEqualTo(3);
        assertThat(pageParam.getPageSize()).isEqualTo(50);
        assertThat(PageParam.PAGE_SIZE_NONE).isEqualTo(-1);
        assertThat(PageParam.EXPORT_MAX_PAGE_SIZE).isEqualTo(10_000);
    }

    @Test
    void pageResult_preservesRowsAndTotal() {
        PageResult<String> result = new PageResult<>(List.of("a", "b"), 5L);

        assertThat(result.getList()).containsExactly("a", "b");
        assertThat(result.getTotal()).isEqualTo(5L);
    }

    @Test
    void pageResult_emptyFactoriesNeverReturnNullLists() {
        assertThat(PageResult.empty().getList()).isEmpty();
        assertThat(PageResult.empty().getTotal()).isZero();
        assertThat(PageResult.empty(7L).getList()).isEmpty();
        assertThat(PageResult.empty(7L).getTotal()).isEqualTo(7L);

        PageResult<String> mutable = new PageResult<>();
        mutable.setList(List.of("row"));
        mutable.setTotal(1L);
        assertThat(mutable.getList()).containsExactly("row");
        assertThat(mutable.getTotal()).isEqualTo(1L);
    }
}
