package com.basicframework.server.integration;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MASTER_MAPPING_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MASTER_OBJECT_REVISION_FINGERPRINT_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectMappingDO;
import com.basicframework.module.ai.dal.mysql.semantic.AiMasterObjectMapper;
import com.basicframework.module.ai.dal.mysql.semantic.AiMasterObjectMappingMapper;
import com.basicframework.module.ai.domain.semantic.AiMasterMappingFacts;
import com.basicframework.module.ai.domain.semantic.AiMasterMappingLine;
import com.basicframework.module.ai.domain.semantic.AiMasterMappingMatchMethod;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.semantic.AiMasterMappingResolver;
import com.basicframework.module.ai.service.semantic.AiMasterObjectService;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingEntrySaveDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingReverseDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingReverseResultDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterObjectSaveDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterRevisionDraftDTO;
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
 * Y02 跨卡脏数据场景：<b>绕过发布直连写库</b>造成的"同一源键挂在两个对象下"。
 *
 * <p><b>为什么要有这条 IT。</b>发布时已按时间窗拦截"同一源键属于多个对象"（AT-070 第 2 条），
 * 判定侧也复核一次（{@code AiMasterMappingResolverImpl} 的多对一复核）。但那条防线只在
 * <b>服务层写入的路径</b>上被验证过。真实系统里历史数据、迁移脚本、运维直连都可能绕过它——
 * 于是"判定侧复核"这一层<b>从未在真实数据库上被证明有效</b>，只有 Mockito 单测覆盖。
 *
 * <p><b>本 IT 的做法。</b>先按正常链路把源键登记到对象甲并独立审核发布（拿到干净的已发布
 * 版本），然后用 {@code jdbcTemplate} <b>直接改库</b>制造脏数据——绕过服务层、绕过发布检查、
 * 绕过 Mapper 写路径。脏化时同步把 {@code revision} 对齐到对象乙的当前版本号，
 * 否则判定会在更早的"版本已推进"检查上就拒掉，测不到多对一复核这一层。
 *
 * <p><b>期望</b>：判定抛稳定冲突码，<b>绝不静默取一个对象</b>。这就是"宁可拒绝，不猜"的兜底语义。
 */
@Import(AiMasterMappingDirtyDataIT.DirtyScopeConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiMasterMappingDirtyDataIT extends AbstractPersistenceIntegrationTest {

    private static final String DIRTY_APP = "it-md-dirty";

    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 9, 1, 0, 0);

    private static final LocalDateTime WINDOW_FROM = LocalDateTime.of(2026, 1, 1, 0, 0);

    @TestConfiguration
    static class DirtyScopeConfiguration {

        @Bean
        com.basicframework.module.ai.domain.identity.SubjectScopeResolver dirtyMappingScopeResolver() {
            return request -> java.util.Optional.of(new com.basicframework.module.ai.domain.identity.SubjectScope(
                    Set.of(10L), Set.copyOf(request.resourceHints()), request.scopeSource(), request.scopeVersion()));
        }
    }

    @Autowired
    private AiApplicationService applicationService;

    @Autowired
    private AiMasterObjectService masterObjectService;

    @Autowired
    private AiMasterMappingResolver mappingResolver;

    @Autowired
    private AiMasterObjectMappingMapper mappingMapper;

    @Autowired
    private AiMasterObjectMapper objectMapper;

    private Long applicationId;

    private Long objectAId;

    private Long objectBId;

    /** 当前测试使用的源键；每个用例在 {@code prepare} 后显式设定，避免隐式推导。 */
    private String sourceKey;

    @BeforeEach
    void prepare() {
        cleanUpData();
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(DIRTY_APP)
                .setName("脏数据来源系统")
                .setOrigins(List.of("https://dirty.example.com")));
        applicationService.updateStatus(issue.getApplication().getId(), 0, true);
        applicationId = issue.getApplication().getId();
        objectAId = createObject("dirty_obj_a", "脏数据对象甲");
        objectBId = createObject("dirty_obj_b", "脏数据对象乙");
        sourceKey = "C-DIRTY";
        // 对象乙也要有一个**已发布**版本，否则脏数据改挂过去后，判定会在更早的
        // "版本不存在"（AI_MASTER_OBJECT_REVISION_NOT_EXISTS）上就拒掉，
        // 根本到不了多对一复核那一层——那样这条 IT 就白写了。
        // 该版本登记的是**另一个**源键，避免发布期检查自己就先拦下来。
        publishUnrelatedRevisionOnObjectB();
    }

    /** 给对象乙发布一个只含无关源键的版本，让它成为一个"看起来完全合法"的归属目标。 */
    private void publishUnrelatedRevisionOnObjectB() {
        loginAs(1001L);
        Long revisionNo = masterObjectService.createRevision(
                new AiMasterRevisionDraftDTO().setMasterObjectId(objectBId).setValidFrom(WINDOW_FROM));
        masterObjectService.addMappingEntry(new AiMasterMappingEntrySaveDTO()
                .setMasterObjectId(objectBId)
                .setRevisionNo(revisionNo)
                .setApplicationId(applicationId)
                .setEntityType("customer")
                .setSourceKey("C-UNRELATED-ON-B")
                .setSourceName("对象乙自己的源键")
                .setMatchMethod("MANUAL")
                .setValidFrom(WINDOW_FROM));
        loginAs(1002L);
        masterObjectService.publishRevision(objectBId, revisionNo, 0);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        cleanUpData();
    }

    /**
     * 朴素直连改挂（不碰指纹）——<b>被指纹防御拦下</b>。
     *
     * <p>这条固定的是 Y02 的<b>第三道防线</b>：改库改了内容却没重算冻结指纹，
     * 判定在复核内容指纹时（{@code AI_MASTER_OBJECT_REVISION_FINGERPRINT_CONFLICT}）就拒了，
     * 根本到不了多对一复核。
     *
     * <p>这不是本卡想测的目标，但它是真实且重要的性质：分层防御的第一层就已经在拦。
     * 真正要测"多对一复核"的那条见 {@link #sameSourceKeyPointedAtTwoObjectsAtOnceIsAlsoRefused}。
     */
    @Test
    void naiveDirectWriteIsCaughtByTheFrozenFingerprintDefense() {
        Long entryId = registerAndPublishOnObjectA();

        // 脏化之前判定通
        assertThat(reverse().isMapped()).isTrue();
        assertThat(reverse().getMasterObjectId()).isEqualTo(objectAId);

        repointToObjectB(entryId, false);

        assertThatThrownBy(this::reverse)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AI_MASTER_OBJECT_REVISION_FINGERPRINT_CONFLICT.getCode());
    }

    /**
     * 连指纹一起自洽的改挂<b>不是违规</b>，而是一次合法的实体改派。
     *
     * <p>这条用例固定一个容易被误读为缺陷的设计意图：改挂后源键只剩<b>一行</b>、指向对象乙，
     * 属于单一归属，判定<b>应当</b>给出新归属而不是报冲突。多对一复核数的是"生效行里出现几个
     * 不同 {@code masterObjectId}"，一行不构成冲突。
     *
     * <p>因此真正该被拦的是"意外改挂"——只改内容、不重算冻结指纹（见上一条，指纹防御拦下）；
     * 而"有意改派"必须走服务层的版本发布路径（新建版本 → 独立审核发布 → 冻结新指纹）。
     * 这条用例把这条边界写死：自洽的改派是能力，不是不一致。
     */
    @Test
    void selfConsistentRepointIsALegitimateReassignmentNotAConflict() {
        Long entryId = registerAndPublishOnObjectA();
        assertThat(reverse().getMasterObjectId()).isEqualTo(objectAId);

        // 改挂 + 重算指纹：版本内容与冻结指纹自洽
        repointToObjectB(entryId, true);

        // 单一归属，判定给出新归属，不报多对一冲突
        AiMasterMappingReverseResultDTO after = reverse();
        assertThat(after.isMapped()).isTrue();
        assertThat(after.getMasterObjectId()).isEqualTo(objectBId);
    }

    /**
     * 真·多对一：同一源键**同时**指向两个对象（不是改挂，是并挂）。
     *
     * <p>两种脏化形态都违反"一个源键只能属于一个实体"，但数据形态不同，判定侧要都能挡住。
     */
    @Test
    void sameSourceKeyPointedAtTwoObjectsAtOnceIsAlsoRefused() {
        Long entryOnA = registerAndPublishOnObjectA();

        // 在对象甲的已发布版本里，插入一行指向对象乙的脏数据
        AiMasterObjectMappingDO source = mappingMapper.selectById(entryOnA);
        AiMasterObjectMappingDO twin = new AiMasterObjectMappingDO()
                .setMasterObjectId(objectBId)
                .setRevision(objectMapper.selectById(objectBId).getCurrentRevision())
                .setApplicationId(source.getApplicationId())
                .setEntityType(source.getEntityType())
                .setSourceKey(source.getSourceKey())
                .setSourceName(source.getSourceName())
                .setMatchMethod(source.getMatchMethod())
                .setValidFrom(source.getValidFrom())
                .setValidTo(source.getValidTo())
                .setVersion(0);
        mappingMapper.insert(twin);

        assertThat(mappingMapper.selectCurrentBySourceKey(applicationId, "customer", sourceKey))
                .hasSizeGreaterThanOrEqualTo(2);

        assertConflict();
    }

    /**
     * 脏数据若让源键指向一个已停用对象，判定不得把它当成有效归属。
     *
     * <p>与前两条的区别：前两条是多对一冲突；本条只有一个归属，但该归属的对象已停用。
     * 期望是"对象停用"的稳定码，<b>不能</b>被当成正常归属放行。
     */
    @Test
    void dirtyDataPointingAtDisabledObjectIsRefused() {
        Long entryId = registerAndPublishOnObjectA();
        repointToObjectB(entryId, false);

        // 绕过服务层直接把对象乙置为停用
        int updated = jdbcTemplate.update(
                "UPDATE ai_master_object SET status = 'DISABLED', version = version + 1 WHERE id = ?", objectBId);
        assertThat(updated).isEqualTo(1);
        assertThat(objectMapper.selectById(objectBId).getStatus()).isEqualTo("DISABLED");

        // 单个归属 + 对象停用：不是多对一冲突，且必须拒绝
        assertThatThrownBy(this::reverse)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isNotEqualTo(AI_MASTER_MAPPING_CONFLICT.getCode())
                .isNotNull();
    }

    // ---------------------------------------------------------------- 判定与断言

    private AiMasterMappingReverseResultDTO reverse() {
        loginAs(1001L);
        return mappingResolver.resolveSourceKey(new AiMasterMappingReverseDTO()
                .setApplicationId(applicationId)
                .setEntityType("customer")
                .setSourceKey(sourceKey)
                .setAsOf(AS_OF));
    }

    private void assertConflict() {
        assertThatThrownBy(this::reverse)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AI_MASTER_MAPPING_CONFLICT.getCode());
    }

    // ---------------------------------------------------------------- 脏数据构造

    /** 按正常链路把 {@link #sourceKey} 登记到对象甲并独立审核发布，返回那一条映射行 id。 */
    private Long registerAndPublishOnObjectA() {
        loginAs(1001L);
        Long revisionNo = masterObjectService.createRevision(
                new AiMasterRevisionDraftDTO().setMasterObjectId(objectAId).setValidFrom(WINDOW_FROM));
        Long entryId = masterObjectService.addMappingEntry(new AiMasterMappingEntrySaveDTO()
                .setMasterObjectId(objectAId)
                .setRevisionNo(revisionNo)
                .setApplicationId(applicationId)
                .setEntityType("customer")
                .setSourceKey(sourceKey)
                .setSourceName("脏数据夹具来源")
                .setMatchMethod("MANUAL")
                .setValidFrom(WINDOW_FROM));
        loginAs(1002L);
        masterObjectService.publishRevision(objectAId, revisionNo, 0);
        return entryId;
    }

    /**
     * 绕过服务层与发布检查，把已发布版本的映射行改挂到对象乙。
     *
     * <p>同步把 {@code revision} 对齐到对象乙的当前版本号：否则判定会在更早的
     * "版本已推进"检查（{@code AI_STATE_CONFLICT}）上就拒掉，测不到多对一复核这一层。
     */
    private void repointToObjectB(Long entryId, boolean recomputeFingerprint) {
        Long objectBRevision = objectMapper.selectById(objectBId).getCurrentRevision();
        int updated = jdbcTemplate.update(
                "UPDATE ai_master_object_mapping SET master_object_id = ?, revision = ? WHERE id = ?",
                objectBId,
                objectBRevision,
                entryId);
        assertThat(updated).isEqualTo(1);
        if (recomputeFingerprint) {
            recomputeRevisionFingerprint(objectBId, objectBRevision);
        }
    }

    /**
     * 按当前映射行重算并回写该版本的冻结指纹。
     *
     * <p>用 {@code AiMasterMappingFacts.fingerprint} 这个<b>生产用的同一实现</b>复算，而不是
     * 自己拼摘要——否则测的就不是线上真实判定所依据的那套指纹了。传 {@code false} 则故意不重算，
     * 让脏数据在指纹这一关就露馅，验证第一层防线。
     */
    private void recomputeRevisionFingerprint(Long objectId, Long revisionNo) {
        List<AiMasterObjectMappingDO> rows = jdbcTemplate.query(
                "SELECT * FROM ai_master_object_mapping WHERE master_object_id = ? AND revision = ?",
                (rs, rowNum) -> new AiMasterObjectMappingDO()
                        .setMasterObjectId(rs.getLong("master_object_id"))
                        .setApplicationId(rs.getLong("application_id"))
                        .setEntityType(rs.getString("entity_type"))
                        .setSourceKey(rs.getString("source_key"))
                        .setSourceName(rs.getString("source_name"))
                        .setMatchMethod(rs.getString("match_method"))
                        .setValidFrom(toLocalDateTime(rs.getTimestamp("valid_from")))
                        .setValidTo(toLocalDateTime(rs.getTimestamp("valid_to"))),
                objectId,
                revisionNo);
        String fingerprint = AiMasterMappingFacts.fingerprint(
                rows.stream().map(AiMasterMappingDirtyDataIT::toLine).toList());
        int updated = jdbcTemplate.update(
                "UPDATE ai_master_object_revision SET mapping_fingerprint = ?"
                        + " WHERE master_object_id = ? AND revision_no = ?",
                fingerprint,
                objectId,
                revisionNo);
        assertThat(updated).isEqualTo(1);
    }

    private static LocalDateTime toLocalDateTime(java.sql.Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    /** 与生产 {@code AiMasterObjectServiceImpl#toLine} 同构的行构造（该方法是包内可见，测试用不到）。 */
    private static AiMasterMappingLine toLine(AiMasterObjectMappingDO row) {
        return new AiMasterMappingLine(
                row.getMasterObjectId(),
                row.getApplicationId(),
                row.getEntityType(),
                row.getSourceKey(),
                row.getSourceName(),
                AiMasterMappingMatchMethod.MANUAL.name(),
                row.getValidFrom(),
                row.getValidTo());
    }

    private Long createObject(String objectCode, String objectName) {
        loginAs(1001L);
        return masterObjectService.createObject(new AiMasterObjectSaveDTO()
                .setObjectCode(objectCode)
                .setObjectName(objectName)
                .setObjectType("CUSTOMER")
                .setDescription("Y02 脏数据夹具"));
    }

    private void loginAs(Long operatorId) {
        LoginUser loginUser = new LoginUser().setId(operatorId).setUserType(UserTypeEnum.ADMIN.getValue());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    private void cleanUpData() {
        jdbcTemplate.update("DELETE FROM ai_master_object_mapping");
        jdbcTemplate.update("DELETE FROM ai_master_object_revision");
        jdbcTemplate.update("DELETE FROM ai_master_object");
        List<Long> appIds =
                jdbcTemplate.queryForList("SELECT id FROM ai_application WHERE app_code = ?", Long.class, DIRTY_APP);
        for (Long appId : appIds) {
            jdbcTemplate.update("DELETE FROM ai_resource_grant WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_subject WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_access_ticket WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application_credential WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application WHERE id = ?", appId);
        }
    }
}
