package com.basicframework.module.ai.service.media;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaTaskDO;
import com.basicframework.module.ai.service.media.dto.AiMediaAssetDTO;
import com.basicframework.module.ai.service.media.dto.AiMediaStepOutcome;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskLeaseDTO;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskResultDTO;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskSubmitDTO;
import java.util.List;

/**
 * 媒体任务（X03）：受理即落库的持久任务 + 租约领取，供图片生成/编辑与（X04）非实时语音共用。
 *
 * <p>不变量：
 * <ol>
 *   <li><b>幂等受理</b>：同一主体在同一应用下的同一 {@code request_key} 只受理一次；重复提交返回既有任务，
 *       **不产生第二次上游调用**。同键但参数不同一律拒绝（不是"复用旧结果"）；</li>
 *   <li><b>受理即固定</b>：端点与配置版本在受理时写入任务行，之后改端点配置不会改变已受理任务的语义；</li>
 *   <li><b>终态唯一且不可覆盖</b>：落终态带 (owner, epoch) 栅栏 + 状态 CAS，晚到的旧 worker 命中 0 行；</li>
 *   <li><b>无主体范围不返回</b>：查询/取消一律带"应用 + 主体类型 + 主体标识"，越权与不存在同语义（404）。</li>
 * </ol>
 */
public interface AiMediaTaskService {

    /** 受理（幂等）：参数不合规由调用方先收窄，本方法只做幂等与持久化。 */
    AiMediaTaskResultDTO submit(AiMediaTaskSubmitDTO request);

    /** 查询任务（含产物；越权与不存在同语义）。 */
    AiMediaTaskResultDTO getTask(Long taskId);

    /** 分页查询（按主体范围；可按媒体种类与状态过滤）。 */
    PageResult<AiMediaTaskResultDTO> getTaskPage(PageParam pageParam, String mediaKind, String status);

    /** 取消（仅待领取状态可取消；执行中不可中断，见实现语义）。 */
    AiMediaTaskResultDTO cancel(Long taskId);

    /** 产物列表（序号升序；越权与不存在同语义）。 */
    List<AiMediaAssetDTO> getAssets(Long taskId);

    /** 领取任务（CAS；返回的租约由调用方在事务外执行）。 */
    List<AiMediaTaskLeaseDTO> claim(String workerId, int limit, int leaseSeconds);

    /** 按编号取任务（仅供后台执行链路使用，不做主体范围判定）。 */
    AiMediaTaskDO getTaskForExecution(Long taskId);

    /** 续租；返回 false 表示租约已失效（worker 必须停止且不得写入结果）。 */
    boolean heartbeat(AiMediaTaskLeaseDTO lease, int leaseSeconds);

    /** 写终态并释放租约；返回 false 表示栅栏未命中（结果已被新 worker 接管）。 */
    boolean finish(AiMediaTaskLeaseDTO lease, AiMediaStepOutcome outcome);

    /** 恢复过期租约（未达上限回队列、达上限置 FAILED）；返回处理条数。 */
    int recoverExpiredLeases(int retryDelaySeconds, int limit);
}
