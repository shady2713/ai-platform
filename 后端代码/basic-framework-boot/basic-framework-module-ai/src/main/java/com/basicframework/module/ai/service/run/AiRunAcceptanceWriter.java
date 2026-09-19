package com.basicframework.module.ai.service.run;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunIdempotencyDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunTaskDO;
import com.basicframework.module.ai.dal.mysql.run.AiRunIdempotencyMapper;
import com.basicframework.module.ai.dal.mysql.run.AiRunMapper;
import com.basicframework.module.ai.dal.mysql.run.AiRunTaskMapper;
import com.basicframework.module.ai.service.conversation.AiConversationSubject;
import com.basicframework.module.ai.service.run.dto.AiRunAcceptDTO;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceRunSnapshotDTO;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运行受理写入器（O02）：把"幂等记录 + 运行 + 首任务"放在**同一个事务**里建立。
 *
 * <p>独立成一个 Bean 的原因：受理的并发冲突处理需要"内层事务失败后在外层回读赢家记录"。
 * 若把回读放在同一个事务里，REPEATABLE READ 的快照看不到并发赢家刚提交的行，
 * 因此内层事务只负责写入与抛冲突，回读与摘要比较由 {@link AiRunServiceImpl} 在事务之外完成。
 */
@Service
@RequiredArgsConstructor
public class AiRunAcceptanceWriter {

    /** 首任务的默认最大尝试次数（达到后置 FAILED，不再重试）。 */
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final AiRunMapper runMapper;

    private final AiRunIdempotencyMapper idempotencyMapper;

    private final AiRunTaskMapper taskMapper;

    /** 事务内建立幂等记录、运行与首任务；任一步失败整体回滚，不留半成品。 */
    @Transactional(rollbackFor = Exception.class)
    public AiRunDO create(
            AiConversationSubject subject,
            AiRunAcceptDTO acceptDTO,
            String inputDigest,
            AiServiceRunSnapshotDTO snapshot,
            String runKey) {
        AiRunDO run = new AiRunDO()
                .setRunKey(runKey)
                .setApplicationId(subject.applicationId())
                .setSubjectType(subject.subjectTypeName())
                .setExternalUserId(subject.externalUserId())
                .setConversationId(acceptDTO.getConversationId())
                .setServiceId(acceptDTO.getServiceId())
                .setReleaseId(snapshot.getRelease().getId())
                .setModelEndpointId(snapshot.getPin().getModelEndpointId())
                .setEndpointConfigRevision(snapshot.getPin().getModelRevision())
                .setContentHash(snapshot.getPin().getContentHash())
                .setInputDigest(inputDigest)
                .setStatus(AiRunDO.STATUS_ACCEPTED)
                .setStepCount(0)
                .setVersion(0);
        runMapper.insert(run);
        idempotencyMapper.insert(new AiRunIdempotencyDO()
                .setApplicationId(subject.applicationId())
                .setSubjectType(subject.subjectTypeName())
                .setExternalUserId(subject.externalUserId())
                .setIdempotencyKey(acceptDTO.getIdempotencyKey())
                .setRequestDigest(inputDigest)
                .setRunId(run.getId())
                .setVersion(0));
        taskMapper.insert(new AiRunTaskDO()
                .setRunId(run.getId())
                .setTaskKind(AiRunTaskDO.KIND_RUN_STEP)
                .setStatus(AiRunTaskDO.STATUS_QUEUED)
                .setAttemptCount(0)
                // 立即可以领取：用 NULL 而不是"当前时间"，避免 JVM 与数据库时钟偏差把任务挡在重试等待里
                .setNextAttemptTime(null)
                .setPayloadDigest(inputDigest)
                .setClaimedEpoch(0)
                .setMaxAttempts(DEFAULT_MAX_ATTEMPTS)
                .setVersion(0));
        return run;
    }

    /** 生成运行业务键（run_ 前缀 + 24 位随机标识；唯一键冲突由受理层回读处理）。 */
    public static String newRunKey() {
        return "run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    /** 当前主体的运行分页（读取不需要事务边界，直接复用运行 Mapper）。 */
    public PageResult<AiRunDO> page(PageParam pageParam, AiConversationSubject subject) {
        return runMapper.selectPageBySubject(
                pageParam, subject.applicationId(), subject.subjectTypeName(), subject.externalUserId());
    }
}
