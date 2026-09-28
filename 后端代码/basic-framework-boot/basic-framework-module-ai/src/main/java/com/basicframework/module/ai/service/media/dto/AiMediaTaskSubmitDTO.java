package com.basicframework.module.ai.service.media.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 媒体任务受理请求（服务层 DTO，X03）。
 *
 * <p>受理只接受**引用与受控参数**：源文件用编号 + 声明级元数据（MIME/字节数/摘要），不接受字节、
 * 不接受上游地址；调用方（图片/语音服务）已经把参数收窄到平台与端点允许的范围。
 */
@Data
@Accessors(chain = true)
public class AiMediaTaskSubmitDTO {

    /** 幂等键（调用方提供；同一主体在同一应用内唯一） */
    private String requestKey;

    /** 媒体种类（IMAGE/AUDIO） */
    private String mediaKind;

    /** 操作（GENERATE/EDIT/TRANSCRIBE/SYNTHESIZE） */
    private String operation;

    /** 能力（与 ModelCapability 同名；执行前据此走媒体准入） */
    private String capability;

    /** 模型端点编号 */
    private Long endpointId;

    /** 文本输入（生成提示词/编辑指令/合成文本；可为空） */
    private String inputText;

    /** 源文件编号（编辑底图/待转写音频；生成类为空） */
    private Long sourceFileId;

    /** 源文件声明 MIME */
    private String sourceMime;

    /** 源文件声明字节数 */
    private Long sourceSizeBytes;

    /** 源文件声明摘要 */
    private String sourceSha256;

    /** 目标尺寸（宽x高；为空表示由端点默认值决定） */
    private String targetSize;

    /** 请求产物数量 */
    private Integer outputCount;

    /** 请求输出格式 */
    private String outputFormat;

    /** TTS 音色标识（X04；为空表示端点默认音色） */
    private String voice;

    /** STT 语言提示（X04；为空表示由端点自行识别） */
    private String languageHint;
}
