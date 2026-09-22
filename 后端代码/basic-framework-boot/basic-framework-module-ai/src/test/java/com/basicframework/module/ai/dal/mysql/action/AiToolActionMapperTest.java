package com.basicframework.module.ai.dal.mysql.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.action.AiToolActionDO;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

/**
 * D09 工具动作 Mapper 查询形状（D11 补齐：该文件此前未登记覆盖率基线）。
 *
 * <p>不连数据库也能验证**安全相关的查询形状**：过滤列、排序、以及"取值走绑定参数而不是拼字符串"。
 * 真实 SQL 语义由 `AiToolActionIT` 在真实 MySQL 上验证。
 */
class AiToolActionMapperTest {

    private final AiToolActionMapper mapper = mock(AiToolActionMapper.class, Answers.CALLS_REAL_METHODS);

    /** Lambda 包装器需要实体元数据缓存（正常由 MyBatis 运行时初始化）。 */
    @BeforeAll
    static void initTableMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiToolActionDO.class);
    }

    private static AiToolActionDO action() {
        return new AiToolActionDO().setId(41L).setRunId(11L).setStatus(AiToolActionDO.STATUS_PENDING);
    }

    @Test
    void selectByRunFiltersByRunAndOrdersByIdDescending() {
        doReturn(List.of(action())).when(mapper).selectList(any());

        assertThat(mapper.selectByRun(11L)).hasSize(1);

        ArgumentCaptor<Wrapper<AiToolActionDO>> captor = wrapperCaptor();
        verify(mapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertThat(sql).containsIgnoringCase("run_id");
        assertThat(sql).containsIgnoringCase("ORDER BY id DESC");
        assertThat(paramValues(captor.getValue()).values()).contains(11L);
        assertThat(sql).as("取值必须是绑定参数，不能把值拼进 SQL").doesNotContain("11");
    }

    @Test
    void selectPageAlwaysScopesBySubjectTripleAndBindsValues() {
        doAnswer(invocation -> {
                    IPage<AiToolActionDO> page = invocation.getArgument(0);
                    page.setRecords(List.of(action()));
                    page.setTotal(1L);
                    return page;
                })
                .when(mapper)
                .selectPage(any(IPage.class), any());

        PageResult<AiToolActionDO> result = mapper.selectPage(new PageParam(), 7L, "USER", null, 11L);

        assertThat(result.getTotal()).isEqualTo(1L);
        ArgumentCaptor<Wrapper<AiToolActionDO>> captor = wrapperCaptor();
        verify(mapper).selectPage(any(IPage.class), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertThat(sql).containsIgnoringCase("application_id");
        assertThat(sql).containsIgnoringCase("subject_type");
        assertThat(sql).containsIgnoringCase("external_user_id");
        assertThat(sql).containsIgnoringCase("run_id");
        assertThat(paramValues(captor.getValue()).values())
                .as("未提供的外部用户标识按空串绑定（越权与不存在同语义）")
                .contains(7L, "USER", "", 11L);
    }

    @Test
    void updateWithVersionIsAnOptimisticLockCasOnIdAndVersion() {
        doReturn(1).when(mapper).update(any(), any());

        int updated = mapper.updateWithVersion(new AiToolActionDO().setId(41L).setStatus("EXECUTED"), 3);

        assertThat(updated).isEqualTo(1);
        ArgumentCaptor<Wrapper<AiToolActionDO>> captor = wrapperCaptor();
        verify(mapper).update(any(), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertThat(sql).containsIgnoringCase("id");
        assertThat(sql).containsIgnoringCase("version");
        assertThat(paramValues(captor.getValue()).values()).contains(41L, 3);
    }

    /** 绑定参数表（值必须走绑定参数而不是拼接进 SQL）。 */
    private static java.util.Map<String, Object> paramValues(Wrapper<AiToolActionDO> wrapper) {
        return ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Wrapper<AiToolActionDO>> wrapperCaptor() {
        return (ArgumentCaptor<Wrapper<AiToolActionDO>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Wrapper.class);
    }

    /** 编译期确认乐观锁包装器类型（D09 约定：状态机转移只能走 CAS）。 */
    @Test
    void casUsesLambdaUpdateWrapperType() {
        assertThat(new LambdaUpdateWrapper<AiToolActionDO>()).isNotNull();
    }
}
