package com.basicframework.module.ai.service.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionStepOutcome;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

/** K03 入库任务 Mapper 查询形状与重排（不连数据库）；租约 SQL 由集成用例在真实 MySQL 上验证。 */
class AiKnowledgeIngestionTaskMapperTest {

    private final AiKnowledgeIngestionTaskMapper mapper =
            mock(AiKnowledgeIngestionTaskMapper.class, Answers.CALLS_REAL_METHODS);

    /** Lambda 包装器需要实体元数据缓存（正常由 MyBatis 运行时初始化）。 */
    @BeforeAll
    static void initTableMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiKnowledgeIngestionTaskDO.class);
    }

    private static Map<String, Object> paramValues(Wrapper<?> wrapper) {
        return ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
    }

    @Test
    void selectsByVersionAndDocumentWithDeterministicOrder() {
        doReturn(new AiKnowledgeIngestionTaskDO()).when(mapper).selectOne(any());
        doReturn(List.of(new AiKnowledgeIngestionTaskDO().setId(91L)))
                .when(mapper)
                .selectList(any());

        assertThat(mapper.selectByVersion(81L, AiKnowledgeIngestionTaskDO.KIND_PARSE))
                .isNotNull();
        assertThat(mapper.selectByDocument(71L)).hasSize(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AiKnowledgeIngestionTaskDO>> selectOne = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectOne(selectOne.capture());
        assertThat(selectOne.getValue().getSqlSegment())
                .containsIgnoringCase("document_version_id")
                .containsIgnoringCase("task_kind");
        assertThat(paramValues(selectOne.getValue()).values()).contains(81L, AiKnowledgeIngestionTaskDO.KIND_PARSE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AiKnowledgeIngestionTaskDO>> selectList = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(selectList.capture());
        assertThat(selectList.getValue().getSqlSegment())
                .containsIgnoringCase("document_id")
                .containsIgnoringCase("ORDER BY id DESC");
    }

    @Test
    void claimableQueryFiltersByStatusAndTimeWithLimit() {
        doReturn(List.of(new AiKnowledgeIngestionTaskDO().setId(91L)))
                .when(mapper)
                .selectList(any());
        LocalDateTime now = LocalDateTime.now().withNano(0);

        assertThat(mapper.selectClaimable(now, 3)).hasSize(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AiKnowledgeIngestionTaskDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertThat(sql).containsIgnoringCase("status").containsIgnoringCase("next_attempt_time");
        assertThat(sql).containsIgnoringCase("ORDER BY next_attempt_time ASC");
        assertThat(sql).contains("LIMIT 3");
        assertThat(paramValues(captor.getValue()).values()).contains(AiKnowledgeIngestionTaskDO.STATUS_QUEUED, now);
    }

    @Test
    void countsAndPagingDelegateToWrappers() {
        doReturn(2L).when(mapper).selectCount(any());

        assertThat(mapper.countExpired(LocalDateTime.now())).isEqualTo(2);
        assertThat(mapper.countQueuedReady(LocalDateTime.now())).isEqualTo(2);
        assertThat(mapper.countByVersionAndStatus(81L, AiKnowledgeIngestionTaskDO.STATUS_FAILED))
                .isZero();
        assertThat(mapper.selectPage(new PageParam(), 61L, "FAILED").getTotal()).isZero();
        assertThat(mapper.selectPage(new PageParam(), 61L, "FAILED").getList()).isEmpty();
    }

    @Test
    void requeueIsAnOptimisticLockCasThatClearsTheLease() {
        doReturn(1).when(mapper).update(any(), any());
        LocalDateTime nextAttempt = LocalDateTime.now().withNano(0);

        assertThat(mapper.requeue(91L, 4, nextAttempt)).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AiKnowledgeIngestionTaskDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(any(), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertThat(sql).containsIgnoringCase("id").containsIgnoringCase("version");
        assertThat(paramValues(captor.getValue()).values()).contains(91L, 4);
    }

    @Test
    void stepOutcomeCarriesStableReasonCodes() {
        assertThat(AiKnowledgeIngestionStepOutcome.succeeded().isSucceeded()).isTrue();
        assertThat(AiKnowledgeIngestionStepOutcome.succeeded().reasonCode()).isNull();
        assertThat(AiKnowledgeIngestionStepOutcome.failed("scan-needs-ocr").isFailed())
                .isTrue();
        assertThat(AiKnowledgeIngestionStepOutcome.failed("scan-needs-ocr").reasonCode())
                .isEqualTo("scan-needs-ocr");
        assertThat(AiKnowledgeIngestionStepOutcome.unhandled("parser-unavailable")
                        .isSucceeded())
                .isFalse();
        assertThat(AiKnowledgeIngestionStepOutcome.unhandled("parser-unavailable")
                        .isFailed())
                .isFalse();
    }
}
