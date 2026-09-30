package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectRevisionDO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.domain.semantic.AiMasterMappingProblem;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.authorization.AiResourceGrantService;
import com.basicframework.module.ai.service.semantic.AiMasterMappingResolver;
import com.basicframework.module.ai.service.semantic.AiMasterObjectCatalogService;
import com.basicframework.module.ai.service.semantic.AiMasterObjectService;
import com.basicframework.module.ai.service.semantic.dto.AiMasterCatalogEntryDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingEntrySaveDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingResolutionDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingResolveDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingReverseDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingReverseResultDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterObjectCatalogDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterObjectCatalogQueryDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterObjectSaveDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterRevisionDetailDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterRevisionDraftDTO;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Y02 跨系统业务对象与主数据映射（真实 MySQL + Redis），核心是 **AT-070**。
 *
 * <p>验收逐条对应：
 * <ol>
 *   <li><b>跨系统同名不同实体不合并</b>：CRM/ERP 两侧展示名完全相同的客户，只有**显式登记的源键**
 *       才能关联；未登记的源键反查返回 {@code mapped=false}（未映射不关联），绝不是"名字一样就合并"；</li>
 *   <li><b>过期/冲突阻断</b>：源键有效期不覆盖判定时刻、同一实体一对多、同一源键多对一、
 *       版本未发布/版本过期/版本指纹被改动，全部抛稳定错误码，绝不静默取一个；</li>
 *   <li><b>换映射版本不改旧报表</b>：发布新版本后，按旧版本号（+冻结指纹）判定得到与受理时完全相同
 *       的结果；已发布版本不可编辑，版本外改动会被指纹重算拦住。</li>
 * </ol>
 *
 * <p>夹具即"合成跨系统夹具"：CRM/ERP 两个接入应用、两侧同名主体与同名客户、显式登记的源键。
 */
@Import(AiMasterMappingAcceptanceIT.MasterMappingScopeConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiMasterMappingAcceptanceIT extends AbstractPersistenceIntegrationTest {

    private static final String CRM_APP = "it-md-crm";

    private static final String ERP_APP = "it-md-erp";

    private static final String CRM_REPORT = "crm_q3_report";

    private static final String ERP_DATASET = "erp_orders";

    /** 两侧展示名完全相同：判定路径不读名称，因此不可能被合并。 */
    private static final String SAME_NAME = "杭州云启科技有限公司";

    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 9, 1, 0, 0);

    private static final LocalDateTime WINDOW_FROM = LocalDateTime.of(2026, 1, 1, 0, 0);

    /** 与既有 AI IT 同源：范围解析器给出受控范围（不改生产解析器）。 */
    @TestConfiguration
    static class MasterMappingScopeConfiguration {

        @Bean
        com.basicframework.module.ai.domain.identity.SubjectScopeResolver masterMappingScopeResolver() {
            return request -> java.util.Optional.of(new com.basicframework.module.ai.domain.identity.SubjectScope(
                    Set.of(10L), Set.copyOf(request.resourceHints()), request.scopeSource(), request.scopeVersion()));
        }
    }

    @Autowired
    private AiApplicationService applicationService;

    @Autowired
    private AiSubjectService subjectService;

    @Autowired
    private AiResourceGrantService grantService;

    @Autowired
    private AiMasterObjectService masterObjectService;

    @Autowired
    private AiMasterMappingResolver mappingResolver;

    @Autowired
    private AiMasterObjectCatalogService catalogService;

    private Long crmApplicationId;

    private Long erpApplicationId;

    private Long cloudObjectId;

    @BeforeEach
    void prepare() {
        cleanUpData();
        crmApplicationId = createApplication(CRM_APP, "CRM 系统");
        erpApplicationId = createApplication(ERP_APP, "ERP 系统");
        subjectService.syncSubject(crmApplicationId, AiSubjectType.USER, "alice", "alice", "crm-auth", 1L);
        subjectService.syncSubject(erpApplicationId, AiSubjectType.USER, "alice", "alice", "erp-auth", 1L);
        subjectService.syncSubject(crmApplicationId, AiSubjectType.USER, "bob", "bob", "crm-auth", 1L);
        grantService.createGrant(
                crmApplicationId,
                AiSubjectType.USER.name(),
                "alice",
                AiResourceType.REPORT.name(),
                CRM_REPORT,
                Set.of(AiAction.READ.name(), AiAction.EXECUTE.name()));
        grantService.createGrant(
                erpApplicationId,
                AiSubjectType.USER.name(),
                "alice",
                AiResourceType.DATASET.name(),
                ERP_DATASET,
                Set.of(AiAction.READ.name()));
        cloudObjectId = createObject("md_cloud_qi", "云启科技（统一客户）");
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        cleanUpData();
        crmApplicationId = null;
        erpApplicationId = null;
        cloudObjectId = null;
    }

    /**
     * AT-070 第一层：**跨系统同名不同实体绝不合并**。
     *
     * <p>CRM 的 C-1001 与 ERP 的 E-9001 才是同一个法人（显式登记）；CRM 的 C-1002 与 ERP 的 E-9002
     * 展示名与前者**逐字相同**，但未被登记到同一对象——反查只会得到它们自己的对象，
     * 未登记的源键更是直接返回"未映射"。
     */
    @Test
    void sameDisplayNameAcrossSystemsIsNeverMergedWithoutExplicitRegistration() {
        Long namesakeObjectId = createObject("md_namesake", "同名但不同的客户");
        registerAndPublish(
                cloudObjectId,
                entry(crmApplicationId, "C-1001", SAME_NAME),
                entry(erpApplicationId, "E-9001", SAME_NAME));
        registerAndPublish(namesakeObjectId, entry(erpApplicationId, "E-9002", SAME_NAME));

        // 同一个统一对象在两个系统里的源键分别可判定，且结果携带版本指纹
        AiMasterMappingResolutionDTO crmKey =
                mappingResolver.resolveObjectKey(resolve("md_cloud_qi", crmApplicationId, "customer"));
        AiMasterMappingResolutionDTO erpKey =
                mappingResolver.resolveObjectKey(resolve("md_cloud_qi", erpApplicationId, "customer"));
        assertThat(crmKey.getSourceKey()).isEqualTo("C-1001");
        assertThat(erpKey.getSourceKey()).isEqualTo("E-9001");
        assertThat(crmKey.getRevisionFingerprint()).isNotBlank();
        assertThat(crmKey.getObjectCode()).isEqualTo("md_cloud_qi");

        // 同名不同实体：C-1002/E-9002 有自己的对象，绝不被合并进 md_cloud_qi
        assertThat(reverse("md_cloud_qi", crmApplicationId, "C-1002")).isFalse();
        assertThat(reverse("md_cloud_qi", erpApplicationId, "E-9002")).isFalse();
        assertThat(mappingResolver
                        .resolveSourceKey(reverseRequest(erpApplicationId, "E-9002"))
                        .getObjectCode())
                .isEqualTo("md_namesake");
        assertThat(mappingResolver
                        .resolveSourceKey(reverseRequest(crmApplicationId, "C-1001"))
                        .getObjectCode())
                .isEqualTo("md_cloud_qi");

        // 未登记即不关联：CRM 侧另一个同名客户没有映射，反查是"未映射"而不是猜测
        AiMasterMappingReverseResultDTO unmapped =
                mappingResolver.resolveSourceKey(reverseRequest(crmApplicationId, "C-1003"));
        assertThat(unmapped.isMapped()).isFalse();
        assertThat(unmapped.getReason()).isEqualTo("NOT_REGISTERED");
    }

    /**
     * AT-070 第二层：**冲突阻断**（一对多/多对一），发布与判定都不能"挑一个"。
     */
    @Test
    void conflictingMappingsBlockPublishAndJudgement() {
        Long conflictingObjectId = createObject("md_conflict", "冲突候选对象");
        registerAndPublish(cloudObjectId, entry(erpApplicationId, "E-9001", SAME_NAME));

        // 多对一：另一个对象的草稿登记了同一个源键（时间窗重叠）→ 发布被拒，草稿保持 DRAFT
        loginAs(1001L);
        Long draftRevision = masterObjectService.createRevision(new AiMasterRevisionDraftDTO()
                .setMasterObjectId(conflictingObjectId)
                .setValidFrom(WINDOW_FROM));
        masterObjectService.addMappingEntry(entry(erpApplicationId, "E-9001", SAME_NAME)
                .setMasterObjectId(conflictingObjectId)
                .setRevisionNo(draftRevision));
        loginAs(1002L);
        assertCode(
                () -> masterObjectService.publishRevision(conflictingObjectId, draftRevision, 0),
                AiErrorCodeConstants.AI_MASTER_MAPPING_CONFLICT);
        assertThat(masterObjectService
                        .getRevision(conflictingObjectId, draftRevision)
                        .getStatus())
                .isEqualTo(AiMasterObjectRevisionDO.STATUS_DRAFT);
        // 草稿不是可核验事实：判定直接拒绝
        assertCode(
                () -> mappingResolver.resolveObjectKey(new AiMasterMappingResolveDTO()
                        .setObjectCode("md_conflict")
                        .setRevisionNo(draftRevision)
                        .setApplicationId(erpApplicationId)
                        .setEntityType("customer")
                        .setAsOf(AS_OF)),
                AiErrorCodeConstants.AI_MASTER_OBJECT_REVISION_NOT_PUBLISHED_CONFLICT);

        // 一对多：同一对象在同一系统里两条源键时间窗重叠（登记允许暂存，发布必须阻断）
        Long overlapObjectId = createObject("md_overlap", "一对多候选对象");
        loginAs(1001L);
        Long overlapRevision = masterObjectService.createRevision(new AiMasterRevisionDraftDTO()
                .setMasterObjectId(overlapObjectId)
                .setValidFrom(WINDOW_FROM));
        masterObjectService.addMappingEntry(entry(crmApplicationId, "C-2001", SAME_NAME)
                .setMasterObjectId(overlapObjectId)
                .setRevisionNo(overlapRevision));
        masterObjectService.addMappingEntry(entry(crmApplicationId, "C-2002", SAME_NAME)
                .setMasterObjectId(overlapObjectId)
                .setRevisionNo(overlapRevision));
        // 编辑期预览就把冲突与"不可发布"如实展示出来
        AiMasterRevisionDetailDTO detail = masterObjectService.getRevisionDetail(overlapObjectId, overlapRevision);
        assertThat(detail.isPublishable()).isFalse();
        assertThat(detail.getConflictKeys()).containsExactly(overlapObjectId + "/" + crmApplicationId + "/customer");
        assertThat(detail.getEntryProblems().values()).containsOnly(AiMasterMappingProblem.CONFLICT.name());
        loginAs(1002L);
        assertCode(
                () -> masterObjectService.publishRevision(overlapObjectId, overlapRevision, 0),
                AiErrorCodeConstants.AI_MASTER_MAPPING_CONFLICT);

        // 已有冲突数据（历史/人工修库）时的判定侧防线：直接写入一条同源键行后，反查必须阻断
        Long legacyObjectId = createObject("md_legacy", "历史冲突对象");
        registerAndPublish(legacyObjectId, entry(erpApplicationId, "E-LEGACY", SAME_NAME));
        jdbcTemplate.update(
                "INSERT INTO ai_master_object_mapping (master_object_id, revision, application_id, entity_type,"
                        + " source_key, source_name, match_method, valid_from, valid_to, version, creator,"
                        + " create_time, updater, update_time) VALUES (?, 1, ?, 'customer', 'E-9001', ?, 'MANUAL',"
                        + " ?, NULL, 0, '1', NOW(), '1', NOW())",
                masterObjectService.getObject(legacyObjectId).getId(),
                erpApplicationId,
                SAME_NAME,
                WINDOW_FROM);
        assertCode(
                () -> mappingResolver.resolveSourceKey(reverseRequest(erpApplicationId, "E-9001")),
                AiErrorCodeConstants.AI_MASTER_MAPPING_CONFLICT);
        assertCode(
                () -> mappingResolver.resolveObjectKey(resolve("md_cloud_qi", erpApplicationId, "customer")),
                AiErrorCodeConstants.AI_MASTER_MAPPING_CONFLICT);
    }

    /**
     * AT-070 第二层（续）：**过期阻断**——有效期不覆盖判定时刻的行不参与判定，且"过期"与
     * "未登记"用不同结果表达，调用方不可能把过期误读成未映射而静默降级。
     */
    @Test
    void expiredMappingsBlockInsteadOfSilentlyFallingBack() {
        Long expiringObjectId = createObject("md_expiring", "有效期受控对象");
        loginAs(1001L);
        Long revisionNo = masterObjectService.createRevision(new AiMasterRevisionDraftDTO()
                .setMasterObjectId(expiringObjectId)
                .setValidFrom(WINDOW_FROM));
        masterObjectService.addMappingEntry(entry(crmApplicationId, "C-3001", SAME_NAME)
                .setMasterObjectId(expiringObjectId)
                .setRevisionNo(revisionNo)
                .setValidTo(LocalDateTime.of(2026, 2, 1, 0, 0)));
        loginAs(1002L);
        masterObjectService.publishRevision(expiringObjectId, revisionNo, 0);

        // 有效期内的历史时刻可以判定（报表按受理时刻解释）
        assertThat(mappingResolver
                        .resolveObjectKey(new AiMasterMappingResolveDTO()
                                .setObjectCode("md_expiring")
                                .setRevisionNo(revisionNo)
                                .setApplicationId(crmApplicationId)
                                .setEntityType("customer")
                                .setAsOf(LocalDateTime.of(2026, 1, 15, 0, 0)))
                        .getSourceKey())
                .isEqualTo("C-3001");

        // 服务端当前时刻（2026-09-01）已过期：阻断
        assertCode(
                () -> mappingResolver.resolveObjectKey(resolve("md_expiring", crmApplicationId, "customer")),
                AiErrorCodeConstants.AI_MASTER_MAPPING_EXPIRED_CONFLICT);
        // 反查同一份过期事实：同样是阻断，不是"未映射"
        AiMasterMappingReverseDTO expiredReverse = reverseRequest(crmApplicationId, "C-3001");
        assertCode(
                () -> mappingResolver.resolveSourceKey(expiredReverse),
                AiErrorCodeConstants.AI_MASTER_MAPPING_EXPIRED_CONFLICT);

        // 版本级有效期：整版过期同样阻断（而不是回退到别的版本）
        Long versionedObjectId = createObject("md_versioned", "版本有效期受控对象");
        loginAs(1001L);
        Long expiredRevision = masterObjectService.createRevision(new AiMasterRevisionDraftDTO()
                .setMasterObjectId(versionedObjectId)
                .setValidFrom(WINDOW_FROM)
                .setValidTo(LocalDateTime.of(2026, 2, 1, 0, 0)));
        masterObjectService.addMappingEntry(entry(crmApplicationId, "C-4001", SAME_NAME)
                .setMasterObjectId(versionedObjectId)
                .setRevisionNo(expiredRevision));
        loginAs(1002L);
        masterObjectService.publishRevision(versionedObjectId, expiredRevision, 0);
        assertCode(
                () -> mappingResolver.resolveObjectKey(resolve("md_versioned", crmApplicationId, "customer")),
                AiErrorCodeConstants.AI_MASTER_OBJECT_REVISION_EXPIRED_CONFLICT);
    }

    /**
     * AT-070 第三层：**换映射版本不改旧结果**（版本固定 + 乐观锁 + 指纹）。
     *
     * <p>报表/产物在受理时固定（对象标识, 版本号, 版本指纹, 源键）；发布新版本只改变"当前版本"，
     * 旧版本的解释结果逐字段不变；已发布版本不可编辑，版本外改动会被指纹重算拦住。
     */
    @Test
    void publishingANewRevisionNeverChangesHowOldReportsAreInterpreted() {
        registerAndPublish(cloudObjectId, entry(crmApplicationId, "C-1001", SAME_NAME));

        // 报表受理：固定版本 1 的判定结果
        AiMasterMappingResolutionDTO accepted =
                mappingResolver.resolveObjectKey(resolve("md_cloud_qi", crmApplicationId, "customer"));
        assertThat(accepted.getRevisionNo()).isEqualTo(1L);
        assertThat(accepted.getSourceKey()).isEqualTo("C-1001");
        String revisionOneFingerprint = accepted.getRevisionFingerprint();

        // 换版本：CRM 侧换成新源键（旧键在 2026-06-01 关闭，新键从该时刻生效）
        loginAs(1001L);
        Long revisionTwo = masterObjectService.createRevision(
                new AiMasterRevisionDraftDTO().setMasterObjectId(cloudObjectId).setValidFrom(WINDOW_FROM));
        masterObjectService.addMappingEntry(entry(crmApplicationId, "C-1001", SAME_NAME)
                .setMasterObjectId(cloudObjectId)
                .setRevisionNo(revisionTwo)
                .setValidTo(LocalDateTime.of(2026, 6, 1, 0, 0)));
        masterObjectService.addMappingEntry(entry(crmApplicationId, "C-9999", SAME_NAME)
                .setMasterObjectId(cloudObjectId)
                .setRevisionNo(revisionTwo)
                .setValidFrom(LocalDateTime.of(2026, 6, 1, 0, 0)));
        loginAs(1002L);
        masterObjectService.publishRevision(cloudObjectId, revisionTwo, 0);
        assertThat(masterObjectService.getObject(cloudObjectId).getCurrentRevision())
                .isEqualTo(2L);

        // 旧报表按受理时的版本 1 解释：结果与指纹逐字段不变
        AiMasterMappingResolutionDTO replayed =
                mappingResolver.resolveObjectKey(resolve("md_cloud_qi", crmApplicationId, "customer"));
        assertThat(replayed.getSourceKey()).isEqualTo("C-1001");
        assertThat(replayed.getRevisionNo()).isEqualTo(accepted.getRevisionNo());
        assertThat(replayed.getRevisionFingerprint()).isEqualTo(revisionOneFingerprint);
        // 版本 1 的指纹在发布版本 2 之后依然是同一份事实
        assertThat(masterObjectService.getRevision(cloudObjectId, 1L).getMappingFingerprint())
                .isEqualTo(revisionOneFingerprint);

        // 显式选择版本 2 才看到新键（平台不会替调用方"顺手升级"）
        assertThat(mappingResolver
                        .resolveObjectKey(new AiMasterMappingResolveDTO()
                                .setObjectCode("md_cloud_qi")
                                .setRevisionNo(2L)
                                .setApplicationId(crmApplicationId)
                                .setEntityType("customer")
                                .setAsOf(AS_OF))
                        .getSourceKey())
                .isEqualTo("C-9999");

        // 已发布版本不可编辑：登记与删除都拒绝
        assertCode(
                () -> masterObjectService.addMappingEntry(entry(crmApplicationId, "C-7777", SAME_NAME)
                        .setMasterObjectId(cloudObjectId)
                        .setRevisionNo(1L)),
                AiErrorCodeConstants.AI_MASTER_OBJECT_REVISION_PUBLISHED_CONFLICT);
        Long revisionOneEntryId =
                masterObjectService.listEntries(cloudObjectId, 1L).get(0).getId();
        assertCode(
                () -> masterObjectService.removeMappingEntry(revisionOneEntryId, 0),
                AiErrorCodeConstants.AI_MASTER_OBJECT_REVISION_PUBLISHED_CONFLICT);

        // 版本外改动（直连数据库改源键）：指纹重算不符，判定阻断而不是按被改过的内容解释旧报表
        jdbcTemplate.update(
                "UPDATE ai_master_object_mapping SET source_key = 'C-TAMPERED' WHERE id = ?", revisionOneEntryId);
        assertCode(
                () -> mappingResolver.resolveObjectKey(resolve("md_cloud_qi", crmApplicationId, "customer")),
                AiErrorCodeConstants.AI_MASTER_OBJECT_REVISION_FINGERPRINT_CONFLICT);
        jdbcTemplate.update(
                "UPDATE ai_master_object_mapping SET source_key = 'C-1001' WHERE id = ?", revisionOneEntryId);
        assertThat(mappingResolver
                        .resolveObjectKey(resolve("md_cloud_qi", crmApplicationId, "customer"))
                        .getRevisionFingerprint())
                .isEqualTo(revisionOneFingerprint);
    }

    /**
     * 目录发现与 Y01 一致：无权系统不出现、拒绝不可区分、预算有界、模型目录不含源键值。
     */
    @Test
    void catalogDiscoveryHidesUnauthorizedSystemsAndIsBudgetBounded() {
        registerAndPublish(
                cloudObjectId,
                entry(crmApplicationId, "C-1001", SAME_NAME),
                entry(erpApplicationId, "E-9001", SAME_NAME));

        // alice 只有 CRM 侧授权：目录只出现 CRM，模型目录也不含 ERP
        AiMasterObjectCatalogDTO catalog = discover(cloudObjectId, 1L, "alice");
        assertThat(catalog.isDenied()).isFalse();
        assertThat(catalog.getEntries())
                .extracting(AiMasterCatalogEntryDTO::getAppCode)
                .containsExactly(CRM_APP);
        assertThat(catalog.getEntries().get(0).getSourceKey()).isEqualTo("C-1001");
        assertThat(catalog.getEntries().get(0).getProblem()).isEqualTo(AiMasterMappingProblem.NONE.name());
        assertThat(catalog.getModelCatalog()).contains(CRM_APP).doesNotContain(ERP_APP);
        // 模型可见目录不含源键值（业务数据不进提示词）
        assertThat(catalog.getModelCatalog()).doesNotContain("C-1001");
        assertThat(catalog.getCatalogFingerprint()).isNotBlank();

        // 主体已登记但无任何可访问系统 与 从未登记：目录完全同形（防枚举）
        AiMasterObjectCatalogDTO registeredWithoutAccess = discover(cloudObjectId, 1L, "bob");
        assertThat(registeredWithoutAccess.isDenied()).isTrue();
        assertThat(registeredWithoutAccess.getEntries()).isEmpty();
        assertThat(registeredWithoutAccess.getModelCatalog()).isEqualTo("[]");
        jdbcTemplate.update(
                "DELETE FROM ai_subject WHERE application_id = ? AND external_user_id = ?", crmApplicationId, "bob");
        AiMasterObjectCatalogDTO neverRegistered = discover(cloudObjectId, 1L, "bob");
        assertThat(neverRegistered.isDenied()).isTrue();
        assertThat(neverRegistered.getEntries()).isEmpty();
        assertThat(neverRegistered.getCatalogFingerprint()).isEqualTo(registeredWithoutAccess.getCatalogFingerprint());

        // 预算有界：单版本登记 200 条以内，第 201 条拒绝（不静默截断）
        Long budgetObjectId = createObject("md_budget", "预算受控对象");
        loginAs(1001L);
        Long budgetRevision = masterObjectService.createRevision(
                new AiMasterRevisionDraftDTO().setMasterObjectId(budgetObjectId).setValidFrom(WINDOW_FROM));
        for (int index = 0; index < AiMasterObjectService.MAX_ENTRIES_PER_REVISION; index++) {
            masterObjectService.addMappingEntry(entry(crmApplicationId, "B-" + index, SAME_NAME)
                    .setMasterObjectId(budgetObjectId)
                    .setRevisionNo(budgetRevision));
        }
        assertCode(
                () -> masterObjectService.addMappingEntry(entry(crmApplicationId, "B-OVER", SAME_NAME)
                        .setMasterObjectId(budgetObjectId)
                        .setRevisionNo(budgetRevision)),
                AiErrorCodeConstants.AI_MASTER_OBJECT_CATALOG_BUDGET_EXCEEDED);
    }

    /**
     * 判定必须显式：版本、判定时刻与可核验事实缺一不可；独立审核不可绕过。
     */
    @Test
    void judgementRequiresExplicitPinnedVersionAndIndependentReview() {
        // 对象不存在 / 版本不存在
        assertCode(
                () -> mappingResolver.resolveObjectKey(resolve("md_missing", crmApplicationId, "customer")),
                AiErrorCodeConstants.AI_MASTER_OBJECT_NOT_EXISTS);

        loginAs(1001L);
        Long revisionNo = masterObjectService.createRevision(
                new AiMasterRevisionDraftDTO().setMasterObjectId(cloudObjectId).setValidFrom(WINDOW_FROM));
        // 草稿创建人不能自己发布（独立审核）
        assertCode(
                () -> masterObjectService.publishRevision(cloudObjectId, revisionNo, 0),
                AiErrorCodeConstants.AI_MASTER_OBJECT_PUBLISHER_CONFLICT);
        loginAs(1002L);
        // 空版本不能发布（没有可核验内容，不能发布一个"什么都没有"的版本）
        assertCode(
                () -> masterObjectService.publishRevision(cloudObjectId, revisionNo, 0),
                AiErrorCodeConstants.AI_MASTER_MAPPING_ENTRY_INVALID);
        loginAs(1001L);
        masterObjectService.addMappingEntry(entry(crmApplicationId, "C-1001", SAME_NAME)
                .setMasterObjectId(cloudObjectId)
                .setRevisionNo(revisionNo));
        loginAs(1002L);
        // 乐观锁：用过期版本发布被拒
        assertCode(
                () -> masterObjectService.publishRevision(cloudObjectId, revisionNo, 7),
                AiErrorCodeConstants.AI_STATE_CONFLICT);
        masterObjectService.publishRevision(cloudObjectId, revisionNo, 0);

        // 该对象在该系统里没有登记：未映射（404），而不是"返回空对象"
        assertCode(
                () -> mappingResolver.resolveObjectKey(resolve("md_cloud_qi", erpApplicationId, "customer")),
                AiErrorCodeConstants.AI_MASTER_MAPPING_NOT_EXISTS);
        // 未登记的实体类型同样不猜测
        assertCode(
                () -> mappingResolver.resolveObjectKey(resolve("md_cloud_qi", crmApplicationId, "order")),
                AiErrorCodeConstants.AI_MASTER_MAPPING_NOT_EXISTS);
        // 判定时刻必填（平台不隐式取"服务器现在"）
        assertCode(
                () -> mappingResolver.resolveObjectKey(new AiMasterMappingResolveDTO()
                        .setObjectCode("md_cloud_qi")
                        .setRevisionNo(revisionNo)
                        .setApplicationId(crmApplicationId)
                        .setEntityType("customer")),
                AiErrorCodeConstants.AI_REQUEST_INVALID);
        // 停用对象：一切判定阻断（用当前乐观锁版本，避免把 409 误读成停用生效）
        masterObjectService.updateObjectStatus(
                cloudObjectId, masterObjectService.getObject(cloudObjectId).getVersion(), false);
        assertCode(
                () -> mappingResolver.resolveObjectKey(resolve("md_cloud_qi", crmApplicationId, "customer")),
                AiErrorCodeConstants.AI_MASTER_OBJECT_DISABLED_CONFLICT);
        // 映射条目登记必须给出源键与匹配方式（不按同名推断）
        loginAs(1001L);
        Long auditRevision = masterObjectService.createRevision(
                new AiMasterRevisionDraftDTO().setMasterObjectId(cloudObjectId).setValidFrom(WINDOW_FROM));
        AiMasterMappingEntrySaveDTO withoutKey = entry(crmApplicationId, "C-5001", SAME_NAME)
                .setMasterObjectId(cloudObjectId)
                .setRevisionNo(auditRevision)
                .setSourceKey(null);
        assertCode(
                () -> masterObjectService.addMappingEntry(withoutKey),
                AiErrorCodeConstants.AI_MASTER_MAPPING_ENTRY_INVALID);
        assertCode(
                () -> masterObjectService.addMappingEntry(entry(crmApplicationId, "C-5001", SAME_NAME)
                        .setMasterObjectId(cloudObjectId)
                        .setRevisionNo(auditRevision)
                        .setMatchMethod("NAME_SIMILARITY")),
                AiErrorCodeConstants.AI_MASTER_MAPPING_ENTRY_INVALID);
    }

    private AiMasterMappingResolveDTO resolve(String objectCode, Long applicationId, String entityType) {
        return new AiMasterMappingResolveDTO()
                .setObjectCode(objectCode)
                .setRevisionNo(1L)
                .setApplicationId(applicationId)
                .setEntityType(entityType)
                .setAsOf(AS_OF);
    }

    /** 反查并断言"没有映射到该对象"（未映射即不关联）。 */
    private boolean reverse(String objectCode, Long applicationId, String sourceKey) {
        AiMasterMappingReverseResultDTO result =
                mappingResolver.resolveSourceKey(reverseRequest(applicationId, sourceKey));
        return result.isMapped() && objectCode.equals(result.getObjectCode());
    }

    private static AiMasterMappingReverseDTO reverseRequest(Long applicationId, String sourceKey) {
        return new AiMasterMappingReverseDTO()
                .setApplicationId(applicationId)
                .setEntityType("customer")
                .setSourceKey(sourceKey)
                .setAsOf(AS_OF);
    }

    private AiMasterObjectCatalogDTO discover(Long objectId, Long revisionNo, String externalUserId) {
        return catalogService.discover(new AiMasterObjectCatalogQueryDTO()
                .setObjectCode(masterObjectService.getObject(objectId).getObjectCode())
                .setRevisionNo(revisionNo)
                .setApplicationId(crmApplicationId)
                .setSubjectType(AiSubjectType.USER.name())
                .setExternalUserId(externalUserId)
                .setAsOf(AS_OF));
    }

    /** 草稿登记 + 独立审核发布的完整链路（1001 登记，1002 发布）。 */
    private void registerAndPublish(Long objectId, AiMasterMappingEntrySaveDTO... entries) {
        loginAs(1001L);
        Long revisionNo = masterObjectService.createRevision(
                new AiMasterRevisionDraftDTO().setMasterObjectId(objectId).setValidFrom(WINDOW_FROM));
        for (AiMasterMappingEntrySaveDTO saveDTO : entries) {
            masterObjectService.addMappingEntry(
                    saveDTO.setMasterObjectId(objectId).setRevisionNo(revisionNo));
        }
        loginAs(1002L);
        masterObjectService.publishRevision(objectId, revisionNo, 0);
    }

    private static AiMasterMappingEntrySaveDTO entry(Long applicationId, String sourceKey, String sourceName) {
        return new AiMasterMappingEntrySaveDTO()
                .setApplicationId(applicationId)
                .setEntityType("customer")
                .setSourceKey(sourceKey)
                .setSourceName(sourceName)
                .setMatchMethod("MANUAL")
                .setValidFrom(WINDOW_FROM);
    }

    private Long createObject(String objectCode, String objectName) {
        loginAs(1001L);
        return masterObjectService.createObject(new AiMasterObjectSaveDTO()
                .setObjectCode(objectCode)
                .setObjectName(objectName)
                .setObjectType("CUSTOMER")
                .setDescription("Y02 合成跨系统夹具"));
    }

    private Long createApplication(String appCode, String name) {
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(appCode)
                .setName(name)
                .setOrigins(List.of("https://crm.example.com")));
        applicationService.updateStatus(issue.getApplication().getId(), 0, true);
        return issue.getApplication().getId();
    }

    private void loginAs(Long operatorId) {
        LoginUser loginUser = new LoginUser().setId(operatorId).setUserType(UserTypeEnum.ADMIN.getValue());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    private void cleanUpData() {
        jdbcTemplate.update("DELETE FROM ai_master_object_mapping");
        jdbcTemplate.update("DELETE FROM ai_master_object_revision");
        jdbcTemplate.update("DELETE FROM ai_master_object");
        for (String appCode : List.of(CRM_APP, ERP_APP)) {
            List<Long> appIds =
                    jdbcTemplate.queryForList("SELECT id FROM ai_application WHERE app_code = ?", Long.class, appCode);
            for (Long appId : appIds) {
                jdbcTemplate.update("DELETE FROM ai_resource_grant WHERE application_id = ?", appId);
                jdbcTemplate.update("DELETE FROM ai_subject WHERE application_id = ?", appId);
                jdbcTemplate.update("DELETE FROM ai_access_ticket WHERE application_id = ?", appId);
                jdbcTemplate.update("DELETE FROM ai_application_credential WHERE application_id = ?", appId);
                jdbcTemplate.update("DELETE FROM ai_application WHERE id = ?", appId);
            }
        }
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, ErrorCode expected) {
        assertThatThrownBy(callable)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(expected.getCode());
    }
}
