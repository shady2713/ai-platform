package com.basicframework.module.ai.service.report.revision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportVersionDO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.domain.query.QueryScope;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.authorization.dto.AiAuthorizationDecisionDTO;
import com.basicframework.module.ai.service.conversation.AiConversationSubject;
import com.basicframework.module.ai.service.conversation.AiConversationSubjectResolver;
import com.basicframework.module.ai.service.dataset.AiDatasetService;
import com.basicframework.module.ai.service.report.persistence.AiReportService;
import com.basicframework.module.ai.service.report.persistence.dto.AiReportSaveDTO;
import com.basicframework.module.ai.service.report.revision.dto.AiReportRevisionRequestDTO;
import com.basicframework.module.ai.service.report.revision.dto.AiReportRevisionResultDTO;
import com.basicframework.module.ai.service.report.validation.AiReportDataBinder;
import com.basicframework.module.ai.service.report.validation.AiReportSpecValidator;
import com.basicframework.module.ai.service.run.AiRunQueryExecutionService;
import com.basicframework.module.ai.service.run.dto.AiRunQueryExecutionRequestDTO;
import com.basicframework.module.ai.testfixture.AiReportRevisionFixture;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * R05 对话修改评测（固定夹具）：每条指令的"分类 + 是否查库 + 改了什么"都必须可复现。
 *
 * <p>为什么用固定夹具做评测：真实模型不可复现，改法是否正确必须能用同一份输入回归比对。
 * 评测口径与卡片验收一一对应：
 * <ul>
 *   <li><b>换图不重复查库</b>：展示类指令的受控查询调用次数必须为 0；</li>
 *   <li><b>新增指标需要受控查询</b>：数据类指令必须调用受控查询，且新块绑定的是真实结果列；</li>
 *   <li><b>原版本不被改写</b>：任何指令都只走"新增版本"，本评测断言保存请求带乐观锁版本。</li>
 * </ul>
 *
 * <p>真实模型效果评测不在本卡范围（本机无模型端点），由 Q04/Q10 承接；本评测是**确定性替身**评测，
 * 不冒充模型效果。
 */
class AiReportRevisionEvaluationTest {

    private final AiReportService reportService = mock(AiReportService.class);

    private final AiDatasetService datasetService = mock(AiDatasetService.class);

    private final AiAuthorizationService authorizationService = mock(AiAuthorizationService.class);

    private final AiConversationSubjectResolver subjectResolver = mock(AiConversationSubjectResolver.class);

    private final AiRunQueryExecutionService queryExecutionService = mock(AiRunQueryExecutionService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<AiReportRevisionModel> modelProvider = mock(ObjectProvider.class);

    private final AiReportRevisionModel model = mock(AiReportRevisionModel.class);

    private final AiReportRevisionServiceImpl service = new AiReportRevisionServiceImpl(
            reportService,
            datasetService,
            authorizationService,
            subjectResolver,
            queryExecutionService,
            new AiReportSpecValidator(),
            new AiReportDataBinder(),
            new AiReportSpecPatcher(),
            modelProvider);

    @BeforeEach
    void setUp() {
        when(subjectResolver.resolveCurrent())
                .thenReturn(Optional.of(new AiConversationSubject(9L, AiSubjectType.USER, "alice")));
        when(reportService.getReport(71L)).thenReturn(report());
        when(reportService.getVersion(71L, 1)).thenReturn(baseVersion());
        when(reportService.saveVersion(any(AiReportSaveDTO.class))).thenReturn(71L);
        when(datasetService.getDataset(81L)).thenReturn(dataset());
        when(authorizationService.authorize(
                        eq(9L), eq("USER"), eq("alice"), eq(AiResourceType.DATASET), any(), any(), any()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(true).setScopeFingerprint("f".repeat(64)));
        when(queryExecutionService.execute(any(AiRunQueryExecutionRequestDTO.class)))
                .thenReturn(AiReportRevisionFixture.queryResult());
        when(modelProvider.getIfAvailable()).thenReturn(model);
    }

    private static AiReportDO report() {
        return new AiReportDO()
                .setId(71L)
                .setCode("r05_sales")
                .setName("8 月销售")
                .setMode(AiReportDO.MODE_SNAPSHOT)
                .setServiceId(5L)
                .setReleaseId(6L)
                .setThemeId("thm_default")
                .setThemeRevision(1)
                .setSchemaVersion("1.0")
                .setLatestVersionNo(1)
                .setPublishedVersionNo(1)
                .setVersion(0);
    }

    private static AiReportVersionDO baseVersion() {
        return new AiReportVersionDO()
                .setId(81L)
                .setReportId(71L)
                .setVersionNo(1)
                .setMode(AiReportDO.MODE_SNAPSHOT)
                .setSpecJson(AiReportRevisionFixture.BASE_SPEC)
                .setDataJson(AiReportRevisionFixture.BASE_DATA)
                .setSourcesJson(AiReportRevisionFixture.BASE_SOURCES)
                .setCompleteness("COMPLETE");
    }

    private static AiDatasetDO dataset() {
        return new AiDatasetDO()
                .setId(81L)
                .setCode(AiReportRevisionFixture.DATASET_CODE)
                .setConnectorId(7L)
                .setSourceObject("it_query.orders")
                .setStatus(AiDatasetDO.STATUS_ENABLED);
    }

    private static AiReportRevisionRequestDTO request(String instruction) {
        return new AiReportRevisionRequestDTO()
                .setReportId(71L)
                .setBaseVersionNo(1)
                .setVersion(0)
                .setInstruction(instruction)
                .setEndpointId(5L)
                .setDatasetId(81L)
                .setDatasetVersionId(91L)
                .setRowScope(QueryScope.eq("customer", "C001"));
    }

    private AiReportRevisionResultDTO evaluate(String instruction, String revisionPlan) {
        when(model.propose(any(), any(), any())).thenReturn(revisionPlan);
        return service.revise(request(instruction));
    }

    /** 最近一次保存请求（评测按用例顺序推进，取最后一次即当前用例的产物）。 */
    private AiReportSaveDTO savedSpec() {
        ArgumentCaptor<AiReportSaveDTO> captor = ArgumentCaptor.forClass(AiReportSaveDTO.class);
        verify(reportService, org.mockito.Mockito.atLeastOnce()).saveVersion(captor.capture());
        return captor.getValue();
    }

    @Test
    void evaluationCasesMatchExpectedClassificationAndQueryBehaviour() {
        // 1) 换图类型：展示类，不查库
        AiReportRevisionResultDTO chartType = evaluate("把柱状图换成折线图", AiReportRevisionFixture.PRESENTATION_PLAN);
        assertThat(chartType.isQueryPerformed()).isFalse();
        assertThat(chartType.getDiff().queryRequired()).isFalse();
        assertThat(chartType.getDiff().modifiedBlocks()).containsExactly("sales_chart");
        verify(queryExecutionService, never()).execute(any());
        assertThat(savedSpec().getSpecJson()).contains("\"chartType\":\"line\"");
        assertThat(savedSpec().getVersion()).isZero();

        // 2) 改标题：展示类，只动标题
        AiReportRevisionResultDTO title = evaluate(
                "标题改成 9 月销售",
                "{\"schemaVersion\":\"1.0\",\"operations\":[{\"op\":\"SET_TITLE\",\"value\":\"9 月销售\"}]}");
        assertThat(title.isQueryPerformed()).isFalse();
        assertThat(title.getDiff().titleChanged()).isTrue();
        assertThat(title.getDiff().modifiedBlocks()).isEmpty();
        verify(queryExecutionService, never()).execute(any());

        // 3) 删除图表：展示类，块被移除且布局同步（数据不查库）
        AiReportRevisionResultDTO removed = evaluate(
                "去掉图表",
                "{\"schemaVersion\":\"1.0\",\"operations\":[{\"op\":\"REMOVE_BLOCK\",\"blockId\":\"sales_chart\"}]}");
        assertThat(removed.isQueryPerformed()).isFalse();
        assertThat(removed.getDiff().removedBlocks()).containsExactly("sales_chart");
        verify(queryExecutionService, never()).execute(any());

        // 4) 改表格列：展示类，重新投影但不查库
        AiReportRevisionResultDTO reprojected = evaluate("明细只留客户列", AiReportRevisionFixture.REPROJECTION_PLAN);
        assertThat(reprojected.isQueryPerformed()).isFalse();
        assertThat(reprojected.getDiff().modifiedBlocks()).containsExactly("sales_table");
        verify(queryExecutionService, never()).execute(any());

        // 5) 改粒度：数据类，必须受控查询（一次），沿用原数据集引用
        AiReportRevisionResultDTO grain = evaluate("按周汇总", AiReportRevisionFixture.GRAIN_PLAN);
        assertThat(grain.isQueryPerformed()).isTrue();
        assertThat(grain.getDiff().queryRequired()).isTrue();
        assertThat(grain.getDiff().addedDatasetRefs()).isEmpty();
        verify(queryExecutionService, times(1)).execute(any());
        assertThat(savedSpec().getDataJson()).contains("plan_bbbbbbbbbbbb");

        // 6) 新增指标：数据类，必须受控查询（一次），新增块与数据集引用
        AiReportRevisionResultDTO metric = evaluate("加一个净销售额指标", AiReportRevisionFixture.DATA_PLAN);
        assertThat(metric.isQueryPerformed()).isTrue();
        assertThat(metric.getDiff().addedBlocks()).hasSize(1);
        assertThat(metric.getDiff().addedDatasetRefs()).hasSize(1);
        verify(queryExecutionService, times(2)).execute(any());

        // 7) 未知操作：拒绝，不保存（评测结论必须包含"不会做的事"）
        assertThatThrownBy(() -> evaluate(
                        "把报表删掉",
                        "{\"schemaVersion\":\"1.0\",\"operations\":[{\"op\":\"DELETE_REPORT\",\"value\":\"1\"}]}"))
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_REPORT_REVISION_PLAN_INVALID.getCode()));
    }

    @Test
    void evaluationKeepsOriginalVersionUntouchedAndAlwaysAppendsVersion() {
        evaluate("把柱状图换成折线图", AiReportRevisionFixture.PRESENTATION_PLAN);
        AiReportSaveDTO saved = savedSpec();

        // 只新增版本：带乐观锁版本 + 同一报表编号；不调用任何"更新版本内容"的入口
        assertThat(saved.getId()).isEqualTo(71L);
        assertThat(saved.getVersion()).isZero();
        assertThat(saved.getMode()).isEqualTo(AiReportDO.MODE_SNAPSHOT);
        verify(reportService, never()).create(any(AiReportSaveDTO.class));
        verify(reportService, never()).getVersion(anyLong(), eq(2));
        assertThat(saved.getSchemaVersion()).isEqualTo("1.0");
        assertThat(saved.getName()).isEqualTo("8 月销售");
    }

    @Test
    void evaluationDocumentsDeterministicSubstituteNotModelQuality() {
        // 本评测的模型是脚本化替身：结论只证明服务端判定与落库口径，不证明模型理解能力。
        // 真实模型效果评测见证据文档"未验证项"，由 Q04/Q10 承接。
        when(model.propose(any(), any(), any())).thenReturn(AiReportRevisionFixture.PRESENTATION_PLAN);
        AiReportRevisionResultDTO result = service.revise(request("把柱状图换成折线图"));
        assertThat(result.getNotes()).anyMatch(note -> note.contains("未重新查询数据源"));
        assertThat(result.getNotes()).noneMatch(note -> note.contains("模型效果"));
    }

    @Test
    void evaluationRecordsCatalogGivenToModel() {
        when(model.propose(any(), any(), any())).thenReturn(AiReportRevisionFixture.PRESENTATION_PLAN);
        service.revise(request("把柱状图换成折线图"));

        ArgumentCaptor<String> catalog = ArgumentCaptor.forClass(String.class);
        verify(model).propose(any(), catalog.capture(), any());
        // 模型只看得到已有块/列与本次授权的数据集（行数据不进目录）
        assertThat(catalog.getValue())
                .contains("sales_result")
                .contains("customer_name")
                .contains("queryableDatasets")
                .doesNotContain("450.00");
    }

    @Test
    void evaluationUsesNoQueryForPresentationEvenWhenDatasetIsUnavailable() {
        // 展示类不依赖数据集可用性：即使数据集查询会失败，展示类修改也必须成功
        when(queryExecutionService.execute(any(AiRunQueryExecutionRequestDTO.class)))
                .thenThrow(new ServiceException(AiErrorCodeConstants.AI_QUERY_SCOPE_REQUIRED));

        AiReportRevisionResultDTO result = evaluate("把柱状图换成折线图", AiReportRevisionFixture.PRESENTATION_PLAN);

        assertThat(result.getOutcome()).isEqualTo(AiReportRevisionResultDTO.OUTCOME_APPLIED);
        assertThat(result.isQueryPerformed()).isFalse();
        verify(queryExecutionService, never()).execute(any());
    }

    @Test
    void evaluationFixtureCoversQueryableDatasetCatalog() {
        // 目录里必须带上"本次允许重新查询的数据集"，否则模型无法提出数据类操作
        when(model.propose(any(), any(), any())).thenReturn(AiReportRevisionFixture.PRESENTATION_PLAN);
        service.revise(request("把柱状图换成折线图"));

        ArgumentCaptor<String> catalog = ArgumentCaptor.forClass(String.class);
        verify(model).propose(any(), catalog.capture(), any());
        assertThat(catalog.getValue()).contains("\"datasetId\":81").contains(AiReportRevisionFixture.DATASET_CODE);
        assertThat(Map.of()).isEmpty();
        assertThat(List.of()).isEmpty();
    }
}
