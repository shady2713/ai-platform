package com.basicframework.module.ai.service.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectDO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectMappingDO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectRevisionDO;
import com.basicframework.module.ai.dal.mysql.semantic.AiMasterObjectMapper;
import com.basicframework.module.ai.dal.mysql.semantic.AiMasterObjectMappingMapper;
import com.basicframework.module.ai.domain.semantic.AiMasterMappingFacts;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingResolutionDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingResolveDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingReverseDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingReverseResultDTO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Y02 判定路径：命中唯一映射、未登记（不报错）与各类阻断（过期/冲突/未发布/指纹/停用）。
 *
 * <p>AT-070 的三条要求在这里逐条落地：未映射不关联（mapped=false）、过期与冲突阻断
 * （稳定错误码，不静默取一个）、判定按显式版本解释（结果携带冻结指纹）。
 */
class AiMasterMappingResolverImplTest {

    private static final long OBJECT_ID = 5L;

    private static final long APPLICATION_ID = 7L;

    private static final long OTHER_APPLICATION_ID = 8L;

    private static final long REVISION_NO = 2L;

    private static final LocalDateTime JAN = LocalDateTime.of(2026, 1, 1, 0, 0);

    private static final LocalDateTime JUN = LocalDateTime.of(2026, 6, 1, 0, 0);

    private AiMasterObjectMapper objectMapper;

    private AiMasterObjectMappingMapper mappingMapper;

    private AiMasterRevisionVerifier verifier;

    private AiMasterMappingResolverImpl resolver;

    @BeforeEach
    void setUp() {
        objectMapper = mock(AiMasterObjectMapper.class);
        mappingMapper = mock(AiMasterObjectMappingMapper.class);
        verifier = mock(AiMasterRevisionVerifier.class);
        resolver = new AiMasterMappingResolverImpl(objectMapper, mappingMapper, verifier);
        when(objectMapper.selectByCode("md_customer")).thenReturn(object("ACTIVE"));
        when(mappingMapper.selectCurrentBySourceKey(anyLong(), any(), any())).thenReturn(List.of());
    }

    @Test
    void rejectsIncompleteRequestsAndUnavailableObjects() {
        assertCode(
                () -> resolver.resolveObjectKey(new AiMasterMappingResolveDTO().setObjectCode("md_customer")),
                AiErrorCodeConstants.AI_REQUEST_INVALID);
        assertCode(() -> resolver.resolveObjectKey(resolve().setAsOf(null)), AiErrorCodeConstants.AI_REQUEST_INVALID);
        assertCode(
                () -> resolver.resolveObjectKey(resolve().setEntityType(" ")), AiErrorCodeConstants.AI_REQUEST_INVALID);
        assertCode(() -> resolver.resolveSourceKey(null), AiErrorCodeConstants.AI_REQUEST_INVALID);
        assertCode(
                () -> resolver.resolveSourceKey(new AiMasterMappingReverseDTO()
                        .setApplicationId(APPLICATION_ID)
                        .setEntityType("customer")
                        .setSourceKey("C-1001")
                        .setAsOf(null)),
                AiErrorCodeConstants.AI_REQUEST_INVALID);

        when(objectMapper.selectByCode("md_customer")).thenReturn(null);
        assertCode(() -> resolver.resolveObjectKey(resolve()), AiErrorCodeConstants.AI_MASTER_OBJECT_NOT_EXISTS);

        when(objectMapper.selectByCode("md_customer")).thenReturn(object("DISABLED"));
        assertCode(() -> resolver.resolveObjectKey(resolve()), AiErrorCodeConstants.AI_MASTER_OBJECT_DISABLED_CONFLICT);
    }

    @Test
    void resolveObjectKeyBlocksMissingExpiredAndConflictingEntries() {
        // 该对象在该系统里没有任何登记：未映射（调用方据此拒绝跨系统合并）
        verified(List.of(entry("E-9001", JAN, null, OTHER_APPLICATION_ID)));
        assertCode(() -> resolver.resolveObjectKey(resolve()), AiErrorCodeConstants.AI_MASTER_MAPPING_NOT_EXISTS);

        // 有登记但有效期都不覆盖判定时刻（已过期）：阻断
        verified(List.of(entry("C-1001", JAN, JUN)));
        assertCode(() -> resolver.resolveObjectKey(resolve()), AiErrorCodeConstants.AI_MASTER_MAPPING_EXPIRED_CONFLICT);

        // 一对多：同对象同系统同实体类型两条同时生效：阻断，不取第一个
        verified(List.of(entry("C-1001", JAN, null), entry("C-1002", JAN, null)));
        assertCode(() -> resolver.resolveObjectKey(resolve()), AiErrorCodeConstants.AI_MASTER_MAPPING_CONFLICT);

        // 多对一：同一源键在别的对象当前版本里同时生效：阻断
        verified(List.of(entry("C-1001", JAN, null)));
        when(mappingMapper.selectCurrentBySourceKey(APPLICATION_ID, "customer", "C-1001"))
                .thenReturn(List.of(entryRow(9L, 1L, "C-1001", APPLICATION_ID, JAN, null)));
        assertCode(() -> resolver.resolveObjectKey(resolve()), AiErrorCodeConstants.AI_MASTER_MAPPING_CONFLICT);
    }

    @Test
    void resolveObjectKeyReturnsPinnedVersionFacts() {
        List<AiMasterObjectMappingDO> entries = List.of(entry("C-1001", JAN, null));
        AiMasterObjectRevisionDO revision = verified(entries);

        AiMasterMappingResolutionDTO resolution = resolver.resolveObjectKey(resolve());

        assertThat(resolution.getObjectCode()).isEqualTo("md_customer");
        assertThat(resolution.getRevisionNo()).isEqualTo(REVISION_NO);
        assertThat(resolution.getRevisionFingerprint()).isEqualTo(revision.getMappingFingerprint());
        assertThat(resolution.getSourceKey()).isEqualTo("C-1001");
        assertThat(resolution.getMatchMethod()).isEqualTo("MANUAL");
        assertThat(resolution.getAsOf()).isEqualTo(JUN);
        assertThat(resolution.getApplicationId()).isEqualTo(APPLICATION_ID);
    }

    @Test
    void resolveSourceKeyReturnsUnmappedInsteadOfGuessing() {
        when(mappingMapper.selectCurrentBySourceKey(APPLICATION_ID, "customer", "C-1001"))
                .thenReturn(List.of());

        AiMasterMappingReverseResultDTO result = resolver.resolveSourceKey(new AiMasterMappingReverseDTO()
                .setApplicationId(APPLICATION_ID)
                .setEntityType("customer")
                .setSourceKey("C-1001")
                .setAsOf(JUN));

        assertThat(result.isMapped()).isFalse();
        assertThat(result.getReason()).isEqualTo(AiMasterMappingResolverImpl.REASON_NOT_REGISTERED);
        assertThat(result.getObjectCode()).isNull();
        assertThat(result.getRevisionNo()).isNull();
    }

    @Test
    void resolveSourceKeyBlocksExpiredDisabledAndConflictingFacts() {
        when(mappingMapper.selectCurrentBySourceKey(APPLICATION_ID, "customer", "C-1001"))
                .thenReturn(List.of(entryRow(1L, "C-1001", APPLICATION_ID, JAN, JUN)));
        // 已登记但已过期：阻断（不能退化成"未映射"）
        assertCode(() -> resolver.resolveSourceKey(reverse()), AiErrorCodeConstants.AI_MASTER_MAPPING_EXPIRED_CONFLICT);

        // 多对一：同一源键指向两个对象
        when(mappingMapper.selectCurrentBySourceKey(APPLICATION_ID, "customer", "C-1001"))
                .thenReturn(List.of(
                        entryRow(OBJECT_ID, 1L, "C-1001", APPLICATION_ID, JAN, null),
                        entryRow(99L, 2L, "C-1001", APPLICATION_ID, JAN, null)));
        assertCode(() -> resolver.resolveSourceKey(reverse()), AiErrorCodeConstants.AI_MASTER_MAPPING_CONFLICT);

        // 对象已删除 / 已停用
        when(mappingMapper.selectCurrentBySourceKey(APPLICATION_ID, "customer", "C-1001"))
                .thenReturn(List.of(entryRow(1L, "C-1001", APPLICATION_ID, JAN, null)));
        when(objectMapper.selectById(OBJECT_ID)).thenReturn(null);
        assertCode(() -> resolver.resolveSourceKey(reverse()), AiErrorCodeConstants.AI_MASTER_MAPPING_NOT_EXISTS);

        when(objectMapper.selectById(OBJECT_ID)).thenReturn(object("DISABLED"));
        assertCode(() -> resolver.resolveSourceKey(reverse()), AiErrorCodeConstants.AI_MASTER_OBJECT_DISABLED_CONFLICT);
    }

    @Test
    void resolveSourceKeyFailsClosedWhenFactsShiftDuringRead() {
        when(mappingMapper.selectCurrentBySourceKey(APPLICATION_ID, "customer", "C-1001"))
                .thenReturn(List.of(entryRow(1L, "C-1001", APPLICATION_ID, JAN, null)));
        // 对象当前版本已被推进到 3：候选行属于旧版本，两次读取不一致
        when(objectMapper.selectById(OBJECT_ID)).thenReturn(object("ACTIVE").setCurrentRevision(3L));
        assertCode(() -> resolver.resolveSourceKey(reverse()), AiErrorCodeConstants.AI_STATE_CONFLICT);

        // 版本已核验但内容里没有这条源键：事实矛盾，按未登记表达
        when(objectMapper.selectById(OBJECT_ID)).thenReturn(object("ACTIVE").setCurrentRevision(REVISION_NO));
        when(verifier.verify(eq(OBJECT_ID), eq(REVISION_NO), any()))
                .thenReturn(new AiMasterRevisionVerifier.VerifiedRevision(publishedRevision(), List.of()));
        assertCode(() -> resolver.resolveSourceKey(reverse()), AiErrorCodeConstants.AI_MASTER_MAPPING_NOT_EXISTS);
    }

    @Test
    void resolveSourceKeyReturnsMatchedObjectWithVersionFingerprint() {
        when(mappingMapper.selectCurrentBySourceKey(APPLICATION_ID, "customer", "C-1001"))
                .thenReturn(List.of(entryRow(1L, "C-1001", APPLICATION_ID, JAN, null)));
        when(objectMapper.selectById(OBJECT_ID)).thenReturn(object("ACTIVE").setCurrentRevision(REVISION_NO));
        AiMasterObjectRevisionDO revision = publishedRevision();
        when(verifier.verify(eq(OBJECT_ID), eq(REVISION_NO), any()))
                .thenReturn(new AiMasterRevisionVerifier.VerifiedRevision(
                        revision,
                        List.of(AiMasterObjectServiceImpl.toLine(entryRow(1L, "C-1001", APPLICATION_ID, JAN, null)))));

        AiMasterMappingReverseResultDTO result = resolver.resolveSourceKey(reverse());

        assertThat(result.isMapped()).isTrue();
        assertThat(result.getReason()).isNull();
        assertThat(result.getObjectCode()).isEqualTo("md_customer");
        assertThat(result.getRevisionNo()).isEqualTo(REVISION_NO);
        assertThat(result.getRevisionFingerprint()).isEqualTo(revision.getMappingFingerprint());
        assertThat(result.getSourceKey()).isEqualTo("C-1001");
        assertThat(result.getSourceName()).isEqualTo("杭州云启科技有限公司");
    }

    private AiMasterObjectRevisionDO verified(List<AiMasterObjectMappingDO> entries) {
        AiMasterObjectRevisionDO revision = publishedRevision();
        when(verifier.verify(eq(OBJECT_ID), eq(REVISION_NO), any()))
                .thenReturn(new AiMasterRevisionVerifier.VerifiedRevision(
                        revision,
                        entries.stream().map(AiMasterObjectServiceImpl::toLine).toList()));
        return revision;
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, ErrorCode expected) {
        assertThatThrownBy(callable)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(expected.getCode());
    }

    private static AiMasterMappingResolveDTO resolve() {
        return new AiMasterMappingResolveDTO()
                .setObjectCode("md_customer")
                .setRevisionNo(REVISION_NO)
                .setApplicationId(APPLICATION_ID)
                .setEntityType("customer")
                .setAsOf(JUN);
    }

    private static AiMasterMappingReverseDTO reverse() {
        return new AiMasterMappingReverseDTO()
                .setApplicationId(APPLICATION_ID)
                .setEntityType("customer")
                .setSourceKey("C-1001")
                .setAsOf(JUN);
    }

    private static AiMasterObjectDO object(String status) {
        return new AiMasterObjectDO()
                .setId(OBJECT_ID)
                .setObjectCode("md_customer")
                .setObjectName("企业客户")
                .setObjectType("CUSTOMER")
                .setStatus(status)
                .setCurrentRevision(REVISION_NO)
                .setVersion(3);
    }

    private static AiMasterObjectRevisionDO publishedRevision() {
        return new AiMasterObjectRevisionDO()
                .setId(11L)
                .setMasterObjectId(OBJECT_ID)
                .setRevisionNo(REVISION_NO)
                .setStatus(AiMasterObjectRevisionDO.STATUS_PUBLISHED)
                .setValidFrom(JAN)
                .setMappingFingerprint("frozen-fingerprint")
                .setEntryCount(1)
                .setVersion(1);
    }

    private static AiMasterObjectMappingDO entry(String sourceKey, LocalDateTime from, LocalDateTime to) {
        return entryRow(OBJECT_ID, 1L, sourceKey, APPLICATION_ID, from, to);
    }

    private static AiMasterObjectMappingDO entry(
            String sourceKey, LocalDateTime from, LocalDateTime to, Long applicationId) {
        return entryRow(OBJECT_ID, 1L, sourceKey, applicationId, from, to);
    }

    private static AiMasterObjectMappingDO entryRow(
            Long id, String sourceKey, Long applicationId, LocalDateTime from, LocalDateTime to) {
        return entryRow(OBJECT_ID, id, sourceKey, applicationId, from, to);
    }

    private static AiMasterObjectMappingDO entryRow(
            Long masterObjectId, Long id, String sourceKey, Long applicationId, LocalDateTime from, LocalDateTime to) {
        return new AiMasterObjectMappingDO()
                .setId(id)
                .setMasterObjectId(masterObjectId)
                .setRevision(REVISION_NO)
                .setApplicationId(applicationId)
                .setEntityType("customer")
                .setSourceKey(sourceKey)
                .setSourceName("杭州云启科技有限公司")
                .setMatchMethod("MANUAL")
                .setValidFrom(from)
                .setValidTo(to)
                .setVersion(0);
    }

    @Test
    void fingerprintOfVerifiedRevisionIsStableAcrossRepeatedReads() {
        // 版本固定：同一份事实重复读取得到同一指纹（换版本不会改旧结果的结构性前提）
        List<AiMasterObjectMappingDO> entries = List.of(entry("C-1001", JAN, null));
        AiMasterObjectRevisionDO revision = verified(entries);
        String first = resolver.resolveObjectKey(resolve()).getRevisionFingerprint();

        assertThat(first).isEqualTo(revision.getMappingFingerprint());
        assertThat(AiMasterMappingFacts.digest(first)).isEqualTo(AiMasterMappingFacts.digest(first));
    }
}
