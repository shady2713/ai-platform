package com.basicframework.module.ai.service.speech.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 语音转写受理请求（服务层 DTO，X04）：私有音频引用 + 可选语言提示。
 *
 * <p>音频只以**文件编号 + 声明级元数据**出现（不含字节、不含上游地址）：字节由服务层按 A07
 * 业务 ACL 读取并核验，受理与执行各一次。
 */
@Data
@Accessors(chain = true)
public class AiSpeechTranscribeDTO {

    /** 幂等键（调用方提供；同一主体在同一应用内唯一） */
    private String requestKey;

    /** 模型端点编号（能力必须已声明且探测确认） */
    private Long endpointId;

    /** 待转写音频的平台私有文件编号 */
    private Long sourceFileId;

    /** 音频声明 MIME（白名单内的取值） */
    private String sourceMime;

    /** 音频声明字节数 */
    private Long sourceSizeBytes;

    /** 音频声明摘要（可为空；非空必须与真实内容一致） */
    private String sourceSha256;

    /** 音频声明时长（毫秒；必填且不超平台单段上限） */
    private Long sourceDurationMillis;

    /** 语言提示（冻结语言集合内的取值；为空表示由端点自行识别） */
    private String languageHint;
}
