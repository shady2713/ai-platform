package com.basicframework.module.ai.service.report.refresh;

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
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetDO;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetVersionDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportRefreshDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportVersionDO;
import com.basicframework.module.ai.dal.mysql.dataset.AiDatasetMapper;
import com.basicframework.module.ai.dal.mysql.report.AiReportMapper;
import com.basicframework.module.ai.dal.mysql.report.AiReportRefreshMapper;
import com.basicframework.module.ai.dal.mysql.report.AiReportVersionMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.domain.query.QueryScope;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.authorization.dto.AiAuthorizationDecisionDTO;
import com.basicframework.module.ai.service.conversation.AiConversationSubject;
import com.basicframework.module.ai.service.conversation.AiConversationSubjectResolver;
import com.basicframework.module.ai.service.dataset.AiDatasetService;
import com.basicframework.module.ai.service.report.persistence.AiReportScopeRef;
import com.basicframework.module.ai.service.report.persistence.AiReportScopeRefs;
import com.basicframework.module.ai.service.report.refresh.dto.AiReportRefreshRequestDTO;
import com.basicframework.module.ai.service.report.refresh.dto.AiReportRefreshResultDTO;
import com.basicframework.module.ai.service.report.refresh.dto.AiReportRefreshStateDTO;
import com.basicframework.module.ai.service.report.validation.AiReportDataBinder;
import com.basicframework.module.ai.service.report.validation.AiReportSpecValidator;
import com.basicframework.module.ai.service.run.AiRunQueryExecutionService;
import com.basicframework.module.ai.service.run.dto.AiRunQueryExecutionFixedRequestDTO;
import com.basicframework.module.ai.service.run.dto.AiRunQueryExecutionResultDTO;
import com.basicframework.module.ai.testfixture.AiReportRevisionFixture;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * R06 刷新服务：按当前权限执行固定查询版本、失败保留旧结果并留痕、重复刷新幂等、失权即停。
 */
class AiReportRefreshServiceImplTest {

    private static final Long REPORT_ID = 71L;

    private final AiReportMapper reportMapper = mock(AiReportMapper.class);

    private final AiReportVersionMapper versionMapper = mock(AiReportVersionMapper.class);

    private final AiReportRefreshMapper refreshMapper = mock(AiReportRefreshMapper.class);

    private final AiDatasetMapper datasetMapper = mock(AiDatasetMapper.class);

    private final AiDatasetService datasetService = mock(AiDatasetService.class);

    private final AiAuthorizationService authorizationService = mock(AiAuthorizationService.class);

    private final AiConversationSubjectResolver subjectResolver = mock(AiConversationSubjectResolver.class);

    private final AiRunQueryExecutionService queryExecutionService = mock(AiRunQueryExecutionService.class);

    private final AiReportRefreshServiceImpl service = new AiReportRefreshServiceImpl(
            reportMapper,
            versionMapper,
            refreshMapper,
            datasetMapper,
            datasetService,
            authorizationService,
            subjectResolver,
            queryExecutionService,
            new AiReportSpecValidator(),
            new AiReportDataBinder());

    @BeforeEach
    void setUp() {
        when(subjectResolver.resolveCurrent())
                .thenReturn(Optional.of(new AiConversationSubject(9L, AiSubjectType.USER, "alice")));
        when(reportMapper.selectById(REPORT_ID)).thenReturn(report());
        when(versionMapper.selectByVersionNo(REPORT_ID, 1)).thenReturn(baseVersion());
        when(reportMapper.updateWithVersion(any(AiReportDO.class), any())).thenReturn(1);
        when(datasetMapper.selectByCode(AiReportRevisionFixture.DATASET_CODE)).thenReturn(dataset());
        when(datasetService.getVersionPage(anyLong(), any()))
                .thenReturn(new PageResult<>(List.of(datasetVersion()), 1L));
        when(authorizationService.reauthorizeHistorical(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(true).setScopeFingerprint("f".repeat(64)));
        when(queryExecutionService.executeFixed(any(AiRunQueryExecutionFixedRequestDTO.class)))
                .thenReturn(AiReportRevisionFixture.queryResult());
    }

    private static AiReportDO report() {
        return new AiReportDO()
                .setId(REPORT_ID)
                .setCode("r06_sales")
                .setName("8 月销售")
                .setMode(AiReportDO.MODE_REFRESHABLE)
                .setApplicationId(9L)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setLatestVersionNo(1)
                .setPublishedVersionNo(1)
                .setVersion(0);
    }

    private static AiReportVersionDO baseVersion() {
        return new AiReportVersionDO()
                .setId(81L)
                .setReportId(REPORT_ID)
                .setVersionNo(1)
                .setMode(AiReportDO.MODE_REFRESHABLE)
                .setSpecJson(AiReportRevisionFixture.BASE_SPEC)
                .setSourcesJson(AiReportRevisionFixture.BASE_SOURCES)
                .setScopeRefsJson(AiReportScopeRefs.toJson(scopeRefs()))
                .setScopeFingerprint(AiReportScopeRefs.fingerprint(scopeRefs()))
                .setCompleteness("COMPLETE");
    }

    /** 基础版本保存时的依赖与范围指纹（与 A03 判定返回的指纹一致）。 */
    private static java.util.List<AiReportScopeRef> scopeRefs() {
        return java.util.List.of(AiReportScopeRefs.withFingerprint(
                new AiReportScopeRef("DATASET", "dset_" + AiReportRevisionFixture.DATASET_CODE, null), "f".repeat(64)));
    }

    private static AiDatasetDO dataset() {
        return new AiDatasetDO()
                .setId(81L)
                .setCode(AiReportRevisionFixture.DATASET_CODE)
                .setConnectorId(7L)
                .setSourceObject("it_query.orders")
                .setStatus(AiDatasetDO.STATUS_ENABLED);
    }

    private static AiDatasetVersionDO datasetVersion() {
        return new AiDatasetVersionDO()
                .setId(91L)
                .setDatasetId(81L)
                .setVersionNo(1)
                .setStatus(AiDatasetVersionDO.STATUS_PUBLISHED)
                .setVerificationStatus(AiDatasetVersionDO.VERIFICATION_VERIFIED);
    }

    private static AiReportRefreshRequestDTO request() {
        return new AiReportRefreshRequestDTO().setReportId(REPORT_ID).setRowScope(QueryScope.eq("customer", "C001"));
    }

    private static void assertCode(Throwable throwable, Integer expectedCode) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expectedCode);
    }

    private AiReportRefreshDO capturedAttempt() {
        ArgumentCaptor<AiReportRefreshDO> captor = ArgumentCaptor.forClass(AiReportRefreshDO.class);
        verify(refreshMapper).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    void refreshExecutesFixedPlanAndSwitchesPublishedVersionAtomically() {
        AiReportRefreshResultDTO result = service.refresh(request());

        assertThat(result.getStatus()).isEqualTo(AiReportRefreshResultDTO.STATUS_OK);
        assertThat(result.getResultVersionNo()).isEqualTo(2);
        assertThat(result.getDataJson()).contains("plan_bbbbbbbbbbbb");
        assertThat(result.getCompleteness()).isEqualTo(AiRunQueryExecutionResultDTO.COMPLETE);

        // 固定计划按当前权限执行（行范围来自授权层），不调用模型/规划
        ArgumentCaptor<AiRunQueryExecutionFixedRequestDTO> query =
                ArgumentCaptor.forClass(AiRunQueryExecutionFixedRequestDTO.class);
        verify(queryExecutionService).executeFixed(query.capture());
        assertThat(query.getValue().getRowScope().conditions()).hasSize(1);
        assertThat(query.getValue().getPlanJson()).contains("dset_sales_demo");

        // 原子切换：CAS 推进 latest/published，插入新版本（可刷新版本仍不存数据）
        ArgumentCaptor<AiReportVersionDO> inserted = ArgumentCaptor.forClass(AiReportVersionDO.class);
        verify(versionMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getVersionNo()).isEqualTo(2);
        assertThat(inserted.getValue().getDataJson()).isNull();
        assertThat(inserted.getValue().getAsOf()).isNotNull();
        assertThat(inserted.getValue().getCompleteness()).isEqualTo("COMPLETE");

        // 尝试记录：成功带数据与范围指纹
        AiReportRefreshDO attempt = capturedAttempt();
        assertThat(attempt.getStatus()).isEqualTo(AiReportRefreshDO.STATUS_OK);
        assertThat(attempt.getResultVersionNo()).isEqualTo(2);
        assertThat(attempt.getDataJson()).contains("客户二");
        assertThat(attempt.getScopeFingerprint()).hasSize(64);
    }

    @Test
    void unchangedUpstreamDataDoesNotCreateAnotherVersion() {
        AiReportRefreshDO lastOk = new AiReportRefreshDO()
                .setReportId(REPORT_ID)
                .setStatus(AiReportRefreshDO.STATUS_OK)
                .setDataJson(AiReportRevisionFixture.queryResult() == null ? null : null);
        // 上次成功刷新的数据与本次完全相同 → 幂等
        String dataJson = service.refresh(request()).getDataJson();
        lastOk.setDataJson(dataJson);
        when(refreshMapper.selectLastOkByReport(REPORT_ID)).thenReturn(lastOk);

        AiReportRefreshResultDTO result = service.refresh(request());

        assertThat(result.getStatus()).isEqualTo(AiReportRefreshResultDTO.STATUS_UNCHANGED);
        assertThat(result.getReason()).isEqualTo("UNCHANGED");
        // 只有第一次刷新推进了版本；幂等的一次不再写版本
        verify(versionMapper, org.mockito.Mockito.times(1)).insert(any(AiReportVersionDO.class));
        verify(reportMapper, org.mockito.Mockito.times(1)).updateWithVersion(any(AiReportDO.class), any());
    }

    @Test
    void refreshWithoutRowScopeIsRecordedAsFailureAndKeepsOldResult() {
        AiReportRefreshResultDTO result = service.refresh(request().setRowScope(null));

        assertThat(result.getStatus()).isEqualTo(AiReportRefreshResultDTO.STATUS_FAILED);
        assertThat(result.getReason())
                .isEqualTo(String.valueOf(AiErrorCodeConstants.AI_REPORT_REFRESH_SCOPE_REQUIRED.getCode()));
        verifyNoInteractions(queryExecutionService);
        verify(versionMapper, never()).insert(any(AiReportVersionDO.class));
        assertThat(capturedAttempt().getStatus()).isEqualTo(AiReportRefreshDO.STATUS_FAILED);
    }

    @Test
    void disabledOrDriftedSourceStopsBeforeAnyQuery() {
        when(datasetMapper.selectByCode(AiReportRevisionFixture.DATASET_CODE))
                .thenReturn(dataset().setStatus("DISABLED"));
        AiReportRefreshResultDTO disabled = service.refresh(request());
        assertThat(disabled.getStatus()).isEqualTo(AiReportRefreshResultDTO.STATUS_FAILED);
        assertThat(disabled.getReason()).isEqualTo(String.valueOf(AiErrorCodeConstants.AI_DATASET_DISABLED.getCode()));

        when(datasetMapper.selectByCode(AiReportRevisionFixture.DATASET_CODE)).thenReturn(dataset());
        when(datasetService.getVersionPage(anyLong(), any()))
                .thenReturn(new PageResult<>(
                        List.of(datasetVersion().setVerificationStatus(AiDatasetVersionDO.VERIFICATION_DRIFTED)), 1L));
        AiReportRefreshResultDTO drifted = service.refresh(request());
        assertThat(drifted.getStatus()).isEqualTo(AiReportRefreshResultDTO.STATUS_FAILED);
        assertThat(drifted.getReason())
                .isEqualTo(String.valueOf(AiErrorCodeConstants.AI_DATASET_VERSION_DRIFTED.getCode()));

        // 停用/漂移都不执行查询、不产生新版本
        verifyNoInteractions(queryExecutionService);
        verify(versionMapper, never()).insert(any(AiReportVersionDO.class));
    }

    @Test
    void queryFailureIsRecordedAndOldResultStaysPublished() {
        when(queryExecutionService.executeFixed(any(AiRunQueryExecutionFixedRequestDTO.class)))
                .thenThrow(new ServiceException(AiErrorCodeConstants.AI_QUERY_SCOPE_REQUIRED));

        AiReportRefreshResultDTO result = service.refresh(request());

        assertThat(result.getStatus()).isEqualTo(AiReportRefreshResultDTO.STATUS_FAILED);
        assertThat(result.getReason())
                .isEqualTo(String.valueOf(AiErrorCodeConstants.AI_QUERY_SCOPE_REQUIRED.getCode()));
        verify(versionMapper, never()).insert(any(AiReportVersionDO.class));
        assertThat(capturedAttempt().getReason()).isEqualTo(result.getReason());
    }

    @Test
    void bindingMismatchIsRecordedInsteadOfWritingHalfBakedResult() {
        // 受控查询结果的列缺少声明列（口径变了）→ 绑定失败 → 留痕，不写版本
        when(queryExecutionService.executeFixed(any(AiRunQueryExecutionFixedRequestDTO.class)))
                .thenReturn(AiReportRevisionFixture.queryResult()
                        .setColumns(List.of(
                                new AiRunQueryExecutionResultDTO.Column("customer_name", "客户", "STRING", null))));

        AiReportRefreshResultDTO result = service.refresh(request());

        assertThat(result.getStatus()).isEqualTo(AiReportRefreshResultDTO.STATUS_FAILED);
        assertThat(result.getReason())
                .isEqualTo(String.valueOf(AiErrorCodeConstants.AI_REPORT_BINDING_MISMATCH.getCode()));
        verify(versionMapper, never()).insert(any(AiReportVersionDO.class));
    }

    @Test
    void concurrentModificationIsRecordedAsConflict() {
        when(reportMapper.updateWithVersion(any(AiReportDO.class), any())).thenReturn(0);

        AiReportRefreshResultDTO result = service.refresh(request());

        assertThat(result.getStatus()).isEqualTo(AiReportRefreshResultDTO.STATUS_FAILED);
        assertThat(result.getReason()).isEqualTo(String.valueOf(AiErrorCodeConstants.AI_STATE_CONFLICT.getCode()));
        verify(versionMapper, never()).insert(any(AiReportVersionDO.class));
    }

    @Test
    void revokedScopeStopsRefreshAndRefusesReadingOldResult() {
        when(authorizationService.reauthorizeHistorical(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(false).setDenyReason("GRANT_NOT_FOUND"));

        assertCode(
                assertThatThrownBy(() -> service.refresh(request())).actual(),
                AiErrorCodeConstants.AI_REPORT_SCOPE_CHANGED.getCode());
        // 失权是前置条件失败：不落尝试记录、不执行查询、不新增版本
        verifyNoInteractions(queryExecutionService);
        verify(versionMapper, never()).insert(any(AiReportVersionDO.class));
        verify(refreshMapper, never()).insert(any(AiReportRefreshDO.class));

        // 读取上一次结果同样复核范围：失权即拒绝（AT-048）
        AiReportRefreshDO lastOk = new AiReportRefreshDO()
                .setReportId(REPORT_ID)
                .setStatus(AiReportRefreshDO.STATUS_OK)
                .setDataJson("{\"kind\":\"REPORT\"}")
                .setScopeRefsJson(AiReportScopeRefs.toJson(scopeRefs()))
                .setScopeFingerprint(AiReportScopeRefs.fingerprint(scopeRefs()));
        when(refreshMapper.selectLastByReport(REPORT_ID)).thenReturn(lastOk);
        when(refreshMapper.selectLastOkByReport(REPORT_ID)).thenReturn(lastOk);
        assertCode(
                assertThatThrownBy(() -> service.lastState(REPORT_ID)).actual(),
                AiErrorCodeConstants.AI_REPORT_SCOPE_CHANGED.getCode());
    }

    @Test
    void snapshotReportCannotBeRefreshed() {
        when(reportMapper.selectById(REPORT_ID)).thenReturn(report().setMode(AiReportDO.MODE_SNAPSHOT));

        assertCode(
                assertThatThrownBy(() -> service.refresh(request())).actual(),
                AiErrorCodeConstants.AI_REPORT_REFRESH_NOT_SUPPORTED.getCode());
    }

    @Test
    void anotherSubjectsReportLooksMissing() {
        when(subjectResolver.resolveCurrent())
                .thenReturn(Optional.of(new AiConversationSubject(9L, AiSubjectType.USER, "bob")));

        assertCode(
                assertThatThrownBy(() -> service.refresh(request())).actual(),
                AiErrorCodeConstants.AI_REPORT_NOT_FOUND.getCode());
        assertCode(
                assertThatThrownBy(() -> service.lastState(REPORT_ID)).actual(),
                AiErrorCodeConstants.AI_REPORT_NOT_FOUND.getCode());
    }

    @Test
    void jobWithoutSessionUsesReportOwnership() {
        // 作业没有会话身份：以报表自身归属列为主体（不接受外部指定）
        when(subjectResolver.resolveCurrent()).thenReturn(Optional.empty());

        AiReportRefreshResultDTO result = service.refresh(request());

        assertThat(result.getStatus()).isEqualTo(AiReportRefreshResultDTO.STATUS_OK);
        verify(authorizationService)
                .reauthorizeHistorical(
                        eq(9L), eq("USER"), eq("alice"), eq(AiResourceType.DATASET), any(), any(), any());
    }

    @Test
    void lastStateReportsAttemptAndReauthorizesData() {
        when(refreshMapper.selectLastByReport(REPORT_ID)).thenReturn(null);
        AiReportRefreshStateDTO none = service.lastState(REPORT_ID);
        assertThat(none.isAttempted()).isFalse();

        AiReportRefreshDO failed = new AiReportRefreshDO()
                .setReportId(REPORT_ID)
                .setStatus(AiReportRefreshDO.STATUS_FAILED)
                .setReason(String.valueOf(AiErrorCodeConstants.AI_DATASET_DISABLED.getCode()))
                .setAsOf(java.time.LocalDateTime.of(2026, 9, 23, 10, 0));
        when(refreshMapper.selectLastByReport(REPORT_ID)).thenReturn(failed);
        AiReportRefreshStateDTO failure = service.lastState(REPORT_ID);
        assertThat(failure.isAttempted()).isTrue();
        assertThat(failure.getStatus()).isEqualTo("FAILED");
        assertThat(failure.getReason()).isEqualTo(String.valueOf(AiErrorCodeConstants.AI_DATASET_DISABLED.getCode()));
        assertThat(failure.getAsOf()).isNotNull();
        assertThat(failure.getDataJson()).isNull();

        AiReportRefreshDO lastOk = new AiReportRefreshDO()
                .setReportId(REPORT_ID)
                .setStatus(AiReportRefreshDO.STATUS_OK)
                .setCompleteness("COMPLETE")
                .setResultVersionNo(2)
                .setDataJson("{\"kind\":\"REPORT\"}")
                .setScopeRefsJson(AiReportScopeRefs.toJson(scopeRefs()))
                .setScopeFingerprint(AiReportScopeRefs.fingerprint(scopeRefs()));
        when(refreshMapper.selectLastOkByReport(REPORT_ID)).thenReturn(lastOk);
        AiReportRefreshStateDTO ok = service.lastState(REPORT_ID);
        assertThat(ok.getResultVersionNo()).isEqualTo(2);
        assertThat(ok.getDataJson()).contains("REPORT");
        assertThat(ok.getCompleteness()).isEqualTo("COMPLETE");
    }

    @Test
    void invalidRequestIsRejected() {
        assertCode(
                assertThatThrownBy(() -> service.refresh(null)).actual(),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                assertThatThrownBy(() -> service.refresh(new AiReportRefreshRequestDTO()))
                        .actual(),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
        assertCode(
                assertThatThrownBy(() -> service.lastState(null)).actual(),
                AiErrorCodeConstants.AI_REQUEST_INVALID.getCode());
    }

    @Test
    void missingReportOrVersionLooksMissing() {
        when(reportMapper.selectById(REPORT_ID)).thenReturn(null);
        assertCode(
                assertThatThrownBy(() -> service.refresh(request())).actual(),
                AiErrorCodeConstants.AI_REPORT_NOT_FOUND.getCode());

        when(reportMapper.selectById(REPORT_ID)).thenReturn(report());
        when(versionMapper.selectByVersionNo(REPORT_ID, 1)).thenReturn(null);
        assertCode(
                assertThatThrownBy(() -> service.refresh(request())).actual(),
                AiErrorCodeConstants.AI_REPORT_VERSION_NOT_FOUND.getCode());
    }

    @Test
    void datasetVersionNotPublishedStopsRefresh() {
        when(datasetService.getVersionPage(anyLong(), any()))
                .thenReturn(new PageResult<>(List.of(datasetVersion().setStatus(AiDatasetVersionDO.STATUS_DRAFT)), 1L));

        AiReportRefreshResultDTO result = service.refresh(request());

        assertThat(result.getStatus()).isEqualTo(AiReportRefreshResultDTO.STATUS_FAILED);
        assertThat(result.getReason())
                .isEqualTo(String.valueOf(AiErrorCodeConstants.AI_DATASET_VERSION_NOT_PUBLISHED.getCode()));
        verifyNoInteractions(queryExecutionService);
    }

    @Test
    void missingDatasetOrVersionStopsRefresh() {
        when(datasetMapper.selectByCode(AiReportRevisionFixture.DATASET_CODE)).thenReturn(null);
        AiReportRefreshResultDTO missingDataset = service.refresh(request());
        assertThat(missingDataset.getReason())
                .isEqualTo(String.valueOf(AiErrorCodeConstants.AI_DATASET_VERSION_NOT_FOUND.getCode()));

        when(datasetMapper.selectByCode(AiReportRevisionFixture.DATASET_CODE)).thenReturn(dataset());
        when(datasetService.getVersionPage(anyLong(), any())).thenReturn(new PageResult<>(List.of(), 0L));
        AiReportRefreshResultDTO missingVersion = service.refresh(request());
        assertThat(missingVersion.getReason())
                .isEqualTo(String.valueOf(AiErrorCodeConstants.AI_DATASET_VERSION_NOT_FOUND.getCode()));

        when(datasetService.getVersionPage(anyLong(), any()))
                .thenReturn(new PageResult<>(
                        List.of(datasetVersion().setVerificationStatus(AiDatasetVersionDO.VERIFICATION_UNVERIFIED)),
                        1L));
        AiReportRefreshResultDTO unverified = service.refresh(request());
        assertThat(unverified.getReason())
                .isEqualTo(String.valueOf(AiErrorCodeConstants.AI_DATASET_VERSION_NOT_VERIFIED.getCode()));
    }

    @Test
    void versionWithoutPlanAnchorStopsRefresh() {
        when(versionMapper.selectByVersionNo(REPORT_ID, 1))
                .thenReturn(baseVersion()
                        .setSpecJson(AiReportRevisionFixture.BASE_SPEC.replace("\"datasetVersion\":1", "\"x\":1")));

        AiReportRefreshResultDTO result = service.refresh(request());

        assertThat(result.getStatus()).isEqualTo(AiReportRefreshResultDTO.STATUS_FAILED);
        assertThat(result.getReason())
                .isEqualTo(String.valueOf(AiErrorCodeConstants.AI_REPORT_VERSION_NOT_FOUND.getCode()));
        verifyNoInteractions(queryExecutionService);
    }

    @Test
    void partialResultIsRecordedAsPartial() {
        when(queryExecutionService.executeFixed(any(AiRunQueryExecutionFixedRequestDTO.class)))
                .thenReturn(AiReportRevisionFixture.queryResult()
                        .setCompleteness(AiRunQueryExecutionResultDTO.PARTIAL)
                        .setTruncated(true));

        AiReportRefreshResultDTO result = service.refresh(request());

        assertThat(result.getCompleteness()).isEqualTo("PARTIAL");
        assertThat(capturedAttempt().getCompleteness()).isEqualTo("PARTIAL");
    }

    @Test
    void refreshSpecKeepsPlansAndUpdatesResultAnchors() {
        service.refresh(request());

        ArgumentCaptor<AiReportVersionDO> inserted = ArgumentCaptor.forClass(AiReportVersionDO.class);
        verify(versionMapper).insert(inserted.capture());
        // 计划与列声明不变（同一份固定查询），只有结果锚点被刷新
        assertThat(inserted.getValue().getSpecJson())
                .contains("plan_bbbbbbbbbbbb")
                .contains("dset_sales_demo")
                .contains("\"rowCount\":2");
    }

    @Test
    void refreshKeepsSourcesAndScopeFingerprintOfBaseVersion() {
        service.refresh(request());

        AiReportRefreshDO attempt = capturedAttempt();
        assertThat(attempt.getSourcesJson()).isEqualTo(AiReportRevisionFixture.BASE_SOURCES);
        assertThat(attempt.getScopeFingerprint()).hasSize(64);
        assertThat(attempt.getBaseVersionNo()).isEqualTo(1);
        assertThat(attempt.getAsOf()).isNotNull();
    }
}
