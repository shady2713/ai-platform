package com.basicframework.module.ai.service.run;

import com.basicframework.module.ai.service.run.dto.AiRunQueryExecutionFixedRequestDTO;
import com.basicframework.module.ai.service.run.dto.AiRunQueryExecutionRequestDTO;
import com.basicframework.module.ai.service.run.dto.AiRunQueryExecutionResultDTO;

/**
 * 运行侧受控查询执行（R05）：对话链路按**当前权限**执行一次数据查询的唯一入口。
 *
 * <p>为什么单独抽一层而不是让调用方自己拼 D05/D06/D03：一次"按当前权限查数据"要同时满足
 * 计划受控（D05 授权目录 + 结构化计划）、编译受控（D06 标识符白名单 + 值全绑定）、
 * 执行受控（D03 只读账号 + 行数/超时预算）、行范围强制（授权层给出的 {@code QueryScope} 必须拼进 WHERE）。
 * 少任何一道，模型或客户端就能把范围放大。
 *
 * <p>三条不可越过的线：
 * <ol>
 *   <li><b>没有行范围就不执行</b>：空范围意味着"没有约束"，直接拒绝（不退回全库）；</li>
 *   <li><b>只执行已发布且已验证的版本</b>：停用、未发布、未验证、漂移的版本一律拒绝；</li>
 *   <li><b>澄清不猜</b>：模型给出澄清时原样返回追问与有限候选，不选一个口径去执行。</li>
 * </ol>
 */
public interface AiRunQueryExecutionService {

    /** 按当前权限执行一次受控查询（PLAN 或 CLARIFICATION）。 */
    AiRunQueryExecutionResultDTO execute(AiRunQueryExecutionRequestDTO request);

    /**
     * 按当前权限执行一次**已固定的计划**（R06 刷新链路）：不规划、不调用模型，只做
     * 版本可执行性复核 + 执行前再校验 + 编译 + 只读执行。
     *
     * <p>计划来自报表版本里保存的规范化计划（创建时已校验），刷新只是"按当前权限再执行一次"；
     * 行范围仍必须由授权层给出，没有行范围即拒绝（不退回全库）。
     */
    AiRunQueryExecutionResultDTO executeFixed(AiRunQueryExecutionFixedRequestDTO request);
}
