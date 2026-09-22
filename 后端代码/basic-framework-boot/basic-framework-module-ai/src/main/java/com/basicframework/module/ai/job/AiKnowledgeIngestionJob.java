package com.basicframework.module.ai.job;

import com.basicframework.framework.quartz.core.handler.JobHandler;
import com.basicframework.module.ai.dal.dataobject.file.AiFileBindingDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentVersionDO;
import com.basicframework.module.ai.dal.mysql.file.AiFileBindingMapper;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeDocumentService;
import com.basicframework.module.ai.service.knowledge.ingestion.AiKnowledgeIngestionService;
import com.basicframework.module.ai.service.knowledge.ingestion.AiKnowledgeIngestionStep;
import com.basicframework.module.ai.service.knowledge.ingestion.AiKnowledgeIngestionTaskDO;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionStepOutcome;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionTaskLeaseDTO;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 知识入库 Job（K03）：领取任务 → 校验文件引用 → 推进文档状态 → 交给入库步骤 → 写终态。
 *
 * <p>职责边界（也是本卡不"假装成功"的地方）：
 * <ul>
 *   <li>本 Job 只做**编排**：文件引用是否仍有效、文档状态能否推进、任务终态如何写；</li>
 *   <li>解析（K04）与切分/向量化（K05）通过 {@link AiKnowledgeIngestionStep} 端口接入；
 *       当前没有实现时任务以稳定原因码 {@code parser-unavailable} 失败，文档与版本同样标失败——
 *       旧可用版本不受影响，但**不会**出现"看起来成功"的空结果；</li>
 *   <li>任何失败都只落脱敏原因码，不落解析异常正文。</li>
 * </ul>
 */
@Slf4j
@Component
public class AiKnowledgeIngestionJob implements JobHandler {

    /** 文件引用已失效（绑定被解除/文件被回收）时的稳定原因码。 */
    static final String REASON_FILE_UNAVAILABLE = "file-unavailable";

    /** 没有可用的入库步骤实现时的稳定原因码。 */
    static final String REASON_PARSER_UNAVAILABLE = "parser-unavailable";

    private final AiKnowledgeIngestionService ingestionService;

    private final AiKnowledgeDocumentService documentService;

    private final AiFileBindingMapper fileBindingMapper;

    private final ObjectProvider<AiKnowledgeIngestionStep> steps;

    /** worker 标识：实例内唯一（进程启动时生成），租约栅栏据此区分持有者。 */
    private final String workerId;

    private final int batchSize;

    private final int leaseSeconds;

    /** 构造器注入：配置与依赖都通过构造器传入。 */
    public AiKnowledgeIngestionJob(
            AiKnowledgeIngestionService ingestionService,
            AiKnowledgeDocumentService documentService,
            AiFileBindingMapper fileBindingMapper,
            ObjectProvider<AiKnowledgeIngestionStep> steps,
            @Value("${basic-framework.ai.knowledge.ingestion.worker-prefix:knowledge-ingestion}") String workerPrefix,
            @Value("${basic-framework.ai.knowledge.ingestion.batch-size:5}") int batchSize,
            @Value("${basic-framework.ai.knowledge.ingestion.lease-seconds:120}") int leaseSeconds) {
        this.ingestionService = ingestionService;
        this.documentService = documentService;
        this.fileBindingMapper = fileBindingMapper;
        this.steps = steps;
        this.workerId = workerPrefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
    }

    @Override
    public String execute(String param) {
        List<AiKnowledgeIngestionTaskLeaseDTO> leases =
                ingestionService.claim(workerId, Math.max(1, batchSize), Math.max(1, leaseSeconds));
        int succeeded = 0;
        int failed = 0;
        for (AiKnowledgeIngestionTaskLeaseDTO lease : leases) {
            if (process(lease)) {
                succeeded++;
            } else {
                failed++;
            }
        }
        if (!leases.isEmpty()) {
            log.info("[execute][知识入库任务 {} 条：成功 {}，失败 {}]", leases.size(), succeeded, failed);
        }
        return String.format("知识入库任务 %s 条（成功 %s，失败 %s）", leases.size(), succeeded, failed);
    }

    /** 处理一个任务；返回是否成功。 */
    private boolean process(AiKnowledgeIngestionTaskLeaseDTO lease) {
        AiKnowledgeDocumentVersionDO version = documentService.getVersion(lease.documentVersionId());
        if (!fileReferenceValid(version.getFileId())) {
            return fail(lease, REASON_FILE_UNAVAILABLE, version);
        }
        // 推进文档状态：排队 → 解析中（状态机不允许时忽略，例如已经是解析中）
        try {
            documentService.markParsing(
                    lease.documentId(),
                    documentService.getDocument(lease.documentId()).getVersion());
        } catch (RuntimeException ignored) {
            log.debug("[process][文档状态无需推进][documentId={}]", lease.documentId());
        }
        AiKnowledgeIngestionStep step = steps.getIfAvailable();
        if (step == null) {
            return fail(lease, REASON_PARSER_UNAVAILABLE, version);
        }
        AiKnowledgeIngestionStepOutcome outcome;
        try {
            outcome = step.process(lease);
        } catch (RuntimeException failure) {
            // 只记录异常类型，不回显正文
            log.warn(
                    "[process][入库步骤失败][taskId={}, step={}, reason={}]",
                    lease.taskId(),
                    step.name(),
                    failure.getClass().getSimpleName());
            outcome = AiKnowledgeIngestionStepOutcome.failed("step-failed");
        }
        if (outcome == null || !outcome.isSucceeded()) {
            String reason = outcome == null ? "step-failed" : outcome.reasonCode();
            return fail(lease, reason == null ? "step-failed" : reason, version);
        }
        boolean finished = ingestionService.finish(lease, AiKnowledgeIngestionTaskDO.STATUS_SUCCEEDED, null);
        if (!finished) {
            // 栅栏未命中：租约已被接管，结果不能算作本次成功
            log.warn("[process][任务终态栅栏未命中][taskId={}]", lease.taskId());
            return false;
        }
        return true;
    }

    private boolean fail(AiKnowledgeIngestionTaskLeaseDTO lease, String reason, AiKnowledgeDocumentVersionDO version) {
        boolean finished = ingestionService.finish(lease, AiKnowledgeIngestionTaskDO.STATUS_FAILED, reason);
        if (!finished) {
            log.warn("[process][任务终态栅栏未命中][taskId={}]", lease.taskId());
            return false;
        }
        // 版本与文档标失败：旧可用版本不受影响（K02 的 AT-024 语义）
        documentService.markVersionFailed(lease.documentId(), version.getId(), reason);
        return false;
    }

    /** 文件引用是否仍有效：绑定存在且未被释放（A07 的当前归属判定）。 */
    private boolean fileReferenceValid(Long fileId) {
        if (fileId == null) {
            return false;
        }
        return fileBindingMapper.selectActiveByFile(fileId).stream()
                .anyMatch(binding -> AiFileBindingDO.STATUS_ACTIVE.equals(binding.getStatus()));
    }

    /** 供测试断言：worker 标识包含配置前缀。 */
    String workerId() {
        return workerId;
    }

    /** 文档状态常量引用（避免魔法字符串散落）。 */
    static String pendingStatus() {
        return AiKnowledgeDocumentDO.STATUS_PENDING;
    }
}
