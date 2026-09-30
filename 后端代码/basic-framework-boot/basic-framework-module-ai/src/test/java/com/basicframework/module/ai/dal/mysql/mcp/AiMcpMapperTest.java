package com.basicframework.module.ai.dal.mysql.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.basicframework.module.ai.dal.dataobject.mcp.AiMcpDiscoveryRunDO;
import com.basicframework.module.ai.dal.dataobject.mcp.AiMcpToolDraftDO;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

/**
 * MCP 草稿/留痕 Mapper 的**安全相关查询形状**。
 *
 * <p>不连数据库也能验证三件与安全有关的事：过滤列是不是按"上游工具身份"来、
 * 排序是否稳定、以及"取值走绑定参数而不是拼进 SQL 字符串"。
 * 真实 SQL 语义（唯一键、乐观锁）由 {@code AiMcpClientIT} 在真实 MySQL 上验证。
 */
class AiMcpMapperTest {

    private final AiMcpToolDraftMapper draftMapper = mock(AiMcpToolDraftMapper.class, Answers.CALLS_REAL_METHODS);

    private final AiMcpDiscoveryRunMapper runMapper = mock(AiMcpDiscoveryRunMapper.class, Answers.CALLS_REAL_METHODS);

    /** Lambda 包装器需要实体元数据缓存（正常由 MyBatis 运行时初始化）。 */
    @BeforeAll
    static void initTableMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AiMcpToolDraftDO.class);
        TableInfoHelper.initTableInfo(assistant, AiMcpDiscoveryRunDO.class);
    }

    private static AiMcpToolDraftDO draft() {
        return new AiMcpToolDraftDO()
                .setId(41L)
                .setConnectorId(501L)
                .setUpstreamToolName("search_orders")
                .setStatus(AiMcpToolDraftDO.STATUS_DRAFT)
                .setVersion(3);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Wrapper<AiMcpToolDraftDO>> draftWrapperCaptor() {
        return (ArgumentCaptor<Wrapper<AiMcpToolDraftDO>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Wrapper.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Wrapper<AiMcpDiscoveryRunDO>> runWrapperCaptor() {
        return (ArgumentCaptor<Wrapper<AiMcpDiscoveryRunDO>>)
                (ArgumentCaptor<?>) ArgumentCaptor.forClass(Wrapper.class);
    }

    /** 包装器取值（Wrapper 接口不直接暴露这些方法，按 MyBatis-Plus 的抽象包装器取）。 */
    private static java.util.Map<String, Object> paramValues(Wrapper<?> wrapper) {
        return ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
    }

    private static String sqlSegment(Wrapper<?> wrapper) {
        return wrapper.getSqlSegment();
    }

    /** SET 子句（getSqlSegment 只给 WHERE）："是否真的写了这一列"要看它。 */
    private static String sqlSet(Wrapper<?> wrapper) {
        return ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapper).getSqlSet();
    }

    @Test
    void selectByUpstreamFiltersByConnectorAndToolName() {
        doReturn(draft()).when(draftMapper).selectOne(any());

        assertThat(draftMapper.selectByUpstream(501L, "search_orders")).isNotNull();

        ArgumentCaptor<Wrapper<AiMcpToolDraftDO>> captor = draftWrapperCaptor();
        verify(draftMapper).selectOne(captor.capture());
        String sql = sqlSegment(captor.getValue());
        assertThat(sql).containsIgnoringCase("connector_id").containsIgnoringCase("upstream_tool_name");
        // 工具名来自上游，必须走绑定参数而不是拼进 SQL 片段
        assertThat(paramValues(captor.getValue()).values()).contains(501L, "search_orders");
    }

    @Test
    void selectByConnectorOrdersByIdAscendingForStableReview() {
        doReturn(List.of(draft())).when(draftMapper).selectList(any());

        assertThat(draftMapper.selectByConnector(501L)).hasSize(1);

        ArgumentCaptor<Wrapper<AiMcpToolDraftDO>> captor = draftWrapperCaptor();
        verify(draftMapper).selectList(captor.capture());
        assertThat(sqlSegment(captor.getValue())).containsIgnoringCase("connector_id");
        assertThat(sqlSegment(captor.getValue()).toUpperCase(java.util.Locale.ROOT))
                .contains("ORDER BY");
    }

    @Test
    void updateWithVersionIsACasOnIdAndVersion() {
        doReturn(1).when(draftMapper).update(any(AiMcpToolDraftDO.class), any());

        assertThat(draftMapper.updateWithVersion(draft().setStatus(AiMcpToolDraftDO.STATUS_BLOCKED), 3))
                .isEqualTo(1);

        ArgumentCaptor<Wrapper<AiMcpToolDraftDO>> captor = draftWrapperCaptor();
        verify(draftMapper).update(any(AiMcpToolDraftDO.class), captor.capture());
        String sql = sqlSegment(captor.getValue());
        assertThat(sql).containsIgnoringCase("id").containsIgnoringCase("version");
        assertThat(paramValues(captor.getValue()).values()).contains(41L, 3);
    }

    @Test
    void clearingApprovalWritesAnExplicitNullInsteadOfSkippingTheColumn() {
        // 反向关键断言：漂移阻断必须真正把 approved_fingerprint 写成 NULL。
        // MyBatis-Plus 的实体更新默认忽略 null 字段，所以这里必须由 wrapper 的 set() 显式生成
        // "SET approved_fingerprint = NULL"，否则旧审批指纹会残留在库里。
        doReturn(1).when(draftMapper).update(any(AiMcpToolDraftDO.class), any());

        assertThat(draftMapper.updateWithVersionClearingApproval(
                        draft().setStatus(AiMcpToolDraftDO.STATUS_BLOCKED).setApprovedFingerprint(null), 3))
                .isEqualTo(1);

        ArgumentCaptor<Wrapper<AiMcpToolDraftDO>> captor = draftWrapperCaptor();
        verify(draftMapper).update(any(AiMcpToolDraftDO.class), captor.capture());
        assertThat(sqlSet(captor.getValue())).containsIgnoringCase("approved_fingerprint");
        assertThat(sqlSegment(captor.getValue())).containsIgnoringCase("id").containsIgnoringCase("version");
        assertThat(paramValues(captor.getValue()).values()).contains(41L, 3);
    }

    @Test
    void clearingApprovalStillReportsZeroWhenTheCasMisses() {
        doReturn(0).when(draftMapper).update(any(AiMcpToolDraftDO.class), any());
        assertThat(draftMapper.updateWithVersionClearingApproval(draft(), 99)).isZero();
    }

    @Test
    void casReportsZeroWhenAnotherWriterWonTheRace() {
        // 反向：乐观锁未命中必须如实返回 0，由服务层转成状态冲突——不能静默当成功
        doReturn(0).when(draftMapper).update(any(AiMcpToolDraftDO.class), any());
        assertThat(draftMapper.updateWithVersion(draft(), 99)).isZero();
    }

    @Test
    void selectRecentFiltersByConnectorAndLimitsRows() {
        AiMcpDiscoveryRunDO run = new AiMcpDiscoveryRunDO()
                .setId(7L)
                .setConnectorId(501L)
                .setAttempts(2)
                .setTermination(AiMcpDiscoveryRunDO.TERMINATION_TIMEOUT);
        doReturn(List.of(run)).when(runMapper).selectList(any());

        assertThat(runMapper.selectRecent(501L, 10)).hasSize(1);

        ArgumentCaptor<Wrapper<AiMcpDiscoveryRunDO>> captor = runWrapperCaptor();
        verify(runMapper).selectList(captor.capture());
        assertThat(sqlSegment(captor.getValue())).containsIgnoringCase("connector_id");
        // limit 必须是有界常量而不是调用方拼出来的任意串
        assertThat(sqlSegment(captor.getValue())).contains("LIMIT 10");
        assertThat(paramValues(captor.getValue()).values()).contains(501L).doesNotContain("10");
    }
}
