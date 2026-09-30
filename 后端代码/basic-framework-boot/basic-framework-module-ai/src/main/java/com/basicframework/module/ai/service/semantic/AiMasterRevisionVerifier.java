package com.basicframework.module.ai.service.semantic;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MASTER_OBJECT_REVISION_EXPIRED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MASTER_OBJECT_REVISION_FINGERPRINT_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MASTER_OBJECT_REVISION_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MASTER_OBJECT_REVISION_NOT_PUBLISHED_CONFLICT;

import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectRevisionDO;
import com.basicframework.module.ai.dal.mysql.semantic.AiMasterObjectMappingMapper;
import com.basicframework.module.ai.dal.mysql.semantic.AiMasterObjectRevisionMapper;
import com.basicframework.module.ai.domain.semantic.AiMasterMappingFacts;
import com.basicframework.module.ai.domain.semantic.AiMasterMappingLine;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 映射版本的**可核验读取**（Y02）：判定与目录共用的唯一入口。
 *
 * <p>四条检查按顺序执行，任何一条不通过都阻断（而不是降级读别的版本）：
 * <ol>
 *   <li>版本存在（否则 404）；</li>
 *   <li>版本已发布（草稿不是可核验事实，409）；</li>
 *   <li>版本有效期覆盖判定时刻（409）；</li>
 *   <li>版本内容重算指纹等于发布时冻结的指纹（不等即 409：内容被版本外改动，
 *       此时"按这个版本解释历史报表"已经不安全）。</li>
 * </ol>
 *
 * <p>把这段逻辑收敛在一处，是为了让"判定"和"目录发现"不可能对同一版本给出不同的事实。
 */
@Component
@RequiredArgsConstructor
public class AiMasterRevisionVerifier {

    private final AiMasterObjectRevisionMapper revisionMapper;

    private final AiMasterObjectMappingMapper mappingMapper;

    /** 读取并核验已发布版本，返回其映射事实（版本头 + 条目事实）。 */
    public VerifiedRevision verify(Long masterObjectId, Long revisionNo, LocalDateTime asOf) {
        AiMasterObjectRevisionDO revision = masterObjectId == null || revisionNo == null
                ? null
                : revisionMapper.selectByRevisionNo(masterObjectId, revisionNo);
        if (revision == null) {
            throw exception(AI_MASTER_OBJECT_REVISION_NOT_EXISTS);
        }
        if (!AiMasterObjectRevisionDO.STATUS_PUBLISHED.equals(revision.getStatus())) {
            throw exception(AI_MASTER_OBJECT_REVISION_NOT_PUBLISHED_CONFLICT);
        }
        if (!AiMasterMappingFacts.inForce(revision.getValidFrom(), revision.getValidTo(), asOf)) {
            throw exception(AI_MASTER_OBJECT_REVISION_EXPIRED_CONFLICT);
        }
        List<AiMasterMappingLine> lines = mappingMapper.selectByRevision(masterObjectId, revisionNo).stream()
                .map(AiMasterObjectServiceImpl::toLine)
                .toList();
        if (!AiMasterMappingFacts.fingerprint(lines).equals(revision.getMappingFingerprint())) {
            throw exception(AI_MASTER_OBJECT_REVISION_FINGERPRINT_CONFLICT);
        }
        return new VerifiedRevision(revision, lines);
    }

    /** 已核验版本：版本头 + 判定事实（指纹已通过复核）。 */
    public record VerifiedRevision(AiMasterObjectRevisionDO revision, List<AiMasterMappingLine> lines) {

        public VerifiedRevision {
            lines = List.copyOf(lines);
        }
    }
}
