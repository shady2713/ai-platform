package com.basicframework.module.ai.service.report.generation.dto;

import com.basicframework.module.ai.domain.result.AiReportResultBlock;
import com.basicframework.module.ai.service.report.validation.AiReportDataBinder;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/** 报表生成结论（R03）：结果块 + 绑定结果 + 尝试次数 + 说明。 */
@Data
@Accessors(chain = true)
public class AiReportGenerationResultDTO {

    /** 结果块（进入会话/报表页的产物） */
    private AiReportResultBlock block;

    /** 绑定结果（渲染用；与块里的数据同源） */
    private AiReportDataBinder.BoundReport bound;

    /** 模型调用次数（含修复） */
    private int attempts;

    /** 是否经过修复 */
    private boolean repaired;

    /** 说明（无数据、降级、修复次数用尽等） */
    private List<String> notes = List.of();

    /** 是否成功产出可用报表 */
    private boolean generated;
}
