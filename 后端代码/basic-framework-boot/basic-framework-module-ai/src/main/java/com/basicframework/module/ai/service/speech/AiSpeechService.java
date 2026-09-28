package com.basicframework.module.ai.service.speech;

import com.basicframework.module.ai.service.media.dto.AiMediaTaskResultDTO;
import com.basicframework.module.ai.service.speech.dto.AiSpeechSynthesizeDTO;
import com.basicframework.module.ai.service.speech.dto.AiSpeechTranscribeDTO;

/**
 * 非实时语音服务（X04）：STT/TTS 的受理入口，复用 X03 的媒体任务语义。
 *
 * <p>与图片端点同一条不变量：
 * <ol>
 *   <li><b>受理前核验输入</b>：转写在受理时按当前主体读一次音频（A07 业务 ACL）并核验
 *       声明/内容/格式，无权与伪装在受理前就拒绝；执行期还会再读一次（受理时能读、执行时失权同样拒绝）；</li>
 *   <li><b>受理即固定</b>：端点、配置版本、模型标识、音色与输出格式在受理时写入任务行；</li>
 *   <li><b>参数先收窄</b>：格式/时长/文本/音色/语言全部命中 X01 冻结的取值集合，非法请求不进任务表。</li>
 * </ol>
 */
public interface AiSpeechService {

    /** 受理语音转写（STT）：音频必须是当前主体有权读取的私有音频。 */
    AiMediaTaskResultDTO transcribe(AiSpeechTranscribeDTO request);

    /** 受理语音合成（TTS）：文本 + 受控的音色/输出格式。 */
    AiMediaTaskResultDTO synthesize(AiSpeechSynthesizeDTO request);
}
