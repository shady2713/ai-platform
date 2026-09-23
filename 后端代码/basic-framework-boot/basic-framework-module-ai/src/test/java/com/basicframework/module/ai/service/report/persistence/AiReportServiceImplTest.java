package com.basicframework.module.ai.service.report.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.report.AiReportDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportVersionDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.mysql.report.AiReportMapper;
import com.basicframework.module.ai.dal.mysql.report.AiReportVersionMapper;
import com.basicframework.module.ai.dal.mysql.run.AiRunMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.authorization.dto.AiAuthorizationDecisionDTO;
import com.basicframework.module.ai.service.conversation.AiConversationSubject;
import com.basicframework.module.ai.service.conversation.AiConversationSubjectResolver;
import com.basicframework.module.ai.service.report.persistence.dto.AiReportSaveDTO;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * R04 报表服务：归属、模式与快照元信息、乐观锁冲突、引用资源再鉴权、范围指纹复核。
 *
 * <p>这里的判定桩只提供"放行/拒绝 + 指纹"，A03 自身的矩阵由 A08 集成用例覆盖；
 * 本类要证明的是**服务层在拿到判定结果后做的取舍**（拒绝保存、拒绝显示、逐项复核）。
 */
class AiReportServiceImplTest {

    private static final Long REPORT_ID = 71L;

    private static final Long APPLICATION_ID = 9L;

    private static final String SUBJECT = "alice";

    private static final String FINGERPRINT = "f".repeat(64);

    private final AiReportMapper reportMapper = mock(AiReportMapper.class);

    private final AiReportVersionMapper versionMapper = mock(AiReportVersionMapper.class);

    private final AiRunMapper runMapper = mock(AiRunMapper.class);

    private final AiConversationSubjectResolver subjectResolver = mock(AiConversationSubjectResolver.class);

    private final AiAuthorizationService authorizationService = mock(AiAuthorizationService.class);

    private final AiReportServiceImpl service =
            new AiReportServiceImpl(reportMapper, versionMapper, runMapper, subjectResolver, authorizationService);

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    private static AiConversationSubject subject() {
        return new AiConversationSubject(APPLICATION_ID, AiSubjectType.USER, SUBJECT);
    }

    private static AiReportSaveDTO saveDTO(String mode) {
        return new AiReportSaveDTO()
                .setCode("sales_overview")
                .setName("销售总览")
                .setMode(mode)
                .setSpecJson("{\"schemaVersion\":\"1.0\"}")
                .setSourcesJson("[{\"resourceType\":\"KNOWLEDGE_BASE\",\"resourceKey\":\"kb-1\"}]");
    }

    private static AiReportDO report() {
        return new AiReportDO()
                .setId(REPORT_ID)
                .setCode("sales_overview")
                .setName("销售总览")
                .setApplicationId(APPLICATION_ID)
                .setSubjectType("USER")
                .setExternalUserId(SUBJECT)
                .setMode(AiReportDO.MODE_REFRESHABLE)
                .setLatestVersionNo(1)
                .setPublishedVersionNo(1)
                .setVersion(3);
    }

    private void login() {
        when(subjectResolver.resolveCurrent()).thenReturn(Optional.of(subject()));
    }

    private void allowDependency() {
        when(authorizationService.authorize(
                        eq(APPLICATION_ID),
                        eq("USER"),
                        eq(SUBJECT),
                        eq(AiResourceType.KNOWLEDGE_BASE),
                        eq("kb-1"),
                        eq(AiAction.READ),
                        anyList()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(true).setScopeFingerprint(FINGERPRINT));
    }

    private AiReportVersionDO storedVersion(String scopeRefsJson, String fingerprint) {
        return new AiReportVersionDO()
                .setId(11L)
                .setReportId(REPORT_ID)
                .setVersionNo(1)
                .setMode(AiReportDO.MODE_REFRESHABLE)
                .setSpecJson("{\"schemaVersion\":\"1.0\"}")
                .setScopeRefsJson(scopeRefsJson)
                .setScopeFingerprint(fingerprint);
    }

    @Test
    void createTakesOwnershipFromSessionAndRecordsScopeRefs() {
        login();
        allowDependency();
        when(reportMapper.selectByCode(APPLICATION_ID, "sales_overview")).thenReturn(null);
        doAnswer(invocation -> {
                    invocation.getArgument(0, AiReportDO.class).setId(REPORT_ID);
                    return 1;
                })
                .when(reportMapper)
                .insert(any(AiReportDO.class));

        assertThat(service.create(saveDTO(AiReportDO.MODE_REFRESHABLE))).isEqualTo(REPORT_ID);

        ArgumentCaptor<AiReportDO> insertedReport = ArgumentCaptor.forClass(AiReportDO.class);
        verify(reportMapper).insert(insertedReport.capture());
        assertThat(insertedReport.getValue().getApplicationId()).isEqualTo(APPLICATION_ID);
        assertThat(insertedReport.getValue().getSubjectType()).isEqualTo("USER");
        assertThat(insertedReport.getValue().getExternalUserId()).isEqualTo(SUBJECT);
        assertThat(insertedReport.getValue().getLatestVersionNo()).isEqualTo(1);

        ArgumentCaptor<AiReportVersionDO> insertedVersion = ArgumentCaptor.forClass(AiReportVersionDO.class);
        verify(versionMapper).insert(insertedVersion.capture());
        assertThat(insertedVersion.getValue().getVersionNo()).isEqualTo(1);
        assertThat(insertedVersion.getValue().getScopeRefsJson())
                .contains("KNOWLEDGE_BASE")
                .contains("kb-1")
                .contains(FINGERPRINT);
        assertThat(insertedVersion.getValue().getScopeFingerprint()).hasSize(64);
        // 可刷新模式不写数据与截至时间
        assertThat(insertedVersion.getValue().getDataJson()).isNull();
        assertThat(insertedVersion.getValue().getAsOf()).isNull();
    }

    @Test
    void createRejectsDuplicateCodeInvalidModeAndSnapshotWithoutData() {
        login();
        when(reportMapper.selectByCode(APPLICATION_ID, "sales_overview")).thenReturn(report());
        assertCode(
                assertThatThrownBy(() -> service.create(saveDTO(AiReportDO.MODE_REFRESHABLE)))
                        .actual(),
                AiErrorCodeConstants.AI_REPORT_CODE_DUPLICATE);

        AiReportSaveDTO invalidMode = saveDTO("STREAMING");
        assertCode(
                assertThatThrownBy(() -> service.create(invalidMode)).actual(),
                AiErrorCodeConstants.AI_REQUEST_INVALID);

        AiReportSaveDTO invalidCode = saveDTO(AiReportDO.MODE_REFRESHABLE).setCode("Bad Code");
        assertCode(
                assertThatThrownBy(() -> service.create(invalidCode)).actual(),
                AiErrorCodeConstants.AI_REQUEST_INVALID);

        // 快照模式必须带数据（否则"快照"无法按保存时的样子展示）
        when(reportMapper.selectByCode(APPLICATION_ID, "sales_overview")).thenReturn(null);
        allowDependency();
        doAnswer(invocation -> {
                    invocation.getArgument(0, AiReportDO.class).setId(REPORT_ID);
                    return 1;
                })
                .when(reportMapper)
                .insert(any(AiReportDO.class));
        AiReportSaveDTO snapshot = saveDTO(AiReportDO.MODE_SNAPSHOT);
        assertCode(
                assertThatThrownBy(() -> service.create(snapshot)).actual(),
                AiErrorCodeConstants.AI_REPORT_SNAPSHOT_DATA_REQUIRED);
    }

    @Test
    void createRecordsSnapshotMetadataAndRejectsDeniedOrUnknownDependency() {
        login();
        allowDependency();
        when(reportMapper.selectByCode(APPLICATION_ID, "sales_overview")).thenReturn(null);
        doAnswer(invocation -> {
                    invocation.getArgument(0, AiReportDO.class).setId(REPORT_ID);
                    return 1;
                })
                .when(reportMapper)
                .insert(any(AiReportDO.class));
        AiReportSaveDTO snapshot = saveDTO(AiReportDO.MODE_SNAPSHOT)
                .setDataJson("{\"blocks\":[]}")
                .setCompleteness("COMPLETE")
                .setCreatedByRun(null);

        service.create(snapshot);

        ArgumentCaptor<AiReportVersionDO> insertedVersion = ArgumentCaptor.forClass(AiReportVersionDO.class);
        verify(versionMapper).insert(insertedVersion.capture());
        assertThat(insertedVersion.getValue().getDataJson()).isEqualTo("{\"blocks\":[]}");
        assertThat(insertedVersion.getValue().getAsOf()).isNotNull();
        assertThat(insertedVersion.getValue().getCompleteness()).isEqualTo("COMPLETE");

        // 依赖被拒绝：不落库
        when(authorizationService.authorize(any(), any(), any(), any(), any(), any(), anyList()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(false).setDenyReason("GRANT_NOT_FOUND"));
        assertCode(
                assertThatThrownBy(() -> service.create(saveDTO(AiReportDO.MODE_REFRESHABLE)))
                        .actual(),
                AiErrorCodeConstants.AI_ACCESS_DENIED);

        // 词表外的资源类型：直接拒绝，不静默跳过
        AiReportSaveDTO unknownType = saveDTO(AiReportDO.MODE_REFRESHABLE)
                .setSourcesJson("[{\"resourceType\":\"TABLE\",\"resourceKey\":\"t\"}]");
        assertCode(
                assertThatThrownBy(() -> service.create(unknownType)).actual(),
                AiErrorCodeConstants.AI_REQUEST_INVALID);
    }

    @Test
    void saveRejectsOtherUsersRunAndAcceptsOwnRun() {
        login();
        allowDependency();
        AiRunDO otherUsersRun = new AiRunDO()
                .setRunKey("run_bob")
                .setApplicationId(APPLICATION_ID)
                .setSubjectType("USER")
                .setExternalUserId("bob");
        when(runMapper.selectOne(any())).thenReturn(otherUsersRun);
        assertCode(
                assertThatThrownBy(() -> service.create(
                                saveDTO(AiReportDO.MODE_REFRESHABLE).setCreatedByRun("run_bob")))
                        .actual(),
                AiErrorCodeConstants.AI_REPORT_SOURCE_RUN_NOT_FOUND);

        AiRunDO ownRun = new AiRunDO()
                .setRunKey("run_alice")
                .setApplicationId(APPLICATION_ID)
                .setSubjectType("USER")
                .setExternalUserId(SUBJECT);
        when(runMapper.selectOne(any())).thenReturn(ownRun);
        when(reportMapper.selectByCode(APPLICATION_ID, "sales_overview")).thenReturn(null);
        doAnswer(invocation -> {
                    invocation.getArgument(0, AiReportDO.class).setId(REPORT_ID);
                    return 1;
                })
                .when(reportMapper)
                .insert(any(AiReportDO.class));

        assertThat(service.create(saveDTO(AiReportDO.MODE_REFRESHABLE).setCreatedByRun("run_alice")))
                .isEqualTo(REPORT_ID);
    }

    @Test
    void saveVersionAppendsVersionAndRejectsStaleOptimisticLock() {
        login();
        allowDependency();
        when(reportMapper.selectById(REPORT_ID)).thenReturn(report());
        when(reportMapper.updateWithVersion(any(AiReportDO.class), eq(3))).thenReturn(0);

        AiReportSaveDTO stale =
                saveDTO(AiReportDO.MODE_REFRESHABLE).setId(REPORT_ID).setVersion(2);
        assertCode(
                assertThatThrownBy(() -> service.saveVersion(stale)).actual(), AiErrorCodeConstants.AI_STATE_CONFLICT);
        verify(versionMapper, never()).insert(any(AiReportVersionDO.class));

        when(reportMapper.updateWithVersion(any(AiReportDO.class), eq(3))).thenReturn(1);
        AiReportSaveDTO fresh =
                saveDTO(AiReportDO.MODE_REFRESHABLE).setId(REPORT_ID).setVersion(3);
        assertThat(service.saveVersion(fresh)).isEqualTo(REPORT_ID);

        ArgumentCaptor<AiReportDO> updated = ArgumentCaptor.forClass(AiReportDO.class);
        verify(reportMapper).updateWithVersion(updated.capture(), eq(3));
        assertThat(updated.getValue().getLatestVersionNo()).isEqualTo(2);
        assertThat(updated.getValue().getPublishedVersionNo()).isEqualTo(2);
        assertThat(updated.getValue().getVersion()).isEqualTo(4);

        ArgumentCaptor<AiReportVersionDO> inserted = ArgumentCaptor.forClass(AiReportVersionDO.class);
        verify(versionMapper).insert(inserted.capture());
        // 新版本号递增，历史版本不受影响（不可变）
        assertThat(inserted.getValue().getVersionNo()).isEqualTo(2);
    }

    @Test
    void saveVersionRejectsModeChangeAndMissingVersion() {
        login();
        when(reportMapper.selectById(REPORT_ID)).thenReturn(report());
        assertCode(
                assertThatThrownBy(() -> service.saveVersion(
                                saveDTO(AiReportDO.MODE_REFRESHABLE).setId(REPORT_ID)))
                        .actual(),
                AiErrorCodeConstants.AI_REQUEST_INVALID);
        assertCode(
                assertThatThrownBy(() -> service.saveVersion(saveDTO(AiReportDO.MODE_SNAPSHOT)
                                .setId(REPORT_ID)
                                .setVersion(3)))
                        .actual(),
                AiErrorCodeConstants.AI_REQUEST_INVALID);
    }

    @Test
    void readRequiresOwnershipAndCurrentScopeCoveringOriginal() {
        login();
        // 他人报表：与不存在同语义
        AiReportDO otherUsers = report().setExternalUserId("bob");
        when(reportMapper.selectById(REPORT_ID)).thenReturn(otherUsers);
        assertCode(
                assertThatThrownBy(() -> service.readCurrent(REPORT_ID)).actual(),
                AiErrorCodeConstants.AI_REPORT_NOT_FOUND);

        // 本人报表 + 范围仍覆盖：放行
        when(reportMapper.selectById(REPORT_ID)).thenReturn(report());
        String refsJson =
                AiReportScopeRefs.toJson(List.of(new AiReportScopeRef("KNOWLEDGE_BASE", "kb-1", FINGERPRINT)));
        AiReportVersionDO version = storedVersion(
                refsJson,
                AiReportScopeRefs.fingerprint(List.of(new AiReportScopeRef("KNOWLEDGE_BASE", "kb-1", FINGERPRINT))));
        when(versionMapper.selectByVersionNo(REPORT_ID, 1)).thenReturn(version);
        when(authorizationService.reauthorizeHistorical(
                        eq(APPLICATION_ID),
                        eq("USER"),
                        eq(SUBJECT),
                        eq(AiResourceType.KNOWLEDGE_BASE),
                        eq("kb-1"),
                        eq(AiAction.READ),
                        eq(FINGERPRINT)))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(true).setScopeFingerprint(FINGERPRINT));
        assertThat(service.readCurrent(REPORT_ID)).isSameAs(version);
    }

    @Test
    void readRefusesWhenScopeFingerprintChangedOrStorageInconsistent() {
        login();
        when(reportMapper.selectById(REPORT_ID)).thenReturn(report());
        List<AiReportScopeRef> refs = List.of(new AiReportScopeRef("KNOWLEDGE_BASE", "kb-1", FINGERPRINT));
        String refsJson = AiReportScopeRefs.toJson(refs);
        String aggregate = AiReportScopeRefs.fingerprint(refs);
        when(versionMapper.selectByVersionNo(REPORT_ID, 1)).thenReturn(storedVersion(refsJson, aggregate));

        // 判定拒绝（授权被撤销）：拒绝显示
        when(authorizationService.reauthorizeHistorical(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(false).setDenyReason("GRANT_NOT_FOUND"));
        assertCode(
                assertThatThrownBy(() -> service.readCurrent(REPORT_ID)).actual(),
                AiErrorCodeConstants.AI_REPORT_SCOPE_CHANGED);

        // 放行但指纹不同（范围收窄）：同样拒绝
        when(authorizationService.reauthorizeHistorical(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(true).setScopeFingerprint("0".repeat(64)));
        assertCode(
                assertThatThrownBy(() -> service.readCurrent(REPORT_ID)).actual(),
                AiErrorCodeConstants.AI_REPORT_SCOPE_CHANGED);

        // 存储的依赖集合与整体指纹对不上：拒绝显示，且不再发起判定
        when(versionMapper.selectByVersionNo(REPORT_ID, 1)).thenReturn(storedVersion(refsJson, "1".repeat(64)));
        assertCode(
                assertThatThrownBy(() -> service.readCurrent(REPORT_ID)).actual(),
                AiErrorCodeConstants.AI_REPORT_SCOPE_CHANGED);
        verify(authorizationService, never())
                .reauthorizeHistorical(any(), any(), any(), any(), any(), any(), eq("1".repeat(64)));
    }

    @Test
    void readChecksEveryVersionEntryNotOnlyCurrent() {
        login();
        when(reportMapper.selectById(REPORT_ID)).thenReturn(report());
        List<AiReportScopeRef> refs = List.of(new AiReportScopeRef("KNOWLEDGE_BASE", "kb-1", FINGERPRINT));
        when(versionMapper.selectByVersionNo(REPORT_ID, 2))
                .thenReturn(storedVersion(AiReportScopeRefs.toJson(refs), AiReportScopeRefs.fingerprint(refs)));
        when(authorizationService.reauthorizeHistorical(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiAuthorizationDecisionDTO().setAllowed(false));

        // 旧版本编号也是读取入口：复制编号绕不过范围复核
        assertCode(
                assertThatThrownBy(() -> service.getVersion(REPORT_ID, 2)).actual(),
                AiErrorCodeConstants.AI_REPORT_SCOPE_CHANGED);
    }

    @Test
    void listVersionsAndPageFilterByOwnershipAndMode() {
        login();
        when(reportMapper.selectById(REPORT_ID)).thenReturn(report());
        when(versionMapper.selectByReport(REPORT_ID)).thenReturn(List.of(storedVersion("[]", "x")));
        assertThat(service.listVersions(REPORT_ID)).hasSize(1);

        assertCode(
                assertThatThrownBy(() -> service.getReportPage(null, "STREAMING"))
                        .actual(),
                AiErrorCodeConstants.AI_REQUEST_INVALID);
        PageResult<AiReportDO> page = new PageResult<>(List.of(report()), 1L);
        when(reportMapper.selectPage(any(PageParam.class), eq(APPLICATION_ID), eq("USER"), eq(SUBJECT), eq(null)))
                .thenReturn(page);
        assertThat(service.getReportPage(new PageParam(), null)).isSameAs(page);
    }

    @Test
    void operationsWithoutSessionAreDenied() {
        when(subjectResolver.resolveCurrent()).thenReturn(Optional.empty());
        assertCode(
                assertThatThrownBy(() -> service.create(saveDTO(AiReportDO.MODE_REFRESHABLE)))
                        .actual(),
                AiErrorCodeConstants.AI_ACCESS_DENIED);
        assertCode(
                assertThatThrownBy(() -> service.readCurrent(REPORT_ID)).actual(),
                AiErrorCodeConstants.AI_ACCESS_DENIED);
    }
}
