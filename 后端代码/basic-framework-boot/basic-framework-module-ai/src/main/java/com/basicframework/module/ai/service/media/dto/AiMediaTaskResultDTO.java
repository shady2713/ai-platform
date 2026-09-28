package com.basicframework.module.ai.service.media.dto;

import com.basicframework.module.ai.dal.dataobject.media.AiMediaTaskDO;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 媒体任务视图（服务层 DTO，X03）：任务事实 + 已落库产物 + 上游真实用量。
 *
 * <p>只暴露平台事实：状态、产物（私有文件编号与尺寸）、用量来源与计数。没有上游地址字段——
 * 上游临时 URL 从不进入平台存储，也不进入协议。
 */
@Data
@Accessors(chain = true)
public class AiMediaTaskResultDTO {

    /** 任务编号 */
    private Long id;

    /** 幂等键 */
    private String requestKey;

    /** 媒体种类（IMAGE/AUDIO） */
    private String mediaKind;

    /** 操作（GENERATE/EDIT/TRANSCRIBE/SYNTHESIZE） */
    private String operation;

    /** 能力（与 ModelCapability 同名） */
    private String capability;

    /** 状态（QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED） */
    private String status;

    /** 失败原因（稳定错误码；成功为空） */
    private String failureCode;

    /** 请求提示词/指令/合成文本 */
    private String inputText;

    /** 源文件编号（生成类为空） */
    private Long sourceFileId;

    /** 受理时固定的模型端点编号 */
    private Long endpointId;

    /** 请求产物数量 */
    private Integer outputCount;

    /** 请求输出格式 */
    private String outputFormat;

    /** TTS 音色标识（X04；为空表示端点默认音色） */
    private String voice;

    /** STT 语言提示（X04；为空表示由端点自行识别） */
    private String languageHint;

    /** 已落库产物数量 */
    private Integer resultCount;

    /** 计量单位（未知为空） */
    private String usageUnit;

    /** 计量数值（未知为空，不写 0） */
    private Long usageQuantity;

    /** 计量来源（REPORTED/UNKNOWN） */
    private String usageSource;

    /** 受理时间 */
    private LocalDateTime createTime;

    /** 产物（序号升序；失败与进行中为空列表） */
    private List<AiMediaAssetDTO> assets = List.of();

    /** 由任务行构造视图（不含产物；产物由调用方补齐）。 */
    public static AiMediaTaskResultDTO from(AiMediaTaskDO task) {
        return new AiMediaTaskResultDTO()
                .setId(task.getId())
                .setRequestKey(task.getRequestKey())
                .setMediaKind(task.getMediaKind())
                .setOperation(task.getOperation())
                .setCapability(task.getCapability())
                .setStatus(task.getStatus())
                .setFailureCode(task.getFailureCode())
                .setInputText(task.getInputText())
                .setSourceFileId(task.getSourceFileId())
                .setEndpointId(task.getEndpointId())
                .setOutputCount(task.getOutputCount())
                .setOutputFormat(task.getOutputFormat())
                .setVoice(task.getVoice())
                .setLanguageHint(task.getLanguageHint())
                .setResultCount(task.getResultCount())
                .setUsageUnit(task.getUsageUnit())
                .setUsageQuantity(task.getUsageQuantity())
                .setUsageSource(task.getUsageSource())
                .setCreateTime(task.getCreateTime());
    }

    /** 补齐产物列表。 */
    public AiMediaTaskResultDTO withAssets(List<AiMediaAssetDTO> values) {
        this.assets = values == null ? List.of() : List.copyOf(values);
        return this;
    }
}
