package com.basicframework.module.ai.service.evaluation;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_CASE_KEY_DUPLICATE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_CHECK_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_DATA_LEVEL_NOT_ALLOWED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_SUITE_CODE_DUPLICATE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_SUITE_FROZEN;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_SUITE_HAS_NO_CASE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_SUITE_NOT_FROZEN;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalCaseDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalSuiteDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceDO;
import com.basicframework.module.ai.dal.mysql.evaluation.AiEvalCaseMapper;
import com.basicframework.module.ai.dal.mysql.evaluation.AiEvalSuiteMapper;
import com.basicframework.module.ai.dal.mysql.serviceconfig.AiServiceMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.evaluation.dto.AiEvalCaseSaveDTO;
import com.basicframework.module.ai.service.evaluation.dto.AiEvalSuiteSaveDTO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 评测套件状态机（Q04）：草稿可编辑、冻结后必须新修订、规则不合规不允许入库或冻结。 */
class AiEvalSuiteServiceImplTest {

    private static final String VALID_CHECKS = "[{\"kind\":\"MONEY\",\"path\":\"amount\",\"expected\":\"450.00\"}]";

    private final AiEvalSuiteMapper suiteMapper = mock(AiEvalSuiteMapper.class);

    private final AiEvalCaseMapper caseMapper = mock(AiEvalCaseMapper.class);

    private final AiServiceMapper serviceMapper = mock(AiServiceMapper.class);

    private final AiEvalSuiteServiceImpl service = new AiEvalSuiteServiceImpl(suiteMapper, caseMapper, serviceMapper);

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    private static AiEvalSuiteDO suite(String status, int revision, int version) {
        return new AiEvalSuiteDO()
                .setId(7L)
                .setApplicationId(1L)
                .setCode("order-qa")
                .setName("订单问答评测")
                .setServiceId(4L)
                .setSubjectType("USER")
                .setExternalUserId("eval-runner")
                .setDataLevel(AiEvalSuiteDO.LEVEL_INTERNAL)
                .setStatus(status)
                .setRevision(revision)
                .setCaseCount(1)
                .setVersion(version);
    }

    private static AiEvalCaseDO evalCase() {
        return new AiEvalCaseDO()
                .setId(11L)
                .setSuiteId(7L)
                .setCaseKey("case_a")
                .setTitle("华东前十")
                .setSeverity(AiEvalCaseDO.SEVERITY_BLOCKER)
                .setQuestion("上个月华东前十的净额是多少？")
                .setChecksJson(VALID_CHECKS)
                .setNeedsReview(false)
                .setVersion(0);
    }

    @BeforeEach
    void setUp() {
        when(suiteMapper.selectById(7L)).thenReturn(suite(AiEvalSuiteDO.STATUS_DRAFT, 1, 0));
        when(caseMapper.selectBySuite(7L)).thenReturn(List.of(evalCase()));
        when(suiteMapper.updateWithVersion(any(), anyInt())).thenReturn(1);
        when(serviceMapper.selectById(4L))
                .thenReturn(new AiServiceDO().setId(4L).setAppId(1L));
    }

    @Test
    void createSuiteRejectsDuplicateCodeForeignServiceAndRealDataLevels() {
        AiEvalSuiteSaveDTO base = new AiEvalSuiteSaveDTO()
                .setApplicationId(1L)
                .setCode("order-qa")
                .setName("订单问答评测")
                .setServiceId(4L)
                .setSubjectType("USER");

        when(suiteMapper.selectByCode(1L, "order-qa")).thenReturn(suite(AiEvalSuiteDO.STATUS_DRAFT, 1, 0));
        assertThatThrownBy(() -> service.createSuite(base))
                .satisfies(exception -> assertCode(exception, AI_EVAL_SUITE_CODE_DUPLICATE));

        when(suiteMapper.selectByCode(1L, "order-qa")).thenReturn(null);
        assertThatThrownBy(() -> service.createSuite(new AiEvalSuiteSaveDTO()
                        .setApplicationId(1L)
                        .setCode("order-qa")
                        .setName("订单问答评测")
                        .setServiceId(4L)
                        .setSubjectType("USER")
                        .setDataLevel("L3_PERSONAL")))
                .satisfies(exception -> assertCode(exception, AI_EVAL_DATA_LEVEL_NOT_ALLOWED));

        when(serviceMapper.selectById(4L))
                .thenReturn(new AiServiceDO().setId(4L).setAppId(99L));
        assertThatThrownBy(() -> service.createSuite(base))
                .as("被评测服务必须属于同一应用")
                .satisfies(exception -> assertCode(exception, AI_REQUEST_INVALID));

        when(serviceMapper.selectById(4L))
                .thenReturn(new AiServiceDO().setId(4L).setAppId(1L));
        when(suiteMapper.insert(any(AiEvalSuiteDO.class))).thenAnswer(invocation -> {
            ((AiEvalSuiteDO) invocation.getArgument(0)).setId(21L);
            return 1;
        });
        assertThat(service.createSuite(base)).isEqualTo(21L);
    }

    @Test
    void freezeValidatesCasesAndStampsDigestWithNextRevision() {
        when(caseMapper.selectBySuite(7L)).thenReturn(List.of());
        assertThatThrownBy(() -> service.freezeSuite(7L, 0))
                .satisfies(exception -> assertCode(exception, AI_EVAL_SUITE_HAS_NO_CASE));

        AiEvalCaseDO broken = evalCase().setChecksJson("[{\"kind\":\"MAGIC\"}]");
        when(caseMapper.selectBySuite(7L)).thenReturn(List.of(broken));
        assertThatThrownBy(() -> service.freezeSuite(7L, 0))
                .as("规则不合规的套件不允许冻结成基线")
                .satisfies(exception -> assertCode(exception, AI_EVAL_CHECK_INVALID));

        when(caseMapper.selectBySuite(7L)).thenReturn(List.of(evalCase()));
        assertThatThrownBy(() -> service.freezeSuite(7L, 5))
                .as("版本不一致拒绝（拿过期版本冻结）")
                .satisfies(exception -> assertCode(exception, AI_STATE_CONFLICT));

        service.freezeSuite(7L, 0);
        ArgumentCaptor<AiEvalSuiteDO> captor = ArgumentCaptor.forClass(AiEvalSuiteDO.class);
        verify(suiteMapper).updateWithVersion(captor.capture(), anyInt());
        AiEvalSuiteDO update = captor.getValue();
        assertThat(update.getStatus()).isEqualTo(AiEvalSuiteDO.STATUS_FROZEN);
        assertThat(update.getRevision()).as("冻结即新修订").isEqualTo(2);
        assertThat(update.getCaseCount()).isEqualTo(1);
        assertThat(update.getFrozenTime()).isNotNull();
        assertThat(update.getContentDigest()).hasSize(64);
        assertThat(update.getVersion()).isEqualTo(1);
    }

    @Test
    void frozenSuiteRejectsEditsUntilNewRevision() {
        when(suiteMapper.selectById(7L)).thenReturn(suite(AiEvalSuiteDO.STATUS_FROZEN, 2, 1));

        assertThatThrownBy(() -> service.updateSuite(
                        new AiEvalSuiteSaveDTO().setId(7L).setVersion(1).setName("改个名")))
                .satisfies(exception -> assertCode(exception, AI_EVAL_SUITE_FROZEN));
        assertThatThrownBy(() -> service.createCase(new AiEvalCaseSaveDTO()
                        .setSuiteId(7L)
                        .setCaseKey("case_b")
                        .setTitle("新样例")
                        .setQuestion("问题")
                        .setChecksJson(VALID_CHECKS)))
                .satisfies(exception -> assertCode(exception, AI_EVAL_SUITE_FROZEN));
        when(caseMapper.selectById(11L)).thenReturn(evalCase());
        assertThatThrownBy(() -> service.deleteCase(11L, 0))
                .satisfies(exception -> assertCode(exception, AI_EVAL_SUITE_FROZEN));
        verify(caseMapper, never()).deleteById(anyLong());

        service.newRevision(7L, 1);
        ArgumentCaptor<AiEvalSuiteDO> captor = ArgumentCaptor.forClass(AiEvalSuiteDO.class);
        verify(suiteMapper).updateWithVersion(captor.capture(), anyInt());
        assertThat(captor.getValue().getStatus()).isEqualTo(AiEvalSuiteDO.STATUS_DRAFT);
        assertThat(captor.getValue().getRevision()).isEqualTo(3);
    }

    @Test
    void newRevisionRejectsDraftAndCaseKeyMustBeUnique() {
        assertThatThrownBy(() -> service.newRevision(7L, 0))
                .satisfies(exception -> assertCode(exception, AI_EVAL_SUITE_NOT_FROZEN));

        when(caseMapper.selectByCaseKey(7L, "case_a")).thenReturn(evalCase());
        assertThatThrownBy(() -> service.createCase(new AiEvalCaseSaveDTO()
                        .setSuiteId(7L)
                        .setCaseKey("case_a")
                        .setTitle("重复标识")
                        .setQuestion("问题")
                        .setChecksJson(VALID_CHECKS)))
                .satisfies(exception -> assertCode(exception, AI_EVAL_CASE_KEY_DUPLICATE));
    }

    @Test
    void caseRulesAreValidatedOnWriteAndNormalizedForStableDigests() {
        when(caseMapper.selectByCaseKey(7L, "case_b")).thenReturn(null);
        when(caseMapper.insert(any(AiEvalCaseDO.class))).thenAnswer(invocation -> {
            ((AiEvalCaseDO) invocation.getArgument(0)).setId(12L);
            return 1;
        });

        Long caseId = service.createCase(new AiEvalCaseSaveDTO()
                .setSuiteId(7L)
                .setCaseKey("case_b")
                .setTitle("金额核对")
                .setQuestion("净额是多少？")
                .setChecksJson("[ { \"path\" : \"amount\" , \"expected\" : \"450.00\" , \"kind\" : \"MONEY\" } ]"));
        assertThat(caseId).isEqualTo(12L);

        ArgumentCaptor<AiEvalCaseDO> captor = ArgumentCaptor.forClass(AiEvalCaseDO.class);
        verify(caseMapper).insert(captor.capture());
        assertThat(captor.getValue().getChecksJson())
                .as("入库即规范化：键按字典序")
                .isEqualTo("[{\"expected\":\"450.00\",\"kind\":\"MONEY\",\"path\":\"amount\"}]");
        assertThat(captor.getValue().getSeverity()).isEqualTo(AiEvalCaseDO.SEVERITY_MAJOR);
        assertThat(captor.getValue().getNeedsReview()).isFalse();

        assertThatThrownBy(() -> service.createCase(new AiEvalCaseSaveDTO()
                        .setSuiteId(7L)
                        .setCaseKey("case_c")
                        .setTitle("坏规则")
                        .setQuestion("问题")
                        .setChecksJson("[{\"kind\":\"VALUE\"}]")))
                .satisfies(exception -> assertCode(exception, AI_EVAL_CHECK_INVALID));
    }

    @Test
    void updateAndDeleteRewriteOnlyDraftRowsAndPageValidatesStatus() {
        service.updateSuite(new AiEvalSuiteSaveDTO()
                .setId(7L)
                .setVersion(0)
                .setName("订单问答评测（改名）")
                .setDescription("说明")
                .setDataLevel(AiEvalSuiteDO.LEVEL_PUBLIC));
        ArgumentCaptor<AiEvalSuiteDO> suiteCaptor = ArgumentCaptor.forClass(AiEvalSuiteDO.class);
        verify(suiteMapper).updateWithVersion(suiteCaptor.capture(), anyInt());
        assertThat(suiteCaptor.getValue().getDataLevel()).isEqualTo(AiEvalSuiteDO.LEVEL_PUBLIC);
        assertThat(suiteCaptor.getValue().getVersion()).isEqualTo(1);

        when(caseMapper.selectById(11L)).thenReturn(evalCase());
        service.deleteCase(11L, 0);
        verify(caseMapper).deleteById(11L);

        when(suiteMapper.selectPage(
                        org.mockito.ArgumentMatchers.any(PageParam.class),
                        org.mockito.ArgumentMatchers.nullable(Long.class),
                        org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(new PageResult<>(List.of(), 0L));
        assertThat(service.pageSuites(new PageParam(), 1L, AiEvalSuiteDO.STATUS_FROZEN)
                        .getTotal())
                .isZero();
        assertThatThrownBy(() -> service.pageSuites(new PageParam(), 1L, "ARCHIVED"))
                .satisfies(exception -> assertCode(exception, AI_REQUEST_INVALID));

        assertThatThrownBy(() -> service.requireSuite(null))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_EVAL_SUITE_NOT_EXISTS));
        assertThatThrownBy(() -> service.requireCase(null))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_EVAL_CASE_NOT_EXISTS));
    }

    @Test
    void caseUpdateValidatesOwnershipAndFields() {
        when(caseMapper.selectById(11L)).thenReturn(evalCase());
        when(caseMapper.updateWithVersion(any(AiEvalCaseDO.class), anyInt())).thenReturn(1);

        service.updateCase(new AiEvalCaseSaveDTO()
                .setId(11L)
                .setVersion(0)
                .setSuiteId(7L)
                .setTitle("新标题")
                .setQuestion("新问题")
                .setSeverity(AiEvalCaseDO.SEVERITY_MINOR)
                .setChecksJson(VALID_CHECKS)
                .setNeedsReview(true));
        ArgumentCaptor<AiEvalCaseDO> captor = ArgumentCaptor.forClass(AiEvalCaseDO.class);
        verify(caseMapper).updateWithVersion(captor.capture(), anyInt());
        assertThat(captor.getValue().getNeedsReview()).isTrue();
        assertThat(captor.getValue().getSeverity()).isEqualTo(AiEvalCaseDO.SEVERITY_MINOR);

        assertThatThrownBy(() -> service.updateCase(new AiEvalCaseSaveDTO()
                        .setId(11L)
                        .setVersion(0)
                        .setSuiteId(7L)
                        .setTitle("  ")
                        .setQuestion("问题")))
                .satisfies(exception -> assertCode(exception, AI_REQUEST_INVALID));
        assertThatThrownBy(() -> service.updateCase(new AiEvalCaseSaveDTO()
                        .setId(11L)
                        .setVersion(99)
                        .setSuiteId(7L)
                        .setTitle("标题")
                        .setQuestion("问题")))
                .satisfies(exception -> assertCode(exception, AI_STATE_CONFLICT));
        assertThatThrownBy(() -> service.updateSuite(
                        new AiEvalSuiteSaveDTO().setId(7L).setVersion(0).setName(" ")))
                .satisfies(exception -> assertCode(exception, AI_REQUEST_INVALID));
    }
}
