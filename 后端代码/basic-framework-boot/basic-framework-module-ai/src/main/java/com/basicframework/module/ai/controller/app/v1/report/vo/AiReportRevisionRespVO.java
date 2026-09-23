package com.basicframework.module.ai.controller.app.v1.report.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 报表对话修改响应（应用端协议层 VO）。
 *
 * <p>{@code outcome=APPLIED} 时给出新版本号与**结构性差异**（改了哪些块/数据集），
 * 原版本保持不变；{@code outcome=CLARIFICATION} 时给出追问与有限候选，且未创建版本。
 * 响应不含范围指纹、行范围与结果行（数字正文由读取版本接口提供）。
 */
@Schema(description = "AI 应用端 - 报表对话修改响应")
@Data
@Accessors(chain = true)
public class AiReportRevisionRespVO {

    @Schema(description = "结果类型：APPLIED（已创建新版本）/ CLARIFICATION（需要澄清）")
    private String outcome;

    @Schema(description = "报表编号")
    private Long reportId;

    @Schema(description = "基础版本号")
    private Integer baseVersionNo;

    @Schema(description = "新版本号（APPLIED 时存在）")
    private Integer newVersionNo;

    @Schema(description = "本次是否执行了受控查询（展示类修改为 false）")
    private boolean queryPerformed;

    @Schema(description = "结构性差异")
    private Diff diff;

    @Schema(description = "澄清追问")
    private String clarificationQuestion;

    @Schema(description = "澄清原因（AMBIGUOUS/UNSUPPORTED/OUT_OF_SCOPE）")
    private String clarificationReason;

    @Schema(description = "澄清候选（只来自本次授权目录）")
    private List<Candidate> clarificationCandidates;

    @Schema(description = "说明")
    private List<String> notes;

    /** 结构性差异：改了哪些块与数据集（不含数据行）。 */
    @Schema(description = "报表修订差异")
    @Data
    @Accessors(chain = true)
    public static class Diff {

        @Schema(description = "是否要求重新查询数据")
        private boolean queryRequired;

        @Schema(description = "应用的操作码")
        private List<String> operations;

        @Schema(description = "标题是否变化")
        private boolean titleChanged;

        @Schema(description = "主题是否变化")
        private boolean themeChanged;

        @Schema(description = "布局是否变化")
        private boolean layoutChanged;

        @Schema(description = "新增块")
        private List<String> addedBlocks;

        @Schema(description = "删除块")
        private List<String> removedBlocks;

        @Schema(description = "修改块")
        private List<String> modifiedBlocks;

        @Schema(description = "新增数据集引用")
        private List<String> addedDatasetRefs;

        @Schema(description = "移除数据集引用")
        private List<String> removedDatasetRefs;
    }

    /** 澄清候选。 */
    @Schema(description = "澄清候选")
    @Data
    @Accessors(chain = true)
    public static class Candidate {

        @Schema(description = "逻辑码")
        private String code;

        @Schema(description = "展示名")
        private String label;
    }
}
