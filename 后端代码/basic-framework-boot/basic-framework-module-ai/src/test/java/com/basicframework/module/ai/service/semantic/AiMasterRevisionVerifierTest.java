package com.basicframework.module.ai.service.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectMappingDO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectRevisionDO;
import com.basicframework.module.ai.dal.mysql.semantic.AiMasterObjectMappingMapper;
import com.basicframework.module.ai.dal.mysql.semantic.AiMasterObjectRevisionMapper;
import com.basicframework.module.ai.domain.semantic.AiMasterMappingFacts;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Y02 版本可核验读取：存在性、已发布、版本有效期与**内容指纹重算**四道检查。
 *
 * <p>指纹检查是关键：版本发布后被版本外改动（直连数据库改源键）时，判定与目录都必须阻断，
 * 而不是按被改过的内容解释历史报表。
 */
class AiMasterRevisionVerifierTest {

    private static final long OBJECT_ID = 5L;

    private static final long REVISION_NO = 2L;

    private static final LocalDateTime JAN = LocalDateTime.of(2026, 1, 1, 0, 0);

    private AiMasterObjectRevisionMapper revisionMapper;

    private AiMasterObjectMappingMapper mappingMapper;

    private AiMasterRevisionVerifier verifier;

    @BeforeEach
    void setUp() {
        revisionMapper = mock(AiMasterObjectRevisionMapper.class);
        mappingMapper = mock(AiMasterObjectMappingMapper.class);
        verifier = new AiMasterRevisionVerifier(revisionMapper, mappingMapper);
    }

    @Test
    void rejectsMissingDraftAndExpiredRevisions() {
        when(revisionMapper.selectByRevisionNo(OBJECT_ID, REVISION_NO)).thenReturn(null);
        assertCode(AiErrorCodeConstants.AI_MASTER_OBJECT_REVISION_NOT_EXISTS);

        when(revisionMapper.selectByRevisionNo(OBJECT_ID, REVISION_NO)).thenReturn(revision("DRAFT", JAN, null, "x"));
        assertCode(AiErrorCodeConstants.AI_MASTER_OBJECT_REVISION_NOT_PUBLISHED_CONFLICT);

        // 版本有效期不覆盖判定时刻：阻断，不回退到别的版本
        when(revisionMapper.selectByRevisionNo(OBJECT_ID, REVISION_NO))
                .thenReturn(revision("PUBLISHED", JAN, JAN.plusDays(1), "x"));
        assertCode(AiErrorCodeConstants.AI_MASTER_OBJECT_REVISION_EXPIRED_CONFLICT);

        when(revisionMapper.selectByRevisionNo(null, REVISION_NO)).thenReturn(null);
        assertThatThrownBy(() -> verifier.verify(null, REVISION_NO, JAN)).isInstanceOf(ServiceException.class);
    }

    @Test
    void recomputesFingerprintAndRejectsTamperedContent() {
        when(revisionMapper.selectByRevisionNo(OBJECT_ID, REVISION_NO))
                .thenReturn(revision("PUBLISHED", JAN, null, "frozen-but-wrong"));
        when(mappingMapper.selectByRevision(OBJECT_ID, REVISION_NO)).thenReturn(List.of(entry("C-1001")));

        assertCode(AiErrorCodeConstants.AI_MASTER_OBJECT_REVISION_FINGERPRINT_CONFLICT);
    }

    @Test
    void returnsRevisionWithVerifiedLines() {
        List<AiMasterObjectMappingDO> entries = List.of(entry("C-1001"), entry("C-1002"));
        String fingerprint = AiMasterMappingFacts.fingerprint(
                entries.stream().map(AiMasterObjectServiceImpl::toLine).toList());
        when(revisionMapper.selectByRevisionNo(OBJECT_ID, REVISION_NO))
                .thenReturn(revision("PUBLISHED", JAN, null, fingerprint));
        when(mappingMapper.selectByRevision(OBJECT_ID, REVISION_NO)).thenReturn(entries);

        AiMasterRevisionVerifier.VerifiedRevision verified = verifier.verify(OBJECT_ID, REVISION_NO, JAN.plusDays(1));

        assertThat(verified.revision().getRevisionNo()).isEqualTo(REVISION_NO);
        assertThat(verified.lines()).hasSize(2);
        assertThat(verified.lines().get(0).sourceKey()).isEqualTo("C-1001");
        assertThat(verified.lines()).isUnmodifiable();
    }

    private void assertCode(com.basicframework.framework.common.exception.ErrorCode expected) {
        assertThatThrownBy(() -> verifier.verify(OBJECT_ID, REVISION_NO, JAN.plusDays(2)))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(expected.getCode());
    }

    private static AiMasterObjectRevisionDO revision(
            String status, LocalDateTime validFrom, LocalDateTime validTo, String fingerprint) {
        return new AiMasterObjectRevisionDO()
                .setId(1L)
                .setMasterObjectId(OBJECT_ID)
                .setRevisionNo(REVISION_NO)
                .setStatus(status)
                .setValidFrom(validFrom)
                .setValidTo(validTo)
                .setMappingFingerprint(fingerprint);
    }

    private static AiMasterObjectMappingDO entry(String sourceKey) {
        return new AiMasterObjectMappingDO()
                .setId(1L)
                .setMasterObjectId(OBJECT_ID)
                .setRevision(REVISION_NO)
                .setApplicationId(7L)
                .setEntityType("customer")
                .setSourceKey(sourceKey)
                .setSourceName("展示名")
                .setMatchMethod("MANUAL")
                .setValidFrom(JAN)
                .setVersion(0);
    }
}
