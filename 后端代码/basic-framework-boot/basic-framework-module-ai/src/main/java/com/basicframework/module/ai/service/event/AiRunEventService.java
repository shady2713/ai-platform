package com.basicframework.module.ai.service.event;

import com.basicframework.module.ai.service.event.dto.AiRunEventDTO;
import com.basicframework.module.ai.service.event.dto.AiRunEventSnapshotDTO;
import java.util.List;

/**
 * 运行事件（O05）。
 *
 * <p>约束：
 * <ul>
 *   <li><b>序号并发安全</b>：序号由运行行在行锁内分配，同一运行的事件严格递增且不重复；</li>
 *   <li><b>状态与事件同事务</b>：写状态与写事件在同一个事务里提交，订阅者不会看到错位；</li>
 *   <li><b>有界重放</b>：按 afterSeq 推进、单次条数有上限；窗口外的请求返回
 *       {@code AI_RUN_EVENT_WINDOW_EXPIRED}，并用 {@link #snapshot} 给出当前状态与最新序号；</li>
 *   <li><b>取消是显式动作</b>：只有非终态运行可取消；取消写入终态事件并同时终止任务。</li>
 * </ul>
 */
public interface AiRunEventService {

    /** 追加事件（在调用方的事务内分配序号并写入；状态变化与事件一起提交）。 */
    AiRunEventDTO append(Long runId, String status, String blockType, String blockJson);

    /** 按序号重放（afterSeq 之后最多 limit 条，升序）。 */
    List<AiRunEventDTO> replay(Long runId, Integer afterSeq, int limit);

    /** 运行快照：当前状态 + 最新/最早序号（重放窗口过期时使用）。 */
    AiRunEventSnapshotDTO snapshot(Long runId);

    /** 取消运行（幂等：已终态返回状态冲突）；写入终态事件并终止任务。 */
    void cancel(Long runId, Integer version);
}
