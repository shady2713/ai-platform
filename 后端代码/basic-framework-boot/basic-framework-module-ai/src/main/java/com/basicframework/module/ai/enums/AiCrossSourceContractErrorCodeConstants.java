package com.basicframework.module.ai.enums;

import com.basicframework.framework.common.exception.ErrorCode;

/**
 * 跨源结果契约错误码（Y07，{@code 1_003_020_xxx}）。
 *
 * <p>本区间回答的是"**这份跨源结果能不能作为一份响应交出去**"，与两个既有区间分工明确：
 * {@code 1_003_017_xxx}（Y04）回答"这次为什么没跑完"，{@code 1_003_018_xxx}（Y05）回答
 * "这次为什么不允许你看"。本区间只覆盖前两者都答完之后、**组装响应**这一步的失败：
 * 台账里没有这次执行、执行结果处于不可出具状态、或请求根本凑不出一条可判定的跨源响应。
 *
 * <p>刻意不复用 Y05 的编号：Y05 的拒绝消息与编号是一份对外承诺，调用方按编号区分
 * "该去申请授权"还是"该销毁旧产物"；把"台账里没这条执行"塞进同一区间，
 * 会让调用方在"我无权"与"我查错了"之间误判处置动作。
 *
 * <p><b>命名是承重的</b>：HTTP 状态由常量名派生——后缀 {@code _NOT_EXISTS} → 404，
 * 名称含 {@code CONFLICT}/{@code EXISTS}/{@code DUPLICATE} → 409，其余 422（ADR 0003）。
 * 去掉 {@code _CONFLICT} 会静默把 409 变成 422。
 *
 * <p>与 Y04/Y05 一致：<b>消息全静态，不带任何插参</b>。拒绝路径上最不该出现的就是被拒对象的标识，
 * 执行键、来源角色、金额一律不进消息。
 */
public interface AiCrossSourceContractErrorCodeConstants {

    /**
     * 跨源执行台账里没有这条执行记录（404）。
     *
     * <p>承重命名：{@code GlobalExceptionHandler.resolveHttpStatus} 只认常量名后缀
     * {@code _NOT_EXISTS} → 404。它与 Y05 的 {@code AI_CROSS_SOURCE_AUTHZ_EXECUTION_NOT_EXISTS}
     * 编号不同但语义相邻：本编号回答"这次执行根本没跑过/已被清理"，
     * Y05 那个回答"这次执行存在但你不被允许看"。
     */
    ErrorCode AI_CROSS_SOURCE_CONTRACT_EXECUTION_NOT_EXISTS = new ErrorCode(1_003_020_000, "跨源执行记录不存在");

    /**
     * 跨源执行结果不可出具（409）：台账存在，但结果处于受控结束或口径不完整的状态。
     *
     * <p>与"无权"分开编号：受控结束（预算超限、必需来源失败、时间点偏移超限）是**技术**失败，
     * 处置动作是修数据或换执行键重跑；无权是**授权**失败，处置动作是申请授权。
     * 两者混用会让调用方在"重试"与"申请权限"之间误判。
     */
    ErrorCode AI_CROSS_SOURCE_CONTRACT_RESULT_NOT_ISSUABLE_CONFLICT = new ErrorCode(1_003_020_001, "跨源执行结果不可出具");

    /**
     * 跨源响应请求不合法（422）：凑不出一条可判定的跨源响应（缺执行键、缺主体上下文、角色词表为空）。
     *
     * <p>按 fail-closed 处理，不按"默认放行"：主体上下文缺失时无法确定授权事实，
     * 查不到授权不等于仍然有权。
     */
    ErrorCode AI_CROSS_SOURCE_CONTRACT_REQUEST_INVALID = new ErrorCode(1_003_020_002, "跨源响应请求不合法");
}
