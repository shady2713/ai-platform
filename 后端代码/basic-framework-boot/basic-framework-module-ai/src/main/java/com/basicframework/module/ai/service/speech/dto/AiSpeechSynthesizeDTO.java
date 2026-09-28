package com.basicframework.module.ai.service.speech.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 语音合成受理请求（服务层 DTO，X04）：合成文本 + 受控的可选参数。
 *
 * <p>文本、音色与输出格式在受理时收窄并固定；执行期不再接受任何来自请求的补充参数。
 */
@Data
@Accessors(chain = true)
public class AiSpeechSynthesizeDTO {

    /** 幂等键（调用方提供；同一主体在同一应用内唯一） */
    private String requestKey;

    /** 模型端点编号（能力必须已声明且探测确认） */
    private Long endpointId;

    /** 待合成文本（非空，最长 4096 码元） */
    private String text;

    /** 音色标识（形状受控；为空表示端点默认音色，具体音色表由端点声明） */
    private String voice;

    /** 输出格式（mp3/wav/opus；为空按 mp3） */
    private String outputFormat;
}
