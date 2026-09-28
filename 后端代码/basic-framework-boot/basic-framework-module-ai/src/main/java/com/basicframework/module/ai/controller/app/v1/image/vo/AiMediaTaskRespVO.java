package com.basicframework.module.ai.controller.app.v1.image.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 媒体任务（协议层）：任务事实 + 产物 + 实际用量。
 *
 * <p>协议里没有上游地址、供应商或下载链接：产物读取一律走受控文件接口（业务 ACL 判定）。
 */
@Data
@Accessors(chain = true)
@Schema(description = "媒体任务（状态/失败原因/产物/用量）")
public class AiMediaTaskRespVO {

    @Schema(description = "任务编号", example = "512")
    private Long id;

    @Schema(description = "媒体种类（IMAGE/AUDIO）", example = "IMAGE")
    private String mediaKind;

    @Schema(description = "操作（GENERATE/EDIT/TRANSCRIBE/SYNTHESIZE）", example = "GENERATE")
    private String operation;

    @Schema(description = "能力（与模型能力词汇同名）", example = "IMAGE_GENERATION")
    private String capability;

    @Schema(description = "状态（QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED）", example = "QUEUED")
    private String status;

    @Schema(description = "失败原因（稳定错误码；成功为空）", example = "1_003_010_005")
    private String failureCode;

    @Schema(description = "生成提示词/编辑指令/合成文本")
    private String inputText;

    @Schema(description = "源文件编号（生成类为空）", example = "1024")
    private Long sourceFileId;

    @Schema(description = "受理时固定的模型端点编号", example = "7")
    private Long endpointId;

    @Schema(description = "请求产物数量", example = "1")
    private Integer outputCount;

    @Schema(description = "请求输出格式", example = "png")
    private String outputFormat;

    @Schema(description = "TTS 音色标识（受理时固定；生成类为空）", example = "Alloy")
    private String voice;

    @Schema(description = "STT 语言提示（受理时固定；为空表示由端点自行识别）", example = "zh-CN")
    private String languageHint;

    @Schema(description = "已落库产物数量", example = "1")
    private Integer resultCount;

    @Schema(description = "实际用量（未知时来源 UNKNOWN 且数量为空）")
    private AiMediaUsageRespVO usage;

    @Schema(description = "受理时间")
    private LocalDateTime createTime;

    @Schema(description = "产物（序号升序；进行中与失败为空列表）")
    private List<AiMediaAssetRespVO> assets;
}
