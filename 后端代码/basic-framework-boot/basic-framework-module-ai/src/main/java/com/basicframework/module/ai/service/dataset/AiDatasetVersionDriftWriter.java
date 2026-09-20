package com.basicframework.module.ai.service.dataset;

import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetVersionDO;
import com.basicframework.module.ai.dal.mysql.dataset.AiDatasetVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 漂移标记写入器（D04）：把"发布时发现上游已漂移"的结论**独立提交**。
 *
 * <p>为什么单独一个 Bean：发布失败要抛错回滚业务事务，但"版本已漂移"这个事实必须留下——
 * 否则调用方看到的仍是 VERIFIED，下一次发布还会走到同一个失败点。
 * 独立事务（REQUIRES_NEW）保证标记不被回滚，与 O04 的终态写入同一做法。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiDatasetVersionDriftWriter {

    private final AiDatasetVersionMapper versionMapper;

    /** 标记版本为已漂移（CAS 失败说明并发已改写，按尽力而为处理）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markDrifted(Long versionId, Integer expectedVersion, String driftSummary) {
        if (versionId == null || expectedVersion == null) {
            return;
        }
        int updated = versionMapper.updateWithVersion(
                new AiDatasetVersionDO()
                        .setId(versionId)
                        .setVerificationStatus(AiDatasetVersionDO.VERIFICATION_DRIFTED)
                        .setDriftJson(driftSummary)
                        .setVersion(expectedVersion + 1),
                expectedVersion);
        if (updated == 0) {
            log.warn("漂移标记未生效（并发改写）：versionId={}", versionId);
        }
    }
}
