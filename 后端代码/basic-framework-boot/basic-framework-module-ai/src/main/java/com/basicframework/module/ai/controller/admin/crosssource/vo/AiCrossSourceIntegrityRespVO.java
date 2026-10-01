package com.basicframework.module.ai.controller.admin.crosssource.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 跨源授权完整性口径（Y07 对外契约，与前端 {@code CrossSourceIntegrity} 排他联合逐字对应）。
 *
 * <p>与 Y05 前端的联合类型同一套字面量，<b>不得另造状态名</b>：
 * {@code COMPLETE} / {@code PARTIAL} / {@code WITHHELD}。两侧名字一旦分叉，
 * 前端解析期就会把合法响应当成"契约漂移"整块拒掉——明明有权看却什么都不显示，
 * 比泄露更糟。
 *
 * <p>字段的互斥关系是承重的：
 * <ul>
 *   <li>{@code COMPLETE} —— {@code reason} 必为 {@code null}。放行没有理由可编，
 *       允许它带理由等于允许拼装代码为一个已放行的结果编造解释。</li>
 *   <li>{@code PARTIAL} / {@code WITHHELD} —— {@code reason} 必非空，
 *       告诉调用方"该做什么"（PARTIAL 看合计即可；WITHHELD 该去申请授权，重试无用）。</li>
 * </ul>
 *
 * <p>类上显式声明 {@code ALWAYS}：项目默认 ObjectMapper 配的是
 * {@code NON_NULL}（{@code JsonUtils}），而本契约要求"字段在响应里恒在"——
 * 缺失即 WITHHELD 的判定依赖前端能看见这个键是否缺失。全局 NON_NULL 会把
 * {@code COMPLETE} 的 {@code reason} 直接抹掉，使两侧对不上。
 */
@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class AiCrossSourceIntegrityRespVO {

    /** 口径状态：{@code COMPLETE} / {@code PARTIAL} / {@code WITHHELD}。 */
    private String state;

    /**
     * 口径理由：仅 {@code PARTIAL} / {@code WITHHELD} 非空，{@code COMPLETE} 恒为 null。
     *
     * <p>内容是静态文案，<b>不得</b>携带被禁来源的角色名、数据集编号或任何规模信息——
     * 那些字段一旦出现在提示里，提示本身就成了一条信道。
     */
    private String reason;
}
