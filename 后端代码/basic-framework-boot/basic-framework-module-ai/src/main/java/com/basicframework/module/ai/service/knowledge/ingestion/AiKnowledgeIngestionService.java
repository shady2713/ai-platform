package com.basicframework.module.ai.service.knowledge.ingestion;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionRequestDTO;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionResultDTO;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionTaskLeaseDTO;
import java.util.List;

/**
 * 文档入库（K03）：版本 + 任务的同一事务创建，后台按租约处理。
 *
 * <p>不变量：
 * <ol>
 *   <li><b>无悬空状态</b>：版本与任务同事务创建；失败整笔回滚，上传方负责补偿解除文件引用
 *       （{@link #ingest} 在事务边界外捕获失败并释放文件）；</li>
 *   <li><b>无重复可见版本</b>：同一 sourceKey + 同指纹复用既有版本与任务，不产生第二次副作用；</li>
 *   <li><b>失败不回显原始异常</b>：任务只落脱敏稳定原因码（{@code last_error_code}），
 *       文档与版本同样只落原因码。</li>
 * </ol>
 */
public interface AiKnowledgeIngestionService {

    /** 入库：校验文件归属（A07 业务键 = 知识库标识）→ 创建/复用版本 → 同事务创建任务。 */
    AiKnowledgeIngestionResultDTO ingest(AiKnowledgeIngestionRequestDTO request);

    /** 领取任务（CAS；返回的租约由调用方在事务外执行）。 */
    List<AiKnowledgeIngestionTaskLeaseDTO> claim(String workerId, int limit, int leaseSeconds);

    /** 续租；返回 false 表示租约已失效（worker 必须停止且不得写入结果）。 */
    boolean heartbeat(AiKnowledgeIngestionTaskLeaseDTO lease, int leaseSeconds);

    /** 写终态并释放租约；返回 false 表示栅栏未命中（结果已被新 worker 接管）。 */
    boolean finish(AiKnowledgeIngestionTaskLeaseDTO lease, String status, String errorCode);

    /** 恢复过期租约（未达上限回队列、达上限置 FAILED）；返回处理条数。 */
    int recoverExpiredLeases(int retryDelaySeconds, int limit);

    /** 人工重试（仅 FAILED/UNKNOWN 可重试；重置尝试计数）。 */
    void retry(Long taskId, Integer version);

    /** 查询任务（不存在抛 404）。 */
    AiKnowledgeIngestionTaskDO getTask(Long taskId);

    /** 分页查询任务。 */
    PageResult<AiKnowledgeIngestionTaskDO> getTaskPage(PageParam pageParam, Long knowledgeBaseId, String status);
}
