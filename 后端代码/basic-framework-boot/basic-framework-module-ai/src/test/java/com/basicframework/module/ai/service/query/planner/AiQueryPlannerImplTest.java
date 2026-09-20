package com.basicframework.module.ai.service.query.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetDO;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetVersionDO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.dataset.AiDatasetService;
import com.basicframework.module.ai.service.query.planner.dto.AiQueryPlanRequestDTO;
import com.basicframework.module.ai.service.query.planner.dto.AiQueryPlanResultDTO;
import com.basicframework.module.ai.testfixture.AiQueryPlanFixture;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** D05 规划器：授权范围、PLAN/CLARIFICATION 分流、有限修复、SQL 拒绝与版本可执行性。 */
class AiQueryPlannerImplTest {

    private final AiDatasetService datasetService = mock(AiDatasetService.class);

    private final AiQueryPlanModel model = mock(AiQueryPlanModel.class);

    private final AiQueryPlannerImpl planner =
            new AiQueryPlannerImpl(datasetService, model, new AiDatasetSummaryBuilder(), AiQueryPlanFixture.CLOCK);

    private static AiDatasetDO dataset() {
        return new AiDatasetDO()
                .setId(AiQueryPlanFixture.DATASET_ID)
                .setCode("it-query-orders")
                .setName("IT 订单数据集")
                .setConnectorId(71L)
                .setSourceObject("it_query.orders")
                .setStatus(AiDatasetDO.STATUS_ENABLED)
                .setLatestVersionNo(1)
                .setPublishedVersionNo(1)
                .setVersion(2);
    }

    private static AiDatasetVersionDO version(String status, String verificationStatus) {
        return new AiDatasetVersionDO()
                .setId(AiQueryPlanFixture.DATASET_VERSION_ID)
                .setDatasetId(AiQueryPlanFixture.DATASET_ID)
                .setVersionNo(1)
                .setStatus(status)
                .setDefinitionJson(AiQueryPlanFixture.DEFINITION)
                .setSchemaHash("a".repeat(64))
                .setVerificationStatus(verificationStatus)
                .setVersion(2);
    }

    private static String planEnvelope(String planJson) {
        return "{\"kind\":\"PLAN\",\"plan\":" + planJson + "}";
    }

    private static AiQueryPlanRequestDTO request() {
        return new AiQueryPlanRequestDTO()
                .setDatasetId(AiQueryPlanFixture.DATASET_ID)
                .setEndpointId(51L)
                .setQuestion("上个月华东的销售额是多少");
    }

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @BeforeEach
    void setUp() {
        when(datasetService.getDataset(AiQueryPlanFixture.DATASET_ID)).thenReturn(dataset());
        when(datasetService.getVersion(AiQueryPlanFixture.DATASET_VERSION_ID))
                .thenReturn(version(AiDatasetVersionDO.STATUS_PUBLISHED, AiDatasetVersionDO.VERIFICATION_VERIFIED));
        when(datasetService.getVersionPage(eq(AiQueryPlanFixture.DATASET_ID), any(PageParam.class)))
                .thenReturn(new PageResult<>(
                        List.of(version(AiDatasetVersionDO.STATUS_PUBLISHED, AiDatasetVersionDO.VERIFICATION_VERIFIED)),
                        1L));
    }

    @Test
    void returnsValidatedPlanWithStableHash() {
        when(model.propose(any(), anyString(), anyString())).thenReturn(planEnvelope(AiQueryPlanFixture.VALID_PLAN));

        AiQueryPlanResultDTO result = planner.plan(request());

        assertThat(result.getKind()).isEqualTo("PLAN");
        assertThat(result.getAttempts()).isEqualTo(1);
        assertThat(result.getPlanDatasetId()).isEqualTo("dset_it-query-orders");
        assertThat(result.getDatasetVersionNo()).isEqualTo(1);
        assertThat(result.getSchemaHash()).isEqualTo("a".repeat(64));
        assertThat(result.getPlanHash()).hasSize(64);
        assertThat(result.getPlanJson())
                .contains("\"code\":\"net_amount\"")
                .contains("\"direction\":\"DESC\"")
                .contains("\"timezone\":\"Asia/Shanghai\"");
        assertThat(planner.plan(request()).getPlanHash()).as("同问题同计划哈希").isEqualTo(result.getPlanHash());
        verify(model, times(2)).propose(eq(51L), anyString(), anyString());
    }

    @Test
    void returnsClarificationAndKeepsOnlyCatalogCandidates() {
        when(model.propose(any(), anyString(), anyString()))
                .thenReturn("{\"kind\":\"CLARIFICATION\",\"clarification\":{\"question\":\"你指的是哪个口径？\","
                        + "\"candidates\":[{\"code\":\"net_amount\",\"label\":\"净额\"},"
                        + "{\"code\":\"invented_code\",\"label\":\"模型编的\"}]}}");

        AiQueryPlanResultDTO result = planner.plan(request());

        assertThat(result.getKind()).isEqualTo("CLARIFICATION");
        assertThat(result.getReason()).isEqualTo("AMBIGUOUS");
        assertThat(result.getQuestion()).isEqualTo("你指的是哪个口径？");
        assertThat(result.getCandidates())
                .as("候选只能来自授权目录")
                .extracting(AiQueryPlanResultDTO.Candidate::code)
                .containsExactly("net_amount");
        assertThat(result.getPlanJson()).isNull();
        assertThat(result.getAttempts()).isEqualTo(1);
    }

    @Test
    void repairsWithinOriginalScopeAndTellsTheModelWhatFailed() {
        // 第一次不合规、第二次合规：attempts=2，且第二次提示词带修复说明
        when(model.propose(any(), anyString(), anyString()))
                .thenReturn(planEnvelope(AiQueryPlanFixture.VALID_PLAN.replace("\"limit\": 10", "\"limit\": 5000")))
                .thenReturn(planEnvelope(AiQueryPlanFixture.VALID_PLAN));

        AiQueryPlanResultDTO repaired = planner.plan(request());
        assertThat(repaired.getKind()).isEqualTo("PLAN");
        assertThat(repaired.getAttempts()).isEqualTo(2);

        org.mockito.ArgumentCaptor<String> prompts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(model, times(2)).propose(any(), prompts.capture(), anyString());
        assertThat(prompts.getAllValues().get(1)).contains("上一次输出未通过平台校验");
        assertThat(prompts.getAllValues().get(1)).as("修复提示不得扩大数据集范围").contains("不得扩大数据集或字段范围");
    }

    @Test
    void stopsAfterRepairLimitIsExhausted() {
        // 连续不合规：默认上限 2 → 最多 3 次模型输出，然后失败结束
        when(model.propose(any(), anyString(), anyString()))
                .thenReturn(planEnvelope(AiQueryPlanFixture.VALID_PLAN.replace("\"limit\": 10", "\"limit\": 5000")));

        assertThatThrownBy(() -> planner.plan(request()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_REPAIR_EXHAUSTED));
        verify(model, times(3)).propose(any(), anyString(), anyString());

        // 显式关闭修复：只调用一次模型
        when(model.propose(any(), anyString(), anyString()))
                .thenReturn(planEnvelope(AiQueryPlanFixture.VALID_PLAN.replace("\"limit\": 10", "\"limit\": 5000")));
        assertThatThrownBy(() -> planner.plan(request().setMaxRepairs(0)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_REPAIR_EXHAUSTED));
    }

    @Test
    void rejectsSqlWithoutRepairAttempt() {
        when(model.propose(any(), anyString(), anyString()))
                .thenReturn(planEnvelope(AiQueryPlanFixture.VALID_PLAN.replace("\"net_amount\"", "\"SUM(amount)\"")));

        assertThatThrownBy(() -> planner.plan(request()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_SQL_REJECTED));
        verify(model, times(1)).propose(any(), anyString(), anyString());
    }

    @Test
    void turnsAliasAmbiguityIntoClarificationWithoutRepair() {
        when(model.propose(any(), anyString(), anyString()))
                .thenReturn(planEnvelope(AiQueryPlanFixture.VALID_PLAN.replace(
                        "\"metrics\": [\"net_amount\"]", "\"metrics\": [\"销售额\"]")));

        AiQueryPlanResultDTO result = planner.plan(request());

        assertThat(result.getKind()).isEqualTo("CLARIFICATION");
        assertThat(result.getReason()).isEqualTo("AMBIGUOUS");
        assertThat(result.getCandidates())
                .as("歧义命中别名时给出目录里的对应字段作为候选")
                .extracting(AiQueryPlanResultDTO.Candidate::code)
                .contains("amount");
        verify(model, times(1)).propose(any(), anyString(), anyString());
    }

    @Test
    void neverExpandsAllowedDatasetSet() {
        AiQueryPlanRequestDTO restricted = request().setAllowedDatasetIds(List.of(999L));
        assertThatThrownBy(() -> planner.plan(restricted))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_DATASET_NOT_ALLOWED));
        verify(model, org.mockito.Mockito.never()).propose(any(), anyString(), anyString());

        // 模型提到别的数据集：拒绝且不修复
        when(model.propose(any(), anyString(), anyString()))
                .thenReturn(planEnvelope(
                        AiQueryPlanFixture.VALID_PLAN.replace("dset_it-query-orders", "dset_other-dataset")));
        assertThatThrownBy(() -> planner.plan(request().setAllowedDatasetIds(List.of(AiQueryPlanFixture.DATASET_ID))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_REPAIR_EXHAUSTED));
    }

    @Test
    void requiresEnabledDatasetAndExecutableVersion() {
        when(datasetService.getDataset(AiQueryPlanFixture.DATASET_ID))
                .thenReturn(dataset().setStatus(AiDatasetDO.STATUS_DISABLED));
        assertThatThrownBy(() -> planner.plan(request()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_DISABLED));

        when(datasetService.getDataset(AiQueryPlanFixture.DATASET_ID)).thenReturn(dataset());
        when(datasetService.getVersion(AiQueryPlanFixture.DATASET_VERSION_ID))
                .thenReturn(version(AiDatasetVersionDO.STATUS_DRAFT, AiDatasetVersionDO.VERIFICATION_UNVERIFIED));
        assertThatThrownBy(() -> planner.plan(request().setDatasetVersionId(AiQueryPlanFixture.DATASET_VERSION_ID)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_VERSION_NOT_PUBLISHED));

        when(datasetService.getVersion(AiQueryPlanFixture.DATASET_VERSION_ID))
                .thenReturn(version(AiDatasetVersionDO.STATUS_PUBLISHED, AiDatasetVersionDO.VERIFICATION_DRIFTED));
        assertThatThrownBy(() -> planner.plan(request().setDatasetVersionId(AiQueryPlanFixture.DATASET_VERSION_ID)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_VERSION_DRIFTED));

        when(datasetService.getVersion(AiQueryPlanFixture.DATASET_VERSION_ID))
                .thenReturn(version(AiDatasetVersionDO.STATUS_PUBLISHED, AiDatasetVersionDO.VERIFICATION_UNVERIFIED));
        assertThatThrownBy(() -> planner.plan(request().setDatasetVersionId(AiQueryPlanFixture.DATASET_VERSION_ID)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_VERSION_NOT_VERIFIED));

        // 没有已发布版本：按未发布拒绝（不退回草稿）
        when(datasetService.getVersionPage(eq(AiQueryPlanFixture.DATASET_ID), any(PageParam.class)))
                .thenReturn(new PageResult<>(
                        List.of(version(AiDatasetVersionDO.STATUS_DRAFT, AiDatasetVersionDO.VERIFICATION_UNVERIFIED)),
                        1L));
        assertThatThrownBy(() -> planner.plan(request()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_VERSION_NOT_PUBLISHED));
    }

    @Test
    void rejectsMalformedModelOutputAndBadRequests() {
        when(model.propose(any(), anyString(), anyString())).thenReturn("not-json");
        assertThatThrownBy(() -> planner.plan(request()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_MODEL_OUTPUT_INVALID));

        when(model.propose(any(), anyString(), anyString())).thenReturn("{\"kind\":\"OTHER\"}");
        assertThatThrownBy(() -> planner.plan(request()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_MODEL_OUTPUT_INVALID));

        when(model.propose(any(), anyString(), anyString())).thenReturn("{\"kind\":\"PLAN\"}");
        assertThatThrownBy(() -> planner.plan(request()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_MODEL_OUTPUT_INVALID));

        for (AiQueryPlanRequestDTO invalid : List.of(
                new AiQueryPlanRequestDTO(),
                new AiQueryPlanRequestDTO().setDatasetId(1L).setEndpointId(1L),
                new AiQueryPlanRequestDTO().setDatasetId(1L).setQuestion("  "),
                new AiQueryPlanRequestDTO().setEndpointId(1L).setQuestion("x"))) {
            assertThatThrownBy(() -> planner.plan(invalid))
                    .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REQUEST_INVALID));
        }
    }

    @Test
    void datasetSummaryReflectsAuthorizedFieldScope() {
        String summary = planner.datasetSummary(AiQueryPlanFixture.DATASET_ID, null, List.of("net_amount", "amount"));

        assertThat(summary).contains("\"code\":\"net_amount\"").doesNotContain("region");
        assertThat(summary).contains("\"currentTime\":\"2026-09-20T20:00:00+08:00\"");
    }
}
