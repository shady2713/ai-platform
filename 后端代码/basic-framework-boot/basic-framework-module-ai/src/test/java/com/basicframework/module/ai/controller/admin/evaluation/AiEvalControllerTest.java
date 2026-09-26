package com.basicframework.module.ai.controller.admin.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalCaseRespVO;
import com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalResultRespVO;
import com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalReviewReqVO;
import com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalRunRespVO;
import com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalSuiteRespVO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalCaseDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalResultDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalRunDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalSuiteDO;
import com.basicframework.module.ai.service.evaluation.AiEvalRunService;
import com.basicframework.module.ai.service.evaluation.AiEvalSuiteService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * Q04 评测控制面契约：权限点与 V83 种子一致（查看/维护/执行/复核分开）、响应不含凭据与正文、动作端点是显式命令。
 */
class AiEvalControllerTest {

    private final AiEvalSuiteService suiteService = mock(AiEvalSuiteService.class);

    private final AiEvalRunService runService = mock(AiEvalRunService.class);

    private final AiEvalController controller = new AiEvalController(suiteService, runService);

    private static String permissionOf(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = AiEvalController.class.getMethod(methodName, parameterTypes);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("%s 必须声明服务端权限表达式", methodName).isNotNull();
        return annotation
                .value()
                .replace("@ss.hasPermission(", "")
                .replace(")", "")
                .replace("'", "");
    }

    @Test
    void everyEndpointDeclaresItsOwnPermissionPoint() throws Exception {
        int reads = 0;
        int writes = 0;
        for (Method method : AiEvalController.class.getDeclaredMethods()) {
            boolean read = method.getAnnotation(GetMapping.class) != null;
            boolean write = method.getAnnotation(PostMapping.class) != null
                    || method.getAnnotation(PutMapping.class) != null
                    || method.getAnnotation(DeleteMapping.class) != null;
            if (!read && !write) {
                continue;
            }
            String permission = permissionOf(method.getName(), method.getParameterTypes());
            if (read) {
                reads++;
                assertThat(permission).as("%s 的查看权限", method.getName()).isEqualTo("ai:eval:query");
                continue;
            }
            writes++;
            assertThat(permission)
                    .as("%s 的写权限", method.getName())
                    .isIn("ai:eval:manage", "ai:eval:run", "ai:eval:review");
        }
        assertThat(reads).as("只读端点数量与 V83 菜单一致（赛件/样例/运行/结果/报告）").isEqualTo(8);
        assertThat(writes).as("写端点数量与 V83 菜单一致").isEqualTo(9);
        assertThat(permissionOf(
                        "startRun",
                        com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalRunStartReqVO.class))
                .isEqualTo("ai:eval:run");
        assertThat(permissionOf("review", AiEvalReviewReqVO.class)).isEqualTo("ai:eval:review");
    }

    @Test
    void responsesCarryNoCredentialsPromptsOrBodies() {
        List<Class<?>> protocolTypes = List.of(
                AiEvalSuiteRespVO.class, AiEvalCaseRespVO.class, AiEvalRunRespVO.class, AiEvalResultRespVO.class);
        for (Class<?> type : protocolTypes) {
            for (Field field : type.getDeclaredFields()) {
                String name = field.getName().toLowerCase();
                assertThat(name)
                        .as("%s.%s 不得携带凭据/提示词/响应正文", type.getSimpleName(), field.getName())
                        .doesNotContain("secret")
                        .doesNotContain("credential")
                        .doesNotContain("apikey")
                        .doesNotContain("prompt")
                        .doesNotContain("outputtext")
                        .doesNotContain("blockjson");
            }
        }
        assertThat(AiEvalResultRespVO.class.getDeclaredFields())
                .extracting(Field::getName)
                .contains("caseDigest", "resultDigest", "verdictJson")
                .doesNotContain("question");
        assertThat(AiEvalRunRespVO.class.getDeclaredFields())
                .extracting(Field::getName)
                .contains("suiteDigest", "summaryJson");
    }

    @Test
    void pageEndpointsPassOnlyServerSideFilters() {
        AiEvalSuiteDO suite = new AiEvalSuiteDO()
                .setApplicationId(1L)
                .setCaseCount(3)
                .setCode("order-qa")
                .setContentDigest("a".repeat(64))
                .setDataLevel(AiEvalSuiteDO.LEVEL_INTERNAL)
                .setExternalUserId("eval-runner")
                .setId(7L)
                .setName("订单问答评测")
                .setRevision(2)
                .setServiceId(4L)
                .setStatus(AiEvalSuiteDO.STATUS_FROZEN)
                .setSubjectType("USER")
                .setVersion(1);
        suite.setCreateTime(LocalDateTime.now());
        when(suiteService.pageSuites(any(), any(), any())).thenReturn(new PageResult<>(List.of(suite), 1L));

        AiEvalSuiteRespVO row = controller
                .pageSuites(new com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalSuitePageReqVO()
                        .setApplicationId(1L)
                        .setStatus(AiEvalSuiteDO.STATUS_FROZEN))
                .getData()
                .getList()
                .get(0);

        assertThat(row.getContentDigest()).hasSize(64);
        assertThat(row.getStatus()).isEqualTo(AiEvalSuiteDO.STATUS_FROZEN);
        assertThat(row.getCaseCount()).isEqualTo(3);
        verify(suiteService).pageSuites(any(), any(), any());
        verify(suiteService, never()).listCases(anyLong());
    }

    @Test
    void actionEndpointsDelegateToCommandsWithVersionAndReviewerInput() {
        controller.freezeSuite(
                new com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalVersionActionReqVO()
                        .setId(7L)
                        .setVersion(1));
        verify(suiteService).freezeSuite(7L, 1);

        controller.newRevision(
                new com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalVersionActionReqVO()
                        .setId(7L)
                        .setVersion(2));
        verify(suiteService).newRevision(7L, 2);

        controller.startRun(
                new com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalRunStartReqVO().setSuiteId(7L));
        verify(runService).startRun(7L);

        controller.review(
                new AiEvalReviewReqVO().setResultId(9L).setApprove(false).setNote("金额口径不对"));
        verify(runService).review(9L, false, "金额口径不对");

        verify(suiteService, never()).createSuite(any());
        verify(runService, never()).report(anyLong());
    }

    @Test
    void reportAndResultsAreServedFromFrozenFacts() {
        when(runService.report(3L)).thenReturn("{\"run\":{\"runId\":3}}");
        when(runService.listResults(3L))
                .thenReturn(List.of(new AiEvalResultDO()
                        .setId(9L)
                        .setRunId(3L)
                        .setCaseKey("case_a")
                        .setSeverity(AiEvalCaseDO.SEVERITY_BLOCKER)
                        .setStatus(AiEvalResultDO.STATUS_REVIEW_REQUIRED)
                        .setCaseDigest("b".repeat(64))
                        .setResultDigest("c".repeat(64))
                        .setReviewStatus(AiEvalResultDO.REVIEW_PENDING)
                        .setVerdictJson("[{\"kind\":\"MONEY\",\"passed\":true}]")));

        assertThat(controller.report(3L).getData()).contains("\"runId\":3");
        AiEvalResultRespVO result = controller.listResults(3L).getData().get(0);
        assertThat(result.getReviewStatus()).isEqualTo(AiEvalResultDO.REVIEW_PENDING);
        assertThat(result.getCaseDigest()).hasSize(64);
        assertThat(result.getStatus()).isEqualTo(AiEvalResultDO.STATUS_REVIEW_REQUIRED);
        verify(runService, never()).review(anyLong(), any(Boolean.class), anyString());
    }

    @Test
    void everyEndpointDelegatesWithRealInputs() {
        when(suiteService.createSuite(any())).thenReturn(21L);
        when(suiteService.requireSuite(7L)).thenReturn(suite());
        when(suiteService.createCase(any())).thenReturn(31L);
        when(suiteService.listCases(7L)).thenReturn(List.of(evalCase()));
        when(runService.requireRun(3L)).thenReturn(run());
        when(runService.pageRuns(any(), any())).thenReturn(new PageResult<>(List.of(run()), 1L));
        when(runService.pageResults(any(), any(), any())).thenReturn(new PageResult<>(List.of(result()), 1L));

        assertThat(controller
                        .createSuite(
                                new com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalSuiteSaveReqVO()
                                        .setApplicationId(1L)
                                        .setCode("order-qa-eval")
                                        .setName("订单问答评测")
                                        .setServiceId(4L)
                                        .setSubjectType("USER"))
                        .getData())
                .isEqualTo(21L);
        assertThat(controller
                        .updateSuite(
                                new com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalSuiteUpdateReqVO()
                                        .setId(7L)
                                        .setVersion(1)
                                        .setName("订单问答评测（改名）"))
                        .getData())
                .isTrue();
        assertThat(controller.getSuite(7L).getData().getCode()).isEqualTo("order-qa");

        assertThat(controller
                        .createCase(
                                new com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalCaseSaveReqVO()
                                        .setSuiteId(7L)
                                        .setCaseKey("case_amount")
                                        .setTitle("金额核对")
                                        .setQuestion("合成问题")
                                        .setChecksJson(
                                                "[{\"kind\":\"VALUE\",\"path\":\"status\",\"expected\":\"OK\"}]"))
                        .getData())
                .isEqualTo(31L);
        assertThat(controller
                        .updateCase(
                                new com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalCaseSaveReqVO()
                                        .setId(11L)
                                        .setVersion(0)
                                        .setSuiteId(7L)
                                        .setCaseKey("case_amount")
                                        .setTitle("金额核对（改）")
                                        .setQuestion("合成问题")
                                        .setChecksJson(
                                                "[{\"kind\":\"VALUE\",\"path\":\"status\",\"expected\":\"OK\"}]"))
                        .getData())
                .isTrue();
        assertThat(controller
                        .deleteCase(
                                new com.basicframework.module.ai.controller.admin.evaluation.vo
                                                .AiEvalVersionActionReqVO()
                                        .setId(11L)
                                        .setVersion(1))
                        .getData())
                .isTrue();
        assertThat(controller.listCases(7L).getData().get(0).getCaseKey()).isEqualTo("case_amount");

        assertThat(controller.getRun(3L).getData().getSuiteDigest()).hasSize(64);
        assertThat(controller
                        .pageRuns(new com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalRunPageReqVO()
                                .setSuiteId(7L))
                        .getData()
                        .getTotal())
                .isEqualTo(1L);
        assertThat(controller
                        .pageResults(
                                new com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalResultPageReqVO()
                                        .setRunId(3L)
                                        .setStatus(AiEvalResultDO.STATUS_ERROR))
                        .getData()
                        .getList()
                        .get(0)
                        .getFailureCode())
                .isEqualTo("1003009001");

        verify(suiteService).deleteCase(11L, 1);
        verify(suiteService).updateCase(any());
        verify(runService).pageResults(any(), any(), any());
    }

    private static AiEvalSuiteDO suite() {
        return new AiEvalSuiteDO()
                .setId(7L)
                .setApplicationId(1L)
                .setCode("order-qa")
                .setName("订单问答评测")
                .setServiceId(4L)
                .setSubjectType("USER")
                .setDataLevel(AiEvalSuiteDO.LEVEL_INTERNAL)
                .setStatus(AiEvalSuiteDO.STATUS_DRAFT)
                .setRevision(1)
                .setCaseCount(1)
                .setVersion(0);
    }

    private static AiEvalCaseDO evalCase() {
        return new AiEvalCaseDO()
                .setId(11L)
                .setSuiteId(7L)
                .setCaseKey("case_amount")
                .setTitle("金额核对")
                .setSeverity(AiEvalCaseDO.SEVERITY_BLOCKER)
                .setQuestion("合成问题")
                .setChecksJson("[{\"kind\":\"VALUE\",\"path\":\"status\",\"expected\":\"OK\"}]")
                .setNeedsReview(false)
                .setVersion(0);
    }

    private static AiEvalRunDO run() {
        AiEvalRunDO entity = new AiEvalRunDO()
                .setApplicationId(1L)
                .setCaseTotal(2)
                .setErrorCount(1)
                .setFailedCount(0)
                .setId(3L)
                .setPassedCount(1)
                .setServiceId(4L)
                .setStatus(AiEvalRunDO.STATUS_COMPLETED)
                .setSuiteDigest("a".repeat(64))
                .setSuiteId(7L)
                .setSuiteRevision(2)
                .setSummaryJson("{\"cases\":[]}");
        entity.setStartedTime(LocalDateTime.now().minusMinutes(1));
        entity.setFinishedTime(LocalDateTime.now());
        return entity;
    }

    private static AiEvalResultDO result() {
        return new AiEvalResultDO()
                .setCaseDigest("b".repeat(64))
                .setCaseKey("case_amount")
                .setFailureCode("1003009001")
                .setId(9L)
                .setResultDigest("c".repeat(64))
                .setReviewStatus(AiEvalResultDO.REVIEW_NOT_REQUIRED)
                .setRunId(3L)
                .setSeverity(AiEvalCaseDO.SEVERITY_BLOCKER)
                .setStatus(AiEvalResultDO.STATUS_ERROR);
    }
}
