package com.basicframework.module.ai.controller.admin.crosssource.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import lombok.Data;

/**
 * 单个来源在跨源合并中贡献的金额（Y07 对外契约）。
 *
 * <p>只含<b>角色与金额</b>两项。刻意不含数据集编号、来源系统标识与实体键：
 * 这三者各自都是一份跨系统事实（Y05 专项二：实体映射本身就是授权对象），
 * 把它们放进响应等于把"你无权的那部分"换个字段名再送一次。
 *
 * <p>本 VO 只在口径为 {@code COMPLETE} 或 {@code PARTIAL} 且调用方角色允许
 * 查看分来源明细时才有内容；角色不允许时为空列表，{@code WITHHELD} 时同样为空。
 */
@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class AiCrossSourceSourceAmountVO {

    /** 来源角色。 */
    private String role;

    /** 该来源预聚合后的金额。 */
    private BigDecimal amount;
}
