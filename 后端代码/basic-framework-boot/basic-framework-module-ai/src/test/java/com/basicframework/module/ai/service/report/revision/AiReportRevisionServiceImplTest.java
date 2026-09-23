package com.basicframework.module.ai.service.report.revision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.basicframework.module.ai.service.run.dto.AiRunQueryExecutionResultDTO;
import com.basicframework.module.ai.testfixture.AiReportRevisionFixture;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * R05 对话修改服务：展示类不查库、数据类必查库、澄清不建版本、并发与权限拒绝。
 *
 * <p>用 Mockito 固定"模型说了什么"与"受控查询返回了什么"，从而把服务端的判定逻辑（分类、绑定、
 * 保存口径）单独验证；真实数据链路由 {@code AiReportRevisionIT} 在真实 MySQL 上验证。
 */
class AiReportRevisionServiceImplTest {

    private static final Long REPORT_ID = 71L;

    private final AiReportService reportService = mock(AiReportService.class);

    private final AiDatasetService datasetService = mock(AiDatasetService.class);

    private final AiAuthorizationService authorizationService = mock(AiAuthorizationService.class);

    private final AiConversationSubjectResolver subjectResolver = mock(AiConversationSubjectResolver.class);

    private final AiRunQueryExecutionService queryExecutionService = mock(AiRunQueryExecutionService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<AiReportRevisionModel> modelProvider = mock(ObjectProvider.class);

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
        when(reportService.getReport(REPORT_ID)).thenReturn(report());
        when(reportService.getVersion(REPORT_ID, 1)).thenReturn(baseVersion());
        when(reportService.saveVersion(any(AiReportSaveDTO.class))).thenReturn(REPORT_ID);
        when(datasetService.getDataset(81L)).thenReturn(dataset());
        when(authorizationService.authorize(
                        eq(9L), eq("USER"), eq("alice"), eq(AiResourceType.DATASET), any(), any(), any()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(true).setScopeFingerprint("f".repeat(64)));
    }

    private static AiReportDO report() {
        return new AiReportDO()
                .setId(REPORT_ID)
                .setCode("r05_sales")
                .setName("8 月销售")
                .setDescription("R05 用例")
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
                .setReportId(REPORT_ID)
                .setVersionNo(1)
                .setMode(AiReportDO.MODE_SNAPSHOT)
                .setSpecJson(AiReportRevisionFixture.BASE_SPEC)
                .setDataJson(AiReportRevisionFixture.BASE_DATA)
                .setSourcesJson(AiReportRevisionFixture.BASE_SOURCES)
                .setCompleteness("COMPLETE")
                .setCreatedByRun("run_r05_base");
    }

    private static AiDatasetDO dataset() {
        return new AiDatasetDO()
                .setId(81L)
                .setCode(AiReportRevisionFixture.DATASET_CODE)
                .setConnectorId(7L)
                .setSourceObject("it_query.orders")
                .setStatus(AiDatasetDO.STATUS_ENABLED);
    }

    private static AiReportRevisionRequestDTO request() {
        return new AiReportRevisionRequestDTO()
                .setReportId(REPORT_ID)
                .setBaseVersionNo(1)
                .setVersion(0)
                .setInstruction("把柱状图换成折线图")
                .setEndpointId(5L)
                .setDatasetId(81L)
                .setDatasetVersionId(91L)
                .setRowScope(QueryScope.eq("customer", "C001"))
                .setCreatedByRun("run_r05_revision");
    }

    private void modelSays(String planJson) {
        AiReportRevisionModel model = mock(AiReportRevisionModel.class);
        when(model.propose(any(), any(), any())).thenReturn(planJson);
        when(modelProvider.getIfAvailable()).thenReturn(model);
    }

    private static void assertCode(Throwable throwable, Integer expectedCode) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expectedCode);
    }

    @Test
    void presentationRevisionReusesStoredDataWithoutQuerying() {
        modelSays(AiReportRevisionFixture.PRESENTATION_PLAN);

        AiReportRevisionResultDTO result = service.revise(request());

        assertThat(result.getOutcome()).isEqualTo(AiReportRevisionResultDTO.OUTCOME_APPLIED);
        assertThat(result.getNewVersionNo()).isEqualTo(2);
        assertThat(result.isQueryPerformed()).isFalse();
        assertThat(result.getDiff().modifiedBlocks()).containsExactly("sales_chart");
        assertThat(result.getNotes()).anyMatch(note -> note.contains("未重新查询数据源"));
        // 换图不重复查库：受控查询链路完全没被调用
        verifyNoInteractions(queryExecutionService);

        AiReportSaveDTO saved = capturedSave();
        assertThat(saved.getSpecJson()).contains("\"chartType\":\"line\"");
        assertThat(saved.getVersion()).isZero();
        assertThat(saved.getCreatedByRun()).isEqualTo("run_r05_revision");
        // 数据复用基础版本的结果行并重新绑定：数字不变、被删块的数据不残留
        assertThat(saved.getDataJson()).contains("客户二").contains("450.00");
        assertThat(saved.getCompleteness()).isEqualTo("COMPLETE");
        assertThat(saved.getSourcesJson()).isEqualTo(AiReportRevisionFixture.BASE_SOURCES);
    }

    @Test
    void dataRevisionRunsControlledQueryAndAddsDatasetDependency() {
        modelSays(AiReportRevisionFixture.DATA_PLAN);
        when(queryExecutionService.execute(any(AiRunQueryExecutionRequestDTO.class)))
                .thenReturn(AiReportRevisionFixture.queryResult());

        AiReportRevisionResultDTO result = service.revise(request());

        assertThat(result.isQueryPerformed()).isTrue();
        assertThat(result.getDiff().queryRequired()).isTrue();
        assertThat(result.getDiff().addedBlocks()).hasSize(1);
        ArgumentCaptor<AiRunQueryExecutionRequestDTO> query =
                ArgumentCaptor.forClass(AiRunQueryExecutionRequestDTO.class);
        verify(queryExecutionService).execute(query.capture());
        // 行范围来自授权层、允许数据集限定为本次声明的那一个：模型无法扩大范围
        assertThat(query.getValue().getRowScope().conditions()).hasSize(1);
        assertThat(query.getValue().getAllowedDatasetIds()).containsExactly(81L);
        assertThat(query.getValue().getQuestion()).isEqualTo("把柱状图换成折线图");

        AiReportSaveDTO saved = capturedSave();
        // 新数据集进入依赖清单：保存时逐项 A03 再鉴权（失权后拒绝展示）
        assertThat(saved.getSourcesJson()).contains("DATASET").contains("dset_" + AiReportRevisionFixture.DATASET_CODE);
        // 数据来自受控查询结果（真实执行），不是模型编的数字
        assertThat(saved.getDataJson()).contains("plan_bbbbbbbbbbbb");
    }

    @Test
    void clarificationCreatesNoVersionAndKeepsReportUntouched() {
        modelSays(AiReportRevisionFixture.DATA_PLAN);
        when(queryExecutionService.execute(any(AiRunQueryExecutionRequestDTO.class)))
                .thenReturn(AiReportRevisionFixture.clarification());

        AiReportRevisionResultDTO result = service.revise(request());

        assertThat(result.getOutcome()).isEqualTo(AiReportRevisionResultDTO.OUTCOME_CLARIFICATION);
        assertThat(result.getClarificationQuestion()).contains("销售额");
        assertThat(result.getClarificationCandidates()).hasSize(1);
        assertThat(result.getNewVersionNo()).isNull();
        verify(reportService, never()).saveVersion(any(AiReportSaveDTO.class));
    }

    @Test
    void revisionWithoutModelFailsClosed() {
        when(modelProvider.getIfAvailable()).thenReturn(null);

        assertCode(
                assertThatThrownBy(() -> service.revise(request())).actual(),
                AiErrorCodeConstants.AI_REPORT_REVISION_MODEL_UNAVAILABLE.getCode());
        verify(reportService, never()).saveVersion(any(AiReportSaveDTO.class));
    }

    @Test
    void dataRevisionRefusesDatasetWithoutCurrentGrant() {
        modelSays(AiReportRevisionFixture.DATA_PLAN);
        when(authorizationService.authorize(anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(false).setDenyReason("GRANT_NOT_FOUND"));

        assertCode(
                assertThatThrownBy(() -> service.revise(request())).actual(),
                AiErrorCodeConstants.AI_ACCESS_DENIED.getCode());
        // 授权判定在前：没有权限就不该发起查询
        verifyNoInteractions(queryExecutionService);
    }

    @Test
    void projectionChangeWithoutStoredRowsIsRefused() {
        modelSays(AiReportRevisionFixture.REPROJECTION_PLAN);
        when(reportService.getVersion(REPORT_ID, 1)).thenReturn(baseVersion().setDataJson("{\"kind\":\"REPORT\"}"));

        assertCode(
                assertThatThrownBy(() -> service.revise(request())).actual(),
                AiErrorCodeConstants.AI_REPORT_REVISION_UNSUPPORTED.getCode());
        verify(reportService, never()).saveVersion(any(AiReportSaveDTO.class));
    }

    @Test
    void invalidRequestAndUnknownSubjectAreRejected() {
        modelSays(AiReportRevisionFixture.PRESENTATION_PLAN);

        assertCode(
                assertThatThrownBy(() -> service.revise(null)).actual(),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                assertThatThrownBy(() -> service.revise(request().setInstruction(" ")))
                        .actual(),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                assertThatThrownBy(() -> service.revise(request().setVersion(null)))
                        .actual(),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());

        when(subjectResolver.resolveCurrent()).thenReturn(Optional.empty());
        assertCode(
                assertThatThrownBy(() -> service.revise(request())).actual(),
                AiErrorCodeConstants.AI_ACCESS_DENIED.getCode());
    }

    @Test
    void concurrentModificationBubblesUpAsConflict() {
        modelSays(AiReportRevisionFixture.PRESENTATION_PLAN);
        when(reportService.saveVersion(any(AiReportSaveDTO.class)))
                .thenThrow(new ServiceException(AiErrorCodeConstants.AI_STATE_CONFLICT));

        assertCode(
                assertThatThrownBy(() -> service.revise(request())).actual(),
                AiErrorCodeConstants.AI_STATE_CONFLICT.getCode());
    }

    @Test
    void invalidModelOutputIsRejectedWithoutSaving() {
        modelSays("{\"schemaVersion\":\"1.0\",\"operations\":[{\"op\":\"REWRITE_EVERYTHING\"}]}");

        assertCode(
                assertThatThrownBy(() -> service.revise(request())).actual(),
                AiErrorCodeConstants.AI_REPORT_REVISION_PLAN_INVALID.getCode());
        verify(reportService, never()).saveVersion(any(AiReportSaveDTO.class));
    }

    @Test
    void catalogOnlyExposesExistingBlocksAndAuthorizedDataset() {
        AiReportRevisionModel model = mock(AiReportRevisionModel.class);
        when(model.propose(any(), any(), any())).thenReturn(AiReportRevisionFixture.PRESENTATION_PLAN);
        when(modelProvider.getIfAvailable()).thenReturn(model);

        service.revise(request());

        ArgumentCaptor<String> catalog = ArgumentCaptor.forClass(String.class);
        verify(model).propose(eq(AiReportRevisionFixture.BASE_SPEC), catalog.capture(), eq("把柱状图换成折线图"));
        // 模型看到的目录：已有块与结果列 + 本次授权的数据集；不含数据行、不含其他数据集
        assertThat(catalog.getValue())
                .contains("sales_chart")
                .contains("total_net_amount")
                .contains("queryableDatasets");
        assertThat(catalog.getValue()).doesNotContain("客户二").doesNotContain("450.00");
    }

    @Test
    void partialQueryResultWeakensVersionCompleteness() {
        modelSays(AiReportRevisionFixture.DATA_PLAN);
        when(queryExecutionService.execute(any(AiRunQueryExecutionRequestDTO.class)))
                .thenReturn(AiReportRevisionFixture.queryResult()
                        .setCompleteness(AiRunQueryExecutionResultDTO.PARTIAL)
                        .setTruncated(true));

        service.revise(request());

        // 受控查询被截断：版本完整性如实标 PARTIAL，不谎称完整统计
        assertThat(capturedSave().getCompleteness()).isEqualTo("PARTIAL");
    }

    private AiReportSaveDTO capturedSave() {
        ArgumentCaptor<AiReportSaveDTO> captor = ArgumentCaptor.forClass(AiReportSaveDTO.class);
        verify(reportService).saveVersion(captor.capture());
        return captor.getValue();
    }
}
