package com.basicframework.module.ai.service.semantic;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_ACCESS_DENIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_CURRENCY_CONVERSION_MISSING_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_SEMANTICS_CODE_DUPLICATE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_SEMANTICS_DISABLED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_SEMANTICS_FINGERPRINT_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_SEMANTICS_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_SEMANTICS_PUBLISHER_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_SEMANTICS_REVISION_EXPIRED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_SEMANTICS_REVISION_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_SEMANTICS_REVISION_NOT_PUBLISHED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMetricSemanticsDO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMetricSemanticsRevisionDO;
import com.basicframework.module.ai.dal.mysql.semantic.AiMetricSemanticsMapper;
import com.basicframework.module.ai.dal.mysql.semantic.AiMetricSemanticsRevisionMapper;
import com.basicframework.module.ai.domain.semantic.AiMetricSemantics;
import com.basicframework.module.ai.domain.semantic.AiMetricSemanticsFacts;
import com.basicframework.module.ai.domain.semantic.AiMetricSemanticsFixtures;
import com.basicframework.module.ai.service.semantic.dto.AiMetricSemanticsRevisionDraftDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMetricSemanticsSaveDTO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** 跨源指标口径的登记、发布与核验（Y03）：与 Y02 同一套安全语义的实现侧。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiMetricSemanticsServiceImplTest {

    @Mock
    private AiMetricSemanticsMapper semanticsMapper;

    @Mock
    private AiMetricSemanticsRevisionMapper revisionMapper;

    private AiMetricSemanticsServiceImpl service;

    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 9, 20, 12, 0);

    /** 草稿创建人（不能自己发布自己的草稿）。 */
    private static final Long OPERATOR = 100L;

    /** 独立审核人。 */
    private static final Long REVIEWER = 200L;

    @BeforeEach
    void setUp() {
        service = new AiMetricSemanticsServiceImpl(semanticsMapper, revisionMapper);
        // 操作员身份：无身份不得登记口径（口径决定"哪些数可以相加"）
        loginAs(OPERATOR);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(Long operatorId) {
        LoginUser loginUser = new LoginUser().setId(operatorId).setUserType(UserTypeEnum.ADMIN.getValue());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    private static void clearOperator() {
        SecurityContextHolder.clearContext();
    }

    private static AiMetricSemanticsSaveDTO saveDTO() {
        AiMetricSemanticsSaveDTO dto = new AiMetricSemanticsSaveDTO();
        dto.setMetricCode("net_revenue");
        dto.setMetricName("净收入");
        dto.setDescription("跨系统净额");
        return dto;
    }

    private static AiMetricSemanticsRevisionDraftDTO draftDTO(String definitionJson) {
        AiMetricSemanticsRevisionDraftDTO dto = new AiMetricSemanticsRevisionDraftDTO();
        dto.setMetricCode("net_revenue");
        dto.setMetricName("净收入");
        dto.setDefinitionJson(definitionJson);
        dto.setValidFrom("2026-01-01T00:00:00");
        dto.setValidTo(null);
        return dto;
    }

    private AiMetricSemanticsDO semanticsDO() {
        return new AiMetricSemanticsDO()
                .setId(7L)
                .setMetricCode("net_revenue")
                .setStatus(AiMetricSemanticsDO.STATUS_ACTIVE)
                .setCurrentRevision(1L)
                .setVersion(0);
    }

    @Test
    void createSemanticsRejectsDuplicatesAndAnonymousCallers() {
        when(semanticsMapper.selectByCode("net_revenue")).thenReturn(semanticsDO());
        assertThatThrownBy(() -> service.createSemantics(saveDTO()))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_SEMANTICS_CODE_DUPLICATE.getCode());

        when(semanticsMapper.selectByCode("net_revenue")).thenReturn(null);
        clearOperator();
        assertThatThrownBy(() -> service.createSemantics(saveDTO()))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_ACCESS_DENIED.getCode());
        verify(semanticsMapper, never()).insert(any(AiMetricSemanticsDO.class));
    }

    @Test
    void createSemanticsRejectsIllegalCodesAndNullInput() {
        // 已登录但入参为 null → 入参非法（而不是"没权限"：身份检查在更早一步已经通过）
        assertThatThrownBy(() -> service.createSemantics(null))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_REQUEST_INVALID.getCode());

        AiMetricSemanticsSaveDTO bad = saveDTO();
        bad.setMetricCode("Bad-Code");
        assertThatThrownBy(() -> service.createSemantics(bad))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_REQUEST_INVALID.getCode());
    }

    @Test
    void createRevisionStoresTheCanonicalDefinitionAndRejectsInvalidOnes() {
        when(semanticsMapper.selectByCode("net_revenue")).thenReturn(semanticsDO());
        when(revisionMapper.selectList(any())).thenReturn(List.of());

        service.createRevision(draftDTO(AiMetricSemanticsFixtures.threeSources()));

        ArgumentCaptor<AiMetricSemanticsRevisionDO> captor = ArgumentCaptor.forClass(AiMetricSemanticsRevisionDO.class);
        verify(revisionMapper).insert(captor.capture());
        AiMetricSemanticsRevisionDO stored = captor.getValue();
        // 落库的是规范化内容：指纹基于它重算，因此读取时能发现版本外改动
        assertThat(stored.getRevisionNo()).isEqualTo(1L);
        assertThat(stored.getStatus()).isEqualTo(AiMetricSemanticsRevisionDO.STATUS_DRAFT);
        assertThat(AiMetricSemantics.parse(stored.getDefinitionJson()).definitionHash())
                .isEqualTo(AiMetricSemantics.parse(AiMetricSemanticsFixtures.threeSources())
                        .definitionHash());
        assertThat(stored.getValidFrom()).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    @Test
    void createRevisionRejectsInvalidDefinitionAndBadTimeWindow() {
        when(semanticsMapper.selectByCode("net_revenue")).thenReturn(semanticsDO());
        when(revisionMapper.selectList(any())).thenReturn(List.of());

        // 形状不合规的定义写不进草稿（登记即校验结构）
        assertThatThrownBy(() -> service.createRevision(draftDTO("not-json"))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.createRevision(draftDTO("{}"))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.createRevision(draftDTO(AiMetricSemanticsFixtures.caliber(
                        "CNY", "CNY", "dset_orders", "dset_invoices", 1, "[\"order\",\"invoice\"]", "null", false))))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.createRevision(null))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_REQUEST_INVALID.getCode());

        AiMetricSemanticsRevisionDraftDTO badTime = draftDTO(AiMetricSemanticsFixtures.threeSources());
        badTime.setValidFrom("not-a-time");
        assertThatThrownBy(() -> service.createRevision(badTime))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_REQUEST_INVALID.getCode());
        verify(revisionMapper, never()).insert(any(AiMetricSemanticsRevisionDO.class));
    }

    @Test
    void mixedCurrencyCaliberIsPublishedButRejectedAtAggregationTime() {
        // 口径是"声明意图"：发布只冻结声明 + 指纹 + 独立审核。
        // 币种/时区这类业务事实由聚合校验（单一真源）拒绝，**不在发布时**拦——
        // 两处都判会让"同一份口径在不同路径下结论不同"，并让聚合侧的门永远走不到。
        when(semanticsMapper.selectByCode("net_revenue")).thenReturn(semanticsDO());
        when(revisionMapper.selectList(any())).thenReturn(List.of());
        service.createRevision(draftDTO(AiMetricSemanticsFixtures.mixedCurrencies()));
        verify(revisionMapper).insert(any(AiMetricSemanticsRevisionDO.class));

        AiMetricSemantics mixed = AiMetricSemantics.parse(AiMetricSemanticsFixtures.mixedCurrencies());
        AiMetricSemanticsRevisionDO draft = new AiMetricSemanticsRevisionDO()
                .setId(3L)
                .setMetricSemanticsId(7L)
                .setRevisionNo(1L)
                .setStatus(AiMetricSemanticsRevisionDO.STATUS_DRAFT)
                .setDefinitionJson(mixed.canonicalJson())
                .setCreatedBy(OPERATOR)
                .setVersion(0);
        AiMetricSemanticsRevisionDO publishedRow = new AiMetricSemanticsRevisionDO()
                .setId(3L)
                .setMetricSemanticsId(7L)
                .setRevisionNo(1L)
                .setStatus(AiMetricSemanticsRevisionDO.STATUS_PUBLISHED)
                .setDefinitionJson(mixed.canonicalJson())
                .setDefinitionFingerprint(mixed.definitionHash())
                .setValidFrom(LocalDateTime.of(2026, 1, 1, 0, 0))
                .setPublishedBy(REVIEWER)
                .setVersion(1);
        when(semanticsMapper.selectById(7L)).thenReturn(semanticsDO());
        when(revisionMapper.selectByRevisionNo(7L, 1L)).thenReturn(draft).thenReturn(publishedRow);
        when(revisionMapper.publishWithVersion(any(), any(Integer.class))).thenReturn(1);
        when(semanticsMapper.updateWithVersion(any(), any(Integer.class))).thenReturn(1);
        loginAs(REVIEWER);
        assertThat(service.publishRevision(7L, 1L, 0).getStatus())
                .isEqualTo(AiMetricSemanticsRevisionDO.STATUS_PUBLISHED);

        // 聚合侧按版本判定：币种不一致且无换算规则 → 拒绝求和（不是静默相加）
        AiMetricSemantics verified = service.resolveVerified("net_revenue", 1L, AS_OF);
        assertThatThrownBy(() -> AiMetricSemanticsFacts.requireSummable(verified, verified.sources()))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_CURRENCY_CONVERSION_MISSING_CONFLICT.getCode());
    }

    @Test
    void publishRequiresAnIndependentReviewerAndAdvancesTheCurrentRevision() {
        AiMetricSemanticsRevisionDO draft = new AiMetricSemanticsRevisionDO()
                .setId(3L)
                .setMetricSemanticsId(7L)
                .setRevisionNo(1L)
                .setStatus(AiMetricSemanticsRevisionDO.STATUS_DRAFT)
                .setDefinitionJson(AiMetricSemantics.parse(AiMetricSemanticsFixtures.threeSources())
                        .canonicalJson())
                .setCreatedBy(OPERATOR)
                .setVersion(0);
        when(semanticsMapper.selectById(7L)).thenReturn(semanticsDO());
        when(revisionMapper.selectByRevisionNo(7L, 1L)).thenReturn(draft);

        // 草稿创建人自己发布 → 独立审核不成立
        assertThatThrownBy(() -> service.publishRevision(7L, 1L, 0))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_SEMANTICS_PUBLISHER_CONFLICT.getCode());
        verify(revisionMapper, never()).publishWithVersion(any(), any(Integer.class));

        // 换成另一位审核人 → 允许发布，并推进口径的当前版本
        loginAs(REVIEWER);
        when(revisionMapper.publishWithVersion(any(), any(Integer.class))).thenReturn(1);
        when(semanticsMapper.updateWithVersion(any(), any(Integer.class))).thenReturn(1);
        // 刻意用**另一个**对象表示发布后的行：链式 setter 会就地改写 draft，
        // 复用同一个引用会让第一个 thenReturn 也返回已发布态（草稿检查随即失败）。
        AiMetricSemanticsRevisionDO publishedRow = copyWithPublished(draft);
        when(revisionMapper.selectByRevisionNo(7L, 1L)).thenReturn(draft).thenReturn(publishedRow);
        AiMetricSemanticsRevisionDO published = service.publishRevision(7L, 1L, 0);
        assertThat(published.getStatus()).isEqualTo(AiMetricSemanticsRevisionDO.STATUS_PUBLISHED);
        assertThat(published.getDefinitionFingerprint()).isEqualTo(currentFingerprint());
        ArgumentCaptor<AiMetricSemanticsDO> advance = ArgumentCaptor.forClass(AiMetricSemanticsDO.class);
        verify(semanticsMapper).updateWithVersion(advance.capture(), any(Integer.class));
        assertThat(advance.getValue().getCurrentRevision()).isEqualTo(1L);
    }

    private static AiMetricSemanticsRevisionDO copyWithPublished(AiMetricSemanticsRevisionDO source) {
        return new AiMetricSemanticsRevisionDO()
                .setId(source.getId())
                .setMetricSemanticsId(source.getMetricSemanticsId())
                .setRevisionNo(source.getRevisionNo())
                .setStatus(AiMetricSemanticsRevisionDO.STATUS_PUBLISHED)
                .setDefinitionJson(source.getDefinitionJson())
                .setDefinitionFingerprint(currentFingerprint())
                .setPublishedBy(REVIEWER);
    }

    @Test
    void publishRefusesWhenEitherOptimisticLockLoses() {
        AiMetricSemanticsRevisionDO draft = new AiMetricSemanticsRevisionDO()
                .setId(3L)
                .setMetricSemanticsId(7L)
                .setRevisionNo(1L)
                .setStatus(AiMetricSemanticsRevisionDO.STATUS_DRAFT)
                .setDefinitionJson(AiMetricSemantics.parse(AiMetricSemanticsFixtures.threeSources())
                        .canonicalJson())
                .setCreatedBy(OPERATOR)
                .setVersion(0);
        when(semanticsMapper.selectById(7L)).thenReturn(semanticsDO());
        when(revisionMapper.selectByRevisionNo(7L, 1L)).thenReturn(draft);
        loginAs(REVIEWER);

        // 版本 CAS 失败：不得留下"版本已发布"的状态
        when(revisionMapper.publishWithVersion(any(), any(Integer.class))).thenReturn(0);
        assertThatThrownBy(() -> service.publishRevision(7L, 1L, 0)).isInstanceOf(ServiceException.class);

        // 口径推进 CAS 失败：同一事务回滚，不产生中间态
        when(revisionMapper.publishWithVersion(any(), any(Integer.class))).thenReturn(1);
        when(semanticsMapper.updateWithVersion(any(), any(Integer.class))).thenReturn(0);
        assertThatThrownBy(() -> service.publishRevision(7L, 1L, 0)).isInstanceOf(ServiceException.class);
    }

    @Test
    void publishRefusesNonDraftRevisionsAndInvalidVersions() {
        AiMetricSemanticsRevisionDO publishedRevision = new AiMetricSemanticsRevisionDO()
                .setId(3L)
                .setMetricSemanticsId(7L)
                .setRevisionNo(1L)
                .setStatus(AiMetricSemanticsRevisionDO.STATUS_PUBLISHED)
                .setDefinitionJson(AiMetricSemantics.parse(AiMetricSemanticsFixtures.threeSources())
                        .canonicalJson())
                .setCreatedBy(OPERATOR)
                .setVersion(0);
        when(semanticsMapper.selectById(7L)).thenReturn(semanticsDO());
        when(revisionMapper.selectByRevisionNo(7L, 1L)).thenReturn(publishedRevision);
        loginAs(REVIEWER);
        assertThatThrownBy(() -> service.publishRevision(7L, 1L, 0)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.publishRevision(7L, 1L, null)).isInstanceOf(ServiceException.class);
        when(semanticsMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.publishRevision(99L, 1L, 0))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_SEMANTICS_NOT_EXISTS.getCode());
    }

    @Test
    void resolveVerifiedBlocksDraftExpiredDisabledAndTamperedRevisions() {
        when(semanticsMapper.selectByCode("net_revenue")).thenReturn(semanticsDO());
        when(revisionMapper.selectByRevisionNo(7L, 1L))
                .thenReturn(revisionDO(AiMetricSemanticsRevisionDO.STATUS_DRAFT, "wrong-fingerprint"))
                .thenReturn(revisionDO(AiMetricSemanticsRevisionDO.STATUS_PUBLISHED, "wrong-fingerprint"))
                .thenReturn(revisionDO(AiMetricSemanticsRevisionDO.STATUS_PUBLISHED, currentFingerprint()))
                .thenReturn(revisionDO(AiMetricSemanticsRevisionDO.STATUS_PUBLISHED, "tampered"));

        // 草稿不是可核验事实
        assertThatThrownBy(() -> service.resolveVerified("net_revenue", 1L, AS_OF))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_SEMANTICS_REVISION_NOT_PUBLISHED_CONFLICT.getCode());
        // 指纹不符（版本外改动）
        assertThatThrownBy(() -> service.resolveVerified("net_revenue", 1L, AS_OF))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_SEMANTICS_FINGERPRINT_CONFLICT.getCode());
        // 正常版本可以核验
        assertThat(service.resolveVerified("net_revenue", 1L, AS_OF).metricCode())
                .isEqualTo("net_revenue");
        // 指纹与内容不符（重算后不同）
        assertThatThrownBy(() -> service.resolveVerified("net_revenue", 1L, AS_OF))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_SEMANTICS_FINGERPRINT_CONFLICT.getCode());
    }

    @Test
    void resolveVerifiedBlocksRevisionsOutsideTheirValidityWindow() {
        when(semanticsMapper.selectByCode("net_revenue")).thenReturn(semanticsDO());
        when(revisionMapper.selectByRevisionNo(7L, 1L))
                .thenReturn(revisionDO(AiMetricSemanticsRevisionDO.STATUS_PUBLISHED, currentFingerprint()));
        // 版本有效期 2026-01-01 起，判定时刻在窗口内
        assertThat(service.resolveVerified("net_revenue", 1L, AS_OF)).isNotNull();
        // 判定时刻早于有效期起点 → 阻断，而不是回退到"当前版本"
        assertThatThrownBy(() -> service.resolveVerified("net_revenue", 1L, LocalDateTime.of(2025, 6, 1, 0, 0)))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_SEMANTICS_REVISION_EXPIRED_CONFLICT.getCode());
        // 判定时刻缺失（不允许"取服务器当前时间"的省略写法）
        assertThatThrownBy(() -> service.resolveVerified("net_revenue", 1L, null))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_SEMANTICS_REVISION_EXPIRED_CONFLICT.getCode());
    }

    @Test
    void resolveVerifiedBlocksDisabledSemanticsAndUnknownVersions() {
        AiMetricSemanticsDO disabled = semanticsDO().setStatus(AiMetricSemanticsDO.STATUS_DISABLED);
        when(semanticsMapper.selectByCode("net_revenue")).thenReturn(disabled);
        assertThatThrownBy(() -> service.resolveVerified("net_revenue", 1L, AS_OF))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_SEMANTICS_DISABLED_CONFLICT.getCode());

        when(semanticsMapper.selectByCode("net_revenue")).thenReturn(semanticsDO());
        when(revisionMapper.selectByRevisionNo(7L, 99L)).thenReturn(null);
        assertThatThrownBy(() -> service.resolveVerified("net_revenue", 99L, AS_OF))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_SEMANTICS_REVISION_NOT_EXISTS.getCode());

        when(semanticsMapper.selectByCode("net_revenue")).thenReturn(null);
        assertThatThrownBy(() -> service.resolveVerified("net_revenue", 1L, AS_OF))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_SEMANTICS_NOT_EXISTS.getCode());
    }

    @Test
    void statusUpdateUsesOptimisticLockingAndRejectsMissingRows() {
        when(semanticsMapper.selectById(7L)).thenReturn(semanticsDO());
        when(semanticsMapper.updateWithVersion(any(), any(Integer.class))).thenReturn(1, 0);
        service.updateSemanticsStatus(7L, 3, false);
        assertThatThrownBy(() -> service.updateSemanticsStatus(7L, 3, false)).isInstanceOf(ServiceException.class);

        assertThatThrownBy(() -> service.updateSemanticsStatus(7L, null, true))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_REQUEST_INVALID.getCode());
        when(semanticsMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.updateSemanticsStatus(99L, 0, true))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AI_METRIC_SEMANTICS_NOT_EXISTS.getCode());
    }

    @Test
    void publisherConflictCodeIsDistinctFromOtherPublishFailures() {
        // 发布人冲突必须是独立错误码，不能与"状态冲突"混用
        assertThat(AI_METRIC_SEMANTICS_PUBLISHER_CONFLICT.getCode())
                .isNotEqualTo(AI_METRIC_SEMANTICS_REVISION_NOT_PUBLISHED_CONFLICT.getCode());
        assertThat(AI_METRIC_SEMANTICS_PUBLISHER_CONFLICT.getCode()).isEqualTo(1_003_016_019);
    }

    private static String currentFingerprint() {
        return AiMetricSemantics.parse(AiMetricSemanticsFixtures.threeSources()).definitionHash();
    }

    private static AiMetricSemanticsRevisionDO revisionDO(String status, String fingerprint) {
        return new AiMetricSemanticsRevisionDO()
                .setId(3L)
                .setMetricSemanticsId(7L)
                .setRevisionNo(1L)
                .setStatus(status)
                .setDefinitionJson(AiMetricSemantics.parse(AiMetricSemanticsFixtures.threeSources())
                        .canonicalJson())
                .setDefinitionFingerprint(fingerprint)
                .setValidFrom(LocalDateTime.of(2026, 1, 1, 0, 0))
                .setValidTo(null)
                .setVersion(0);
    }
}
